#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build a signed Android candidate from clean, tagged source; never publish."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile


REPO = Path(__file__).resolve().parents[1]
SIGNING_VARIABLES = (
    'WAVE_ANDROID_KEYSTORE', 'WAVE_ANDROID_KEY_ALIAS',
    'WAVE_ANDROID_KEYSTORE_PASSWORD', 'WAVE_ANDROID_KEY_PASSWORD',
)


def run(*args, capture=False):
    return subprocess.run(args, cwd=REPO, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def version_info(text):
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
    code = re.search(r'^\s*versionCode\s*=\s*(\d+)\s*$', text, re.M)
    if not name or not code or not re.fullmatch(r'\d+\.\d+\.\d+(?:-rc\.\d+)?', name[1]):
        raise ValueError('Set a release version (X.Y.Z or X.Y.Z-rc.N) before packaging')
    return name[1], int(code[1])


def verify_signer(output, expected):
    # Require exactly one current APK signer. Do not accept a matching digest
    # from another certificate or a multi-signer artifact.
    count = re.findall(r'^Number of signers:\s*(\d+)\s*$', output, re.M)
    digests = re.findall(r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer):? certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$', output, re.M)
    if count != ['1'] or not digests or {item.lower() for item in digests} != {expected.lower()}:
        raise ValueError('APK signer does not match the published production certificate')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sign-key', help='optional full OpenPGP fingerprint for the checksum manifest')
    args = parser.parse_args()
    if args.sign_key and not re.fullmatch(r'(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})', args.sign_key):
        parser.error('--sign-key requires a full fingerprint')
    if run('git', '-C', str(REPO), 'status', '--porcelain', capture=True).strip():
        raise ValueError('Release requires a clean checkout')
    version, code = version_info((REPO / 'app/build.gradle.kts').read_text())
    revision = run('git', '-C', str(REPO), 'rev-parse', 'HEAD', capture=True).strip()
    tagged = run('git', '-C', str(REPO), 'rev-parse', f'v{version}^{{commit}}', capture=True).strip()
    if revision != tagged:
        raise ValueError(f'Tag v{version} must identify HEAD')
    if not all(os.environ.get(key) for key in SIGNING_VARIABLES):
        raise ValueError('Supply all four documented Android signing variables privately')
    sdk_value = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk_value:
        raise ValueError('Set ANDROID_HOME to the Android SDK')
    sdk_tools = Path(sdk_value) / 'build-tools' / '37.0.0'
    for tool in ('apksigner', 'zipalign', 'aapt2'):
        if not (sdk_tools / tool).is_file():
            raise ValueError('Install Android build-tools 37.0.0')
    run('./gradlew', '--no-daemon', '--no-configuration-cache', ':core:test',
        ':app:lintDebug', ':app:assembleRelease')
    run('python3', '-m', 'unittest', 'discover', '-s', 'tools/vpn-probe/tests', '-v')
    run('python3', '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v')
    run('python3', 'scripts/test-release.py')
    apk = REPO / 'app/build/outputs/apk/release/app-release.apk'
    cert = run('openssl', 'x509', '-in', str(REPO / 'docs/keys/wave-android-release.pem'),
               '-noout', '-fingerprint', '-sha256', capture=True)
    expected = cert.strip().split('=', 1)[1].replace(':', '')
    signer = run(str(sdk_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk), capture=True)
    verify_signer(signer, expected)
    run(str(sdk_tools / 'zipalign'), '-c', '-P', '16', '4', str(apk))
    badging = run(str(sdk_tools / 'aapt2'), 'dump', 'badging', str(apk), capture=True)
    if not re.search(r"^package: name='me\.vwave\.vpn' versionCode='" + str(code) +
                     r"' versionName='" + re.escape(version) + "'", badging, re.M):
        raise ValueError('Built APK package/version differs from tagged source')
    # Gradle and checks must not have modified source files.
    if run('git', '-C', str(REPO), 'status', '--porcelain', capture=True).strip():
        raise ValueError('Build modified source; refusing candidate')
    output = REPO / 'app/build/distribution' / revision
    output.mkdir(parents=True, exist_ok=False)
    name = f'wave-vpn-android-{version}'
    with tempfile.TemporaryDirectory() as temporary:
        package = Path(temporary) / name
        package.mkdir()
        shutil.copy2(apk, package / f'{name}.apk')
        for source in ('LICENSE', 'README.md', 'SECURITY.md', 'CHANGELOG.md'):
            shutil.copy2(REPO / source, package / source)
        shutil.copytree(REPO / 'licenses', package / 'licenses')
        shutil.copytree(REPO / 'docs', package / 'docs')
        record = {'version': version, 'versionCode': code, 'revision': revision,
                  'apk_sha256': digest(apk), 'signer_sha256': expected.lower(),
                  'build_tools': '37.0.0'}
        (package / 'SOURCE.json').write_text(json.dumps(record, indent=2) + '\n')
        (package / 'APK-SIGNATURE.txt').write_text(signer)
        (package / 'TOOLCHAIN.txt').write_text(run('./gradlew', '--no-configuration-cache', '--version', capture=True))
        (package / 'DEPENDENCIES.txt').write_text(run('./gradlew', '--no-configuration-cache',
            ':app:dependencies', '--configuration', 'releaseRuntimeClasspath', capture=True))
        with tarfile.open(output / f'{name}.tar.gz', 'w:gz') as archive:
            archive.add(package, arcname=name)
    run('git', '-C', str(REPO), 'archive', '--format=tar.gz', f'--prefix={name}-source/',
        f'--output={output / (name + "-source.tar.gz")}', 'HEAD')
    shutil.copy2(apk, output / f'{name}.apk')
    manifest = output / f'{name}-SHA256SUMS'
    manifest.write_text(''.join(f'{digest(path)}  {path.name}\n' for path in sorted(path for path in output.iterdir() if path.name.endswith(('.tar.gz', '.apk')))))
    if args.sign_key:
        signature = output / f'{name}-SHA256SUMS.asc'
        run('gpg', '--local-user', args.sign_key, '--armor', '--detach-sign',
            '--output', str(signature), str(manifest))
        public = output / f'{name}-signing-key.asc'
        public.write_text(run('gpg', '--armor', '--export', args.sign_key, capture=True))
        run('bash', 'scripts/verify-release.sh', str(public), str(manifest), str(signature), args.sign_key)
    print(f'Candidate only, not published: {output}')
    print('Production qualification gates remain independent; see documented prerelease limitations.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
