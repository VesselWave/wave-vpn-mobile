# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import hotspot


class HotspotTests(unittest.TestCase):
    def test_invalid_target_never_contacts_host(self):
        with patch.object(hotspot, 'remote') as remote:
            for target in ['-oProxyCommand=bad', 'root@host;whoami']:
                with self.assertRaises(ValueError):
                    hotspot.main(['status', '--ssh=' + target])
            remote.assert_not_called()

    def test_start_preserves_existing_private_directory(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(hotspot, 'remote') as remote:
            path = Path(temp) / 'evidence'; path.mkdir()
            file = path / 'account.json'; file.write_text('original')
            with self.assertRaises(FileExistsError):
                hotspot.main(['start', '--ssh', 'root@host', '--output', str(path)])
            self.assertEqual(file.read_text(), 'original')
            remote.assert_not_called()

    def test_password_only_sent_over_stdin_and_saved_privately(self):
        import subprocess
        with tempfile.TemporaryDirectory() as temp, patch.object(hotspot.subprocess, 'run') as run:
            run.return_value = subprocess.CompletedProcess([], 0, '', '')
            output = Path(temp) / 'ap'
            console = io.StringIO()
            with contextlib.redirect_stdout(console):
                hotspot.main(['start', '--ssh', 'root@host', '--output', str(output)])
            password = json.loads((output / 'account.json').read_text())['password']
            args, kwargs = run.call_args
            self.assertNotIn(password, repr(args))
            self.assertIn(password, kwargs['input'])
            self.assertNotIn(password, console.getvalue())
            self.assertEqual((output.stat().st_mode & 0o777), 0o700)
            self.assertEqual(((output / 'account.json').stat().st_mode & 0o777), 0o600)


if __name__ == '__main__':
    unittest.main()
