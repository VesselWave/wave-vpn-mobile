# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location('vpn_compare', HERE / 'compare.py')
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class ComparisonTests(unittest.TestCase):
    def make_run(self, directory):
        return comparison.InstalledVpnRun({
            'package': 'test.vpn', 'adb': {'serial': 'test-only'},
            'comparison_ui': {'disconnect': [{'text': 'Disconnect'}]},
            'capture': {'kind': 'none'}}, directory,
            comparison.run.parser().parse_args(['run']))

    def test_installed_split_apks_are_archived_without_install_or_uninstall(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.make_run(directory)
            instance.d.installed = lambda _: True
            instance.d.shell = lambda *args: 'package:/base.apk\npackage:/split.apk'
            calls = []
            def call(*args):
                calls.append(args)
                Path(args[-1]).write_bytes(args[1].encode())
            instance.d.call = call
            import hashlib
            instance.state['artifact'] = {'sha256': hashlib.sha256(b'/base.apk').hexdigest()}
            instance.install('test.vpn', 'unused.apk')
            self.assertEqual(len(instance.state['installed_apks']), 2)
            self.assertTrue(all(args[0] == 'pull' for args in calls))
            self.assertFalse(instance.state['target_created'])

    def test_persistent_blocking_tunnel_is_not_reported_as_teardown(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.make_run(directory)
            instance.d.open = lambda _: None
            instance.steps = lambda _: None
            instance.active_vpn = lambda: True
            instance.wait_for = lambda predicate, seconds, reason: comparison.run.require(predicate(), reason)
            with self.assertRaisesRegex(comparison.run.Failure, 'teardown was not exercised'):
                instance.disconnect()

    def test_active_blocking_vpn_still_needs_a_connection_action(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.make_run(directory)
            instance.config['comparison_ui']['connected'] = {'text': 'Connected'}
            instance.login = lambda: None
            instance.active_vpn = lambda: True
            nodes = [{'visible': True, 'label': 'Blocked'}]
            instance.d.nodes = lambda: nodes
            calls = []
            def steps(name):
                calls.append(name)
                nodes[0]['label'] = 'Connected'
            instance.steps = steps
            instance.connect()
            self.assertEqual(calls, ['connect'])

    def test_invisible_or_unrelated_connected_label_cannot_pass(self):
        self.assertFalse(comparison.InstalledVpnRun.matches(
            {'visible': False, 'label': 'Connected'}, {'prefix': 'Connected'}))
        self.assertFalse(comparison.InstalledVpnRun.matches(
            {'visible': True, 'label': 'Not connected'}, {'prefix': 'Connected'}))

    def test_selected_overview_tab_does_not_require_clickable_ancestor(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.make_run(directory)
            instance.config['comparison_ui']['overview'] = [{'text': 'Quick Connect'}]
            instance.d.nodes = lambda: [{'visible': True, 'selected': True, 'description': 'Quick Connect'}]
            instance.d.ui = lambda *a, **kw: self.fail('Selected tab should not be clicked')
            instance.steps('overview')

    def test_force_stop_does_not_accept_an_already_dead_tunnel_as_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.make_run(directory)
            instance.connect = lambda: (_ for _ in ()).throw(comparison.run.Failure('No connected start'))
            instance.observe = lambda *a, **kw: self.fail('No observation before a connected start')
            with self.assertRaisesRegex(comparison.run.Failure, 'No connected start'):
                instance.run_scenario('force-stop')

    def test_reconnect_establishes_an_inactive_start_before_observing_connect(self):
        with tempfile.TemporaryDirectory() as directory:
            instance = self.make_run(directory)
            calls = []
            instance.connect = lambda: calls.append('connect')
            instance.protection = lambda enabled: calls.append(('protection', enabled))
            instance.disconnect = lambda: calls.append('disconnect')
            instance.observe = lambda *a, **kw: calls.append('observe')
            instance.run_scenario('reconnect')
            self.assertEqual(calls, ['connect', ('protection', True), 'disconnect', 'observe'])


if __name__ == '__main__':
    unittest.main()
