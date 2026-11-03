# SPDX-License-Identifier: GPL-3.0-or-later
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from credentials import write_credentials


class CredentialsTests(unittest.TestCase):
    def test_private_file_preserves_special_characters_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private/account.json'
            password = 'spaces $() quotes " Unicode 漢字'
            write_credentials(path, 'test-user', password)
            self.assertEqual(json.loads(path.read_text())['password'], password)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o700)
            with self.assertRaises(FileExistsError):
                write_credentials(path, 'replacement', 'replacement')
            self.assertEqual(json.loads(path.read_text())['username'], 'test-user')

    def test_repository_and_symlink_destinations_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.git').mkdir()
            with self.assertRaises(ValueError):
                write_credentials(root / 'account.json', 'user', 'password')
            with tempfile.TemporaryDirectory() as other:
                link = Path(other) / 'account.json'
                link.symlink_to(Path(other) / 'missing.json')
                with self.assertRaises(ValueError):
                    write_credentials(link, 'user', 'password')
