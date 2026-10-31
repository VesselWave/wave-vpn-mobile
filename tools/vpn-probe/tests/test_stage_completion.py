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
