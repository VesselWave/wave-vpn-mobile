# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import sys
import types
import json
import tempfile
from unittest.mock import Mock
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run


class Radio:
    def __init__(self, mobile='0'):
        self.mobile = mobile
        self.enabled = True
        self.events = []
    def setting(self, *args):
        return self.mobile
    def shell(self, *args):
        if args[:2] == ('svc', 'wifi'):
            self.enabled = args[2] == 'enable'; self.events.append(args[2])
        return 'Wifi is connected BSSID: 02:11:22:33:44:55' if self.enabled else 'Wifi is disabled'


class WifiRecoveryTests(unittest.TestCase):
    def owner(self, radio, fail=False):
        def wait(predicate, *args):
            if not predicate(): raise run.Failure('state not observed')
        def observe(name, seconds, action):
            detail = action()
            if fail: raise run.Failure('capture failed')
            return detail
        return types.SimpleNamespace(d=radio, config={}, capture={}, state={}, persist=lambda: None,
                                     wait_for=wait, observe=observe)

    def test_wifi_cycle_verified_and_restored_after_capture_failure(self):
        for fail in (False, True):
            radio = Radio(); owner = self.owner(radio, fail)
            with patch.object(run.time, 'sleep'):
                if fail:
                    with self.assertRaises(run.Failure): run.Run.lifecycle(owner, 'wifi-reconnect')
                else:
                    self.assertTrue(run.Run.lifecycle(owner, 'wifi-reconnect')['wifi_disabled_verified'])
            self.assertTrue(radio.enabled)
            self.assertEqual(radio.events, ['disable', 'enable', 'enable'])
            self.assertNotIn('radios_changed', owner.state)

    def test_wifi_password_uses_private_stdin_not_adb_arguments(self):
        radio = Radio(); radio.call = Mock()
        owner = self.owner(radio)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'account.json'
            path.write_text(json.dumps({'ssid': 'Test AP', 'password': 'private-wifi-password'}))
            path.chmod(0o600)
            owner.config['wifi_reconnect_credentials_file'] = str(path)
            with patch.object(run.time, 'sleep'):
                result = run.Run.lifecycle(owner, 'wifi-reconnect')
            args, kwargs = radio.call.call_args
            self.assertNotIn('private-wifi-password', repr(args))
            self.assertIn(b'private-wifi-password', kwargs['data'])
            self.assertTrue(result['explicit_wifi_network_selection'])

    def test_fixed_mac_scope_cannot_pass_after_identity_is_lost(self):
        radio = Radio(); owner = self.owner(radio)
        owner.capture['client_mac'] = '02:11:22:33:44:66'
        with patch.object(run.time, 'sleep'), self.assertRaises(run.Failure):
            run.Run.lifecycle(owner, 'wifi-reconnect')
        self.assertTrue(radio.enabled)
        self.assertNotIn('radios_changed', owner.state)

    def test_cellular_enabled_is_rejected_before_mutation(self):
        radio = Radio('1')
        with self.assertRaises(run.Failure): run.Run.lifecycle(self.owner(radio), 'wifi-reconnect')
        self.assertEqual(radio.events, [])


if __name__ == '__main__': unittest.main()
