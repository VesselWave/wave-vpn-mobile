# SPDX-License-Identifier: GPL-3.0-or-later
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import matrix


class MatrixTests(unittest.TestCase):
    def test_interrupt_waits_for_child_cleanup_and_is_forwarded_once(self):
        with tempfile.TemporaryDirectory() as directory:
            ready, done = Path(directory) / 'ready', Path(directory) / 'done'
            program = '''import signal,sys,time
from pathlib import Path
def stop(*_):
    time.sleep(0.2)
    Path(sys.argv[2]).touch()
    sys.exit(130)
signal.signal(signal.SIGTERM, stop)
Path(sys.argv[1]).touch()
time.sleep(3)
'''
            def interrupt():
                deadline = time.monotonic() + 2
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(0.01)
                if ready.exists():
                    os.kill(os.getpid(), signal.SIGINT)
                    time.sleep(0.03)
                    os.kill(os.getpid(), signal.SIGTERM)
            worker = threading.Thread(target=interrupt)
            worker.start()
            try:
                code = matrix.launch([sys.executable, '-c', program, str(ready), str(done)])
            finally:
                worker.join()
            self.assertEqual(code, 130)
            self.assertTrue(done.exists())

    def exercise(self, cleanup_pending):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / 'config.json'
            config.write_text(json.dumps({'version': 1, 'profiles': {
                name: {'package': 'test.' + name, 'adb': {'serial': 'test-only'},
                       'capture': {'kind': 'emulator', 'interface': 'wlan0'}}
                for name in ('a', 'b')}}))
            calls = []
            def launch(argv):
                destination = Path(argv[argv.index('--output') + 1])
                (destination / 'private').mkdir(parents=True)
                (destination / 'private/state.json').write_text(json.dumps({'pending_cleanup': cleanup_pending}))
                calls.append(argv[argv.index('--profile') + 1])
                return 1
            argv = ['matrix.py', '--config', str(config), '--profiles', 'a,b',
                    '--output', str(root / 'results'), '--repetitions', '2']
            with patch.object(sys, 'argv', argv), patch.object(matrix, 'launch', side_effect=launch), patch.object(matrix, 'Device') as device:
                device.return_value.setting.return_value = 'null'
                code = matrix.main()
            manifest = json.loads((root / 'results/matrix.json').read_text())
            return calls, code, manifest

    def test_cleanup_failure_stops_before_next_vpn(self):
        calls, code, manifest = self.exercise(True)
        self.assertEqual(calls, ['a'])
        self.assertNotEqual(code, 0)
        self.assertFalse(manifest['complete'])

    def test_privacy_failures_retained_and_order_rotates(self):
        calls, code, manifest = self.exercise(False)
        self.assertEqual(calls, ['a', 'b', 'b', 'a'])
        self.assertEqual(code, 1)
        self.assertTrue(manifest['complete'])
        self.assertEqual(len({item['report'] for item in manifest['runs']}), 4)
