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
