# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dualstack_hotspot as ap


class DualstackTests(unittest.TestCase):
    def test_bad_interfaces_and_endpoints_never_contact_hosts(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(ap, 'call') as remote:
            base = ['prepare', '--output', str(Path(temp)/'new'), '--sigma-ssh', 'root@sigma',
                    '--vps-ssh', 'user@vps', '--vps-ipv4', '192.0.2.1']
            for extra in [['--radio', 'wlan0;id'], ['--vps-interface', 'lo'], ['--vps-ipv4', '::1']]:
                with self.assertRaises(ValueError): ap.main(base + extra)
            remote.assert_not_called()

    def test_existing_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(ap, 'call') as remote:
            file=Path(temp)/'settings.json';file.write_text('original')
            with self.assertRaises(FileExistsError):
                ap.main(['prepare','--output',temp,'--sigma-ssh','root@sigma',
                         '--vps-ssh','user@vps','--vps-ipv4','192.0.2.1'])
            self.assertEqual(file.read_text(),'original');remote.assert_not_called()

    def test_remote_errors_do_not_export_credentials(self):
        with tempfile.TemporaryDirectory() as temp:
            settings={'vps_ipv4':'192.0.2.1','radio':'wlan0','phy':'phy0','vps_interface':'eth0'}
            result=subprocess.CompletedProcess([],1,'private-key-value','private-password')
            with patch.object(ap,'P',Path(temp)), patch.object(ap,'SETTINGS',settings), patch.object(ap.subprocess,'run',return_value=result):
                with self.assertRaises(RuntimeError) as error: ap.call(['ssh','host'],'private-config')
                self.assertNotIn('private-key-value',str(error.exception))
                self.assertIn('private-key-value',(Path(temp)/'dualstack-last-error.txt').read_text())

    def test_cleanup_attempts_both_hosts_and_retains_keys_if_one_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            file=Path(temp)/'tunnel-keys.json';file.write_text('private')
            with patch.object(ap,'P',Path(temp)),patch.object(ap,'SIGMA',['sigma']),patch.object(ap,'VPS',['vps']),patch.object(ap,'call',side_effect=[RuntimeError('failed'),'']) as remote:
                with self.assertRaises(RuntimeError):ap.stop()
                self.assertEqual(remote.call_count,2);self.assertTrue(file.exists())

    def test_successful_cleanup_removes_local_private_keys(self):
        with tempfile.TemporaryDirectory() as temp:
            for name in ['tunnel-keys.json','dualstack-account.json']:(Path(temp)/name).write_text('private')
            with patch.object(ap,'P',Path(temp)),patch.object(ap,'call',return_value=''),contextlib.redirect_stdout(io.StringIO()):ap.stop()
            self.assertFalse((Path(temp)/'tunnel-keys.json').exists())
            self.assertFalse((Path(temp)/'dualstack-account.json').exists())


if __name__ == '__main__': unittest.main()
