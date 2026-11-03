# SPDX-License-Identifier: GPL-3.0-or-later
import json
from pathlib import Path
import sys
import tempfile
import types
from unittest.mock import Mock
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run


class StageCompletionTests(unittest.TestCase):
    def test_disconnect_requires_live_sink_even_with_stale_session_name(self):
        sink = ('NetworkAgentInfo{network{257} ni{VPN CONNECTED extra: VPN:me.vwave.vpn} '
                'LinkAddresses: [ 192.0.2.1/32,fd00:7761:7665::1/128 ] '
                'DnsAddresses: [ /192.0.2.2,/fd00:7761:7665::2 ] '
                'Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0,::/0 -> :: tun0 ] '
                'sessionId=wave, bypassable=false}')
        cases = [(sink, '1', True), (sink, '0', False),
                 (sink.replace('192.0.2.1/32', '10.0.0.1/32'), '1', False),
                 (sink.replace('/192.0.2.2', '/10.0.0.2'), '1', False),
                 (sink.replace('::/0 ->', 'fd00::/64 ->'), '1', False),
                 (sink.replace('VPN:me.vwave.vpn}', 'VPN:other.app}'), '1', False),
                 (sink.replace('bypassable=false', 'bypassable=true'), '1', False)]
        for network, lockdown, accepted in cases:
            with self.subTest(network=network, lockdown=lockdown):
                device = Mock()
                device.setting.return_value = lockdown
                device.shell.side_effect = lambda *args: (network if args[-1] == 'connectivity' else
                    '  0: me.vwave.vpn\n    sessionId=wave, bypassable=false\n')
                def wait_for(predicate, seconds, message):
                    if not predicate():
                        raise run.Failure(message)
                owner = types.SimpleNamespace(config={'blocking_vpn_session': 'Wave — blocking traffic'},
                    package='me.vwave.vpn', d=device, open_overview=lambda: ['Disconnect'],
                    active_vpn=lambda: True, wait_for=wait_for)
                if accepted:
                    self.assertTrue(run.Run.disconnect(owner)['active_vpn_after_disconnect'])
                else:
                    with self.assertRaises(run.Failure):
                        run.Run.disconnect(owner)

    def test_missing_vpn_does_not_prove_blocking_handoff(self):
        device = Mock()
        device.setting.return_value = '1'
        def wait_for(predicate, seconds, message):
            if not predicate():
                raise run.Failure(message)
        owner = types.SimpleNamespace(config={'blocking_vpn_session': 'Wave — blocking traffic'},
            package='me.vwave.vpn', d=device, open_overview=lambda: ['Connect'],
            active_vpn=lambda: False, wait_for=wait_for)
        with self.assertRaises(run.Failure):
            run.Run.disconnect(owner)

    def test_capture_ap_pin_rejects_roaming(self):
        owner = types.SimpleNamespace(config={'expected_wifi_bssid': 'AA:BB:CC:DD:EE:FF'}, d=Mock())
        owner.d.shell.return_value = 'Wifi is connected\nBSSID: aa:bb:cc:dd:ee:ff'
        self.assertTrue(run.Run.capture_network_matches(owner))
        owner.d.shell.return_value = 'Wifi is connected\nBSSID: 00:11:22:33:44:55'
        self.assertFalse(run.Run.capture_network_matches(owner))
        owner.d.shell.return_value = 'Wifi is disabled'
        self.assertFalse(run.Run.capture_network_matches(owner))

    def test_dns_baseline_is_explicit_and_validated(self):
        for baseline, expected in [(None, None), ('off', 'off'), ('automatic', 'opportunistic')]:
            owner = types.SimpleNamespace(config={'private_dns_baseline': baseline}, d=Mock())
            run.Run.after_positive_control(owner)
            if expected is None:
                owner.d.put.assert_not_called()
            else:
                owner.d.put.assert_called_once_with('global', 'private_dns_mode', expected)
        owner = types.SimpleNamespace(config={'private_dns_baseline': 'unknown'}, d=Mock())
        with self.assertRaises(run.Failure):
            run.Run.after_positive_control(owner)
        owner.d.put.assert_not_called()

    def test_selected_dns_modes_restore_settings_after_failure(self):
        device = Mock()
        device.setting.side_effect = ['opportunistic', 'saved-provider']
        observed = []
        def observe(name, seconds, action):
            action()
            observed.append(name)
            if name.endswith('strict'):
                raise run.Failure('capture failed')
        owner = types.SimpleNamespace(config={'private_dns_modes': ['automatic', 'strict']},
                                      d=device, observe=observe)
        with self.assertRaises(run.Failure):
            run.Run.private_dns(owner)
        self.assertEqual(observed, ['private-dns-automatic', 'private-dns-strict'])
        self.assertNotIn(unittest.mock.call('global', 'private_dns_mode', 'off'), device.put.call_args_list)
        self.assertEqual(device.put.call_args_list[-2:], [
            unittest.mock.call('global', 'private_dns_mode', 'opportunistic'),
            unittest.mock.call('global', 'private_dns_specifier', 'saved-provider')])

    def test_oem_vpn_button_still_requires_matching_installed_version(self):
        for version, accepted in [('0.2-dev', True), ('another-version', False)]:
            device = Mock()
            device.nodes.return_value = [{'visible': True, 'description': 'Wave VPN Settings', 'label': ''}]
            def wait_for_screen(predicate, timeout):
                labels = [version, 'Always-on VPN']
                if not predicate(labels):
                    raise run.Failure('Wrong settings screen')
                return labels
            device.wait_labels.side_effect = wait_for_screen
            owner = types.SimpleNamespace(config={'vpn_settings_buttons': ['Wave VPN Settings', 'Wave VPN Connected Settings']},
                d=device, state={'artifact': {'version_name': '0.2-dev'}})
            with patch.object(run.time, 'sleep'):
                if accepted:
                    run.Run.vpn_settings(owner)
                else:
                    with self.assertRaisesRegex(run.Failure, 'identify the target'):
                        run.Run.vpn_settings(owner)
            device.put.assert_not_called()

    def test_identical_target_is_preserved_without_installing_again(self):
        device = Mock()
        device.installed.return_value = True
        device.shell.return_value = 'package:/data/app/example/base.apk'
        artifact = {'sha256': 'same-apk', 'signer_sha256': 'trusted'}
        owner = types.SimpleNamespace(d=device, output=Path('/private/qa'),
            state={'artifact': artifact}, artifact=Mock(return_value=artifact), persist=Mock())
        run.Run.install(owner, 'me.vwave.vpn', '/private/target.apk')
        self.assertEqual(device.call.call_count, 1)  # Backup pull only; no installer.
        self.assertEqual(device.call.call_args.args[0], 'pull')
        self.assertFalse(owner.state['target_created'])

    def test_required_ipv6_uplink_cannot_silently_degrade_to_ipv4(self):
        device = Mock()
        device.setting.return_value = 'hostname'
        counts = {'raw_dns4_out': 1, 'dot4_syn': 1, 'direct_https4_syn': 1,
                  'system_dns4_out': 1}
        owner = types.SimpleNamespace(
            capture={'kind': 'gateway'}, d=device, validated_families=[],
            config={'required_capture_families': ['ipv4', 'ipv6']},
            protection=Mock(), disconnect=Mock(), connect=Mock(),
            observe=Mock(return_value=({'capture': {'counts': counts},
                                       'probes': {'ipv6': {'success': 0}}}, [])))
        with self.assertRaisesRegex(run.Failure, 'Required uplink capture families'):
            run.Run.positive_control(owner)
        owner.connect.assert_not_called()
        device.put.assert_called_with('global', 'private_dns_mode', 'hostname')

    def test_observation_pass_cannot_hide_failed_stage_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / 'config.json'
            config.write_text(json.dumps({'version': 1, 'common': {
                'package': 'test.vpn', 'adb': {'serial': 'test-only'}, 'capture': {'kind': 'none'}}}))
            original = run.Run
            def create(*args, **kwargs):
                instance = original(*args, **kwargs)
                instance.preflight = lambda: None
                instance.setup = lambda: None
                def scenario(name):
                    instance.results.append({'scenario': name, 'status': 'pass'})
                    raise run.Failure('Post-observation connection did not complete')
                instance.run_scenario = scenario
                def cleanup():
                    instance.state['pending_cleanup'] = False
                    return []
                instance.cleanup = cleanup
                return instance
            with patch.object(run, 'Run', side_effect=create):
                code = run.main(['run', '--config', str(config), '--output', str(root / 'result'),
                                 '--scenarios', 'positive-control,steady'])
            report = json.loads((root / 'result/report.json').read_text())
            self.assertEqual(code, 1)
            self.assertEqual(report['status'], 'fail')
            self.assertEqual(report['results'][1]['scenario'], 'positive-control-completion')
            self.assertEqual(report['results'][1]['status'], 'fail')
            self.assertEqual(report['results'][2]['status'], 'skip')
