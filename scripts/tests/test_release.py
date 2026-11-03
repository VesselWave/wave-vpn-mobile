# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseIdentityTests(unittest.TestCase):
    def test_development_version_cannot_be_packaged(self):
        with self.assertRaises(ValueError):
            release.version_info('versionCode = 2\nversionName = "0.2-dev"')
        self.assertEqual(release.version_info('versionCode = 3\nversionName = "0.2.0-rc.1"'),
                         ('0.2.0-rc.1', 3))

    def test_other_certificate_or_multiple_signers_rejected(self):
        expected = 'a' * 64
        line = f'Number of signers: 1\nSigner #1 certificate SHA-256 digest: {expected}\n'
        release.verify_signer(line, expected)
        release.verify_signer(line.replace('Signer #1', 'V2 Signer:'), expected)
        for output in ('Signer #1 certificate SHA-1 digest: ' + expected,
                       line.replace(expected, 'b' * 64),
                       line.replace('Number of signers: 1', 'Number of signers: 2') +
                       'Signer #2 certificate SHA-256 digest: ' + expected):
            with self.subTest(output=output), self.assertRaises(ValueError):
                release.verify_signer(output, expected)


class CandidateArchiveTests(unittest.TestCase):
    def test_archives_record_revision_and_exclude_ignored_private_inputs(self):
        # Exercise real Git/archive and filesystem behavior with SDK/build
        # commands stubbed. This is a packaging contract, not build evidence.
        original_repo = release.REPO
        expected = '29950a5756876759b28984a0e6b44a276efd948f8e7008c3fff471d4ad418821'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for directory in ('app/build/outputs/apk/release', 'docs/keys', 'licenses', 'sdk/build-tools/37.0.0'):
                (root / directory).mkdir(parents=True)
            (root / '.gitignore').write_text('app/build/\nsdk/\n.env\n')
            (root / '.env').write_text('PRIVATE FIXTURE MUST NOT SHIP')
            (root / 'app/build.gradle.kts').write_text('versionCode = 3\nversionName = "0.2.0-rc.1"\n')
            for name in ('LICENSE', 'README.md', 'SECURITY.md', 'CHANGELOG.md'):
                (root / name).write_text('public fixture\n')
            (root / 'licenses/NOTICE').write_text('fixture dependency notice\n')
            shutil.copy2(original_repo / 'docs/keys/wave-android-release.pem', root / 'docs/keys/wave-android-release.pem')
            apk = root / 'app/build/outputs/apk/release/app-release.apk'
            apk.write_bytes(b'synthetic APK fixture')
            for tool in ('apksigner', 'zipalign', 'aapt2'):
                (root / 'sdk/build-tools/37.0.0' / tool).touch()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
            git('init', '-q')
            git('add', '.')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
            git('tag', 'v0.2.0-rc.1')
            revision = git('rev-parse', 'HEAD')
            real_run = release.run
            def stub_tools(*args, capture=False):
                if args[0] == './gradlew':
                    return 'fixture toolchain/dependencies\n' if capture else None
                if args[0] == 'python3':
                    return None
                if Path(args[0]).name == 'apksigner':
                    return 'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: ' + expected + '\n'
                if Path(args[0]).name == 'aapt2':
                    return "package: name='me.vwave.vpn' versionCode='3' versionName='0.2.0-rc.1'\n"
                if Path(args[0]).name == 'zipalign':
                    return None
                return real_run(*args, capture=capture)
            environment = {key: 'fixture-only' for key in release.SIGNING_VARIABLES}
            environment['ANDROID_HOME'] = str(root / 'sdk')
            with patch.object(release, 'REPO', root), patch.object(release, 'run', stub_tools), \
                 patch.dict(os.environ, environment), patch.object(sys, 'argv', ['release.py']):
                release.main()
                # Existing output must be refused, not mixed with another run.
                with self.assertRaises(FileExistsError):
                    release.main()
            output = root / 'app/build/distribution' / revision
            with tarfile.open(output / 'wave-vpn-android-0.2.0-rc.1-source.tar.gz') as archive:
                names = archive.getnames()
                self.assertTrue(any(name.endswith('/LICENSE') for name in names))
                self.assertFalse(any(name.endswith('/.env') or '/app/build/' in name for name in names))
            with tarfile.open(output / 'wave-vpn-android-0.2.0-rc.1.tar.gz') as archive:
                name = next(name for name in archive.getnames() if name.endswith('/SOURCE.json'))
                record = json.load(archive.extractfile(name))
                self.assertEqual(record['revision'], revision)
                self.assertEqual(record['apk_sha256'], release.digest(apk))
                self.assertTrue(any(name.endswith('/licenses/NOTICE') for name in archive.getnames()))


if __name__ == '__main__':
    unittest.main()
