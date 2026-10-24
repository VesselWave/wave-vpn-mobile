# SPDX-License-Identifier: GPL-3.0-or-later
import base64
import json
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run as runner
from summarize import summarize


def frame(port=53, payload=b'waveqa-20261022.example.com', src='192.0.2.1', tcp=False):
    transport = struct.pack('!HH', 12345, port)
    transport += (bytes(8) + b'\x50\x02' + bytes(6)) if tcp else struct.pack('!HH', 8 + len(payload), 0)
    ip = b'\x45\x00' + struct.pack('!H', 20 + len(transport) + len(payload)) + bytes(4) + b'\x40' + bytes([6 if tcp else 17]) + bytes(2)
    ip += socket.inet_aton(src) + socket.inet_aton('1.1.1.1')
    return bytes(12) + b'\x08\x00' + ip + transport + payload


def pcap(frames, endian='<', nano=False):
    magic = 0xa1b23c4d if nano else 0xa1b2c3d4
    result = struct.pack(endian + 'IHHIIII', magic, 2, 4, 0, 0, 65535, 1)
    for packet in frames:
        result += struct.pack(endian + 'IIII', 100, 123, len(packet), len(packet)) + packet
    return result


class PacketEvidenceTests(unittest.TestCase):
    def summary(self, data, **kwargs):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'capture.pcap'; path.write_bytes(data)
            return summarize(path, **kwargs)

    def test_bad_or_partial_capture_cannot_become_an_empty_pass(self):
        valid = pcap([frame()])
        for bad in [b'', valid[:20], valid[:30], valid[:-1], valid + b'junk']:
            with self.subTest(size=len(bad)), self.assertRaises(ValueError):
                self.summary(bad)

    def test_endianness_and_timestamp_variants_detect_same_probe(self):
        for endian in ['<', '>']:
            for nano in [False, True]:
                self.assertEqual(self.summary(pcap([frame()], endian, nano))['counts']['raw_dns4_out'], 1)

    def test_other_phone_packets_do_not_supply_a_positive_control(self):
        result = self.summary(pcap([frame(src='192.0.2.2')]), client_addresses=['192.0.2.1'])
        self.assertNotIn('raw_dns4_out', result['counts'])

    def test_mac_scope_catches_ipv6_without_a_known_privacy_address(self):
        mac = bytes.fromhex('021122334455')
        payload = b'probe-123.1-1-1-1.sslip.io'
        udp = struct.pack('!HHHH', 12345, 53, 8 + len(payload), 0) + payload
        ip6 = struct.pack('!IHBB', 6 << 28, len(udp), 17, 64)
        ip6 += socket.inet_pton(socket.AF_INET6, '2001:db8::1234')
        ip6 += socket.inet_pton(socket.AF_INET6, '2606:4700:4700::1111')
        packet = bytes(6) + mac + b'\x86\xdd' + ip6 + udp
        result = self.summary(pcap([packet]), client_addresses=['192.0.2.1'], client_mac='02:11:22:33:44:55')
        self.assertEqual(result['counts']['system_dns6_out'], 1)
        other = self.summary(pcap([packet]), client_mac='02:11:22:33:44:66')
        self.assertNotIn('system_dns6_out', other['counts'])
        self.assertNotIn('02:11:22:33:44:55', json.dumps(result))

    def test_exclusive_ap_scope_survives_client_mac_rotation(self):
        def source(mac):
            packet = frame()
            return packet[:6] + bytes.fromhex(mac) + packet[12:]
        result = self.summary(pcap([source('021122334455'), source('021122334466'),
                                    source('021122334477')]),
                              client_addresses=['192.0.2.99'], ap_mac='02:11:22:33:44:77')
        self.assertEqual(result['counts']['raw_dns4_out'], 2)

    def test_transport_port_is_configurable_and_not_called_crypto_validation(self):
        result = self.summary(pcap([frame(port=51820)]), wireguard_port=51820)
        self.assertEqual(result['counts']['wireguard_out'], 1)

    def test_privacy_report_does_not_export_payload_addresses_or_names(self):
        result = self.summary(pcap([frame(payload=b'private-host.example')]))
        text = json.dumps(result)
        self.assertNotIn('private-host', text)
        self.assertNotIn('192.0.2.1', text)
        self.assertIn('all_dns4_out', result['counts'])

    def test_tcp_dns_and_nonprobe_dot_are_not_ignored(self):
        for data in [pcap([frame(port=53, tcp=True)]), pcap([frame(port=853, tcp=True)])]:
            self.assertTrue(runner.capture_findings(self.summary(data)))
        self.assertTrue(runner.capture_findings({'counts': {'all_dot4_syn': 1}}))

    def test_background_dns_blocks_blanket_pass_without_claiming_its_app(self):
        findings = runner.capture_findings(self.summary(pcap([frame(payload=b'background.example')])) )
        self.assertTrue(any('origin_not_assumed' in finding for finding in findings))

    def test_default_and_bound_resolver_evidence_stays_separate_and_failing(self):
        result = self.summary(pcap([
            frame(payload=b'probe-123.1-1-1-1.sslip.io'),
            frame(payload=b'probe-bound-456.1-1-1-1.sslip.io')]))
        self.assertEqual(result['counts']['system_dns4_out'], 2)
        self.assertEqual(result['counts']['default_system_dns4_out'], 1)
        self.assertEqual(result['counts']['bound_system_dns4_out'], 1)
        self.assertIn('probe_escape_ipv4', runner.capture_findings(result))

    def test_shared_https_or_dot_endpoint_is_a_finding_without_probe_attribution(self):
        for port in (443, 853):
            findings = runner.capture_findings(self.summary(pcap([frame(port=port, tcp=True)])))
            self.assertIn('probe_endpoint_transport_observed_ipv4_origin_not_assumed', findings)
            self.assertNotIn('probe_escape_ipv4', findings)
        marked = runner.capture_findings(self.summary(pcap([frame()])))
        self.assertIn('probe_escape_ipv4', marked)


class RunnerSafetyTests(unittest.TestCase):
    def test_idle_ui_does_not_prove_android_tunnel_teardown(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}}, temp, runner.parser().parse_args(['run']))
            run.open_overview = lambda: ['Connect']
            run.d.wait_labels = lambda *a: ['Connect']
            run.active_vpn = lambda: True
            def check(predicate, seconds, reason):
                runner.require(predicate(), reason)
            run.wait_for = check
            with self.assertRaisesRegex(runner.Failure, 'active target VPN'):
                run.disconnect()
            run.active_vpn = lambda: False
            run.disconnect()

    def test_disconnect_scenario_establishes_a_connected_start(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}}, temp, runner.parser().parse_args(['run']))
            calls = []
            run.connect = lambda: calls.append('connect')
            run.protection = lambda enabled: calls.append(('protection', enabled))
            run.observe = lambda *a, **kw: calls.append(('observe', a[0], kw['expected']))
            run.run_scenario('disconnect')
            self.assertEqual(calls, ['connect', ('protection', True), ('observe', 'disconnect', 'blocked')])

    def test_debug_launcher_namespace_is_resolved_and_foreign_components_rejected(self):
        device = runner.Device({'adb': {'serial': 'test-only'}})
        calls = []
        def shell(*args):
            calls.append(args)
            return 'me.vwave.vpn.dev/me.vwave.vpn.MainActivity' if args[0] == 'cmd' else ''
        device.shell = shell
        with patch.object(runner.time, 'sleep'):
            device.open('me.vwave.vpn.dev')
        self.assertEqual(calls[-1][-1], 'me.vwave.vpn.dev/me.vwave.vpn.MainActivity')
        device.shell = lambda *a: 'other.package/other.package.MainActivity'
        with self.assertRaises(runner.Failure):
            device.open('me.vwave.vpn.dev')

    def test_unconfigured_vpn_policy_is_disabled_without_a_settings_entry(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}}, temp, runner.parser().parse_args(['run']))
            run.d.setting = lambda *a: 'null'
            run.vpn_settings = lambda: self.fail('An unprepared VPN has no Settings entry')
            run.protection(False)

    def test_credentials_are_stdin_not_instrumentation_or_shell_arguments(self):
        device = runner.Device({'adb': {'serial': 'test-only'}})
        calls = []
        secret = 'private value $() Unicode 漢字'
        def shell(*args, **kwargs):
            calls.append((args, kwargs))
            if args[0] == 'am':
                value = base64.b64encode(b'{"wall_ms":123}').decode()
                return 'INSTRUMENTATION_RESULT: result=' + value
            return ''
        device.shell = shell
        device.ui('fill', selector={'editable': True}, value=secret)
        self.assertTrue(any(json.loads(call[1]['data'])['value'] == secret for call in calls if call[1].get('data')))
        self.assertTrue(all(secret not in str(call[0]) for call in calls))

    def test_icon_only_controls_are_visible_to_observer_and_selector(self):
        device = runner.Device({'adb': {'serial': 'test-only'}})
        device.nodes = lambda: [{'label': '', 'description': 'Connect', 'visible': True}]
        self.assertIn('Connect', device.labels())
        calls = []
        device.ui = lambda action, **values: calls.append((action, values))
        with patch.object(runner.time, 'sleep'):
            device.tap('Connect')
        self.assertEqual(calls[0][1]['selector'], {'text': 'Connect'})

    def test_gateway_cleanup_is_armed_before_forwarding_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'gateway', 'ssh': 'qa-host', 'interface': 'eth0',
                                          'client_ipv4': '192.0.2.1', 'manage': True}}, temp, args)
            scripts = []
            run.remote = lambda script: scripts.append(script)
            run.setup_gateway()
            script = scripts[0]
            self.assertLess(script.index('systemd-run'), script.index('nft add table'))
            self.assertLess(script.index('systemd-run'), script.index('net.ipv4.ip_forward=1'))
            self.assertIn('wave-vpn-qa-gateway/owner', script)
            subprocess.run(['sh', '-n'], input=script, text=True, check=True, capture_output=True)
            # Expansion must happen when cleanup runs, not while the heredoc is written.
            self.assertIn('\\$(cat /run/lock/wave-vpn-qa-gateway/owner', script)
            self.assertNotIn('192.0.2.1', (run.output / 'private/state.json').name)

    def test_gateway_route_must_match_the_configured_capture_host(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'gateway', 'gateway_ipv4': '192.0.2.2',
                                          'phone_interface': 'wlan0', 'manage': False}}, temp, args)
            for route, expected in [('default via 192.0.2.2 dev wlan0', True),
                                    ('default via 192.0.2.3 dev wlan0', False),
                                    ('default via 192.0.2.2 dev rmnet0', False)]:
                with self.subTest(route=route):
                    run.d.shell = lambda *a, **kw: route
                    def verify(predicate, seconds, reason):
                        self.assertEqual(predicate(), expected)
                        if not expected:
                            raise runner.Failure(reason)
                    run.wait_for = verify
                    if expected:
                        run.setup_gateway()
                    else:
                        with self.assertRaises(runner.Failure):
                            run.setup_gateway()

    def test_wifi_restore_does_not_collapse_already_visible_static_ip_options(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'gateway', 'wifi_ssid': 'qa-only'}},
                             temp, runner.parser().parse_args(['run']))
            for expanded in (False, True):
                with self.subTest(expanded=expanded):
                    labels = ['qa-only']
                    taps = []
                    def tap(label):
                        taps.append(label)
                        if label == 'qa-only':
                            labels[:] = ['Advanced options'] + (['IP settings'] if expanded else [])
                        elif label == 'Advanced options':
                            self.assertNotIn('IP settings', labels)
                            labels.append('IP settings')
                    run.d.shell = lambda *a: ''
                    run.d.labels = lambda: labels[:]
                    run.d.tap = tap
                    run.d.wait_labels = lambda predicate: self.assertTrue(predicate(labels))
                    run.wifi_form()
                    self.assertEqual('Advanced options' in taps, not expanded)

    def test_early_success_cannot_mask_failed_post_crash_recovery(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}}, temp, args)
            run.probe_start = lambda phase: None
            clocks = iter([1000, 2000, 2000, 21000])
            run.d.ui = lambda *a, **kw: {'wall_ms': next(clocks)}
            phase = run.run_id + '-crash'
            raw = ''.join('2100\t' + phase + '\t' + k + '\tOK old\n' for k in ['ipv4','dns','direct4','doh'])
            run.d.shell = lambda *a, **kw: raw
            with patch.object(runner.time, 'monotonic', side_effect=[0, 100, 100]):
                result, _ = run.observe('crash', seconds=20, action=lambda: {})
            self.assertEqual(result['status'], 'fail')
            self.assertIn('did not recover', result['error'])

    def test_dhcp_restore_is_resumable_but_still_requires_capture_route_removal(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'gateway', 'gateway_ipv4': '192.0.2.2'}},
                             temp, runner.parser().parse_args(['run']))
            run.wifi_form = lambda: None
            for mode, old_route in [('Static', False), ('DHCP', False), ('DHCP', True)]:
                with self.subTest(mode=mode, old_route=old_route):
                    run.state['wifi_changed'] = True
                    calls = []
                    run.d.labels = lambda: [mode]
                    run.d.tap = lambda label: calls.append(label)
                    run.d.ui = lambda action: calls.append(action)
                    run.d.shell = lambda *a: 'default via 192.0.2.2 dev wlan0' if old_route else ''
                    def verify(predicate, seconds, reason):
                        if not predicate():
                            raise runner.Failure(reason)
                    run.wait_for = verify
                    if old_route:
                        with self.assertRaises(runner.Failure):
                            run.restore_wifi()
                        self.assertTrue(run.state['wifi_changed'])
                    else:
                        run.restore_wifi()
                        self.assertNotIn('wifi_changed', run.state)
                    self.assertEqual(calls, ['Static', 'DHCP', 'Save'] if mode == 'Static' else ['back'])

    def test_failed_fresh_snapshot_never_reuses_cached_nodes(self):
        device = runner.Device({'adb': {'serial': 'test-only'}})
        device.shell = lambda *a, **kw: 'INSTRUMENTATION_RESULT: error=failed'
        with self.assertRaises(runner.Failure):
            device.nodes()

    def test_soak_detects_outage_after_early_success_and_preserves_privacy_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}, 'soak_seconds': 60}, temp, args)
            run.connect = lambda: None
            run.protection = lambda enabled: None
            for times, initial_status, expected in [([1000], 'pass', 'fail'),
                                                     ([1000, 25000, 45000, 59000], 'pass', 'pass'),
                                                     ([1000, 25000, 45000, 59000], 'fail', 'fail')]:
                with self.subTest(times=times, initial_status=initial_status):
                    result = {'status': initial_status, 'required_probes': ['dns'],
                              'window_start_unix_ms': 0, 'window_end_unix_ms': 60000}
                    rows = [[str(t), 'soak', 'dns', 'OK answer'] for t in times]
                    run.observe = lambda *a: (result, rows)
                    run.run_scenario('traffic-soak')
                    self.assertEqual(result['status'], expected)
                    self.assertIn('dns', result['longest_success_gap_seconds'])

    def test_probe_start_waits_for_delayed_process_instead_of_assuming_half_a_second(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'}, 'capture': {'kind': 'none'}},
                             temp, runner.parser().parse_args(['run']))
            run.d.shell = lambda *a, **kw: ''
            with patch.object(run.d, 'pid', side_effect=['', '', '123']), patch.object(runner.time, 'sleep'):
                run.probe_start('test-phase')

    def test_soak_cannot_outlive_the_gateway_cleanup_lease(self):
        with tempfile.TemporaryDirectory() as temp:
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'gateway', 'manage': True}, 'soak_seconds': 3600},
                             temp, runner.parser().parse_args(['run']))
            run.state['gateway_cleanup_deadline_unix_ms'] = 3600000
            run.connect = lambda: self.fail('Expired capture infrastructure must not start a soak')
            with patch.object(runner.time, 'time', return_value=1800), self.assertRaises(runner.Failure):
                run.run_scenario('traffic-soak')

    def test_probe_window_excludes_previous_phase_and_inflight_grace(self):
        rows = '100\told\tipv4\tOK old\n200\tcurrent\tipv4\tOK inflight\n900\tcurrent\tipv4\tFAIL timeout\n'
        summary, selected, late = runner.probe_summary(rows, 'current', 150, 1000, after_ms=500)
        self.assertEqual(len(selected), 2)
        self.assertEqual(summary['ipv4']['success'], 1)
        self.assertEqual(late['ipv4'], 0)

    def test_source_tree_is_never_an_evidence_destination(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / '.git').mkdir()
            with self.assertRaises(runner.Failure):
                runner.private_directory(root / 'output')

    def test_adb_disconnection_leaves_resumable_cleanup_state(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}}, temp, args)
            run.state['settings'] = {'secure/always_on_vpn_app': 'null'}
            run.d.wait = lambda *a: (_ for _ in ()).throw(runner.Failure('offline'))
            self.assertTrue(run.cleanup())
            persisted = json.loads(run.state_path.read_text())
            self.assertTrue(persisted['pending_cleanup'])
            self.assertEqual(run.state_path.stat().st_mode & 0o777, 0o600)

    def test_completed_restore_is_idempotent_and_does_not_touch_device(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'test-only'},
                              'capture': {'kind': 'none'}}, temp, args)
            run.state['pending_cleanup'] = False
            run.d.wait = lambda *a: self.fail('A completed restore must not touch the device')
            self.assertEqual(run.cleanup(), [])

    def test_recovery_failure_report_contains_no_private_config(self):
        with tempfile.TemporaryDirectory() as temp:
            args = runner.parser().parse_args(['run'])
            run = runner.Run({'package': 'test.package', 'adb': {'serial': 'PRIVATE_SERIAL'},
                              'credentials_file': '/PRIVATE_ACCOUNT.json', 'capture': {'kind': 'none'}}, temp, args)
            run.results = [{'scenario': 'crash', 'status': 'fail', 'error': 'VPN did not recover'}]
            run.write_report()
            text = (Path(temp) / 'report.json').read_text()
            self.assertNotIn('PRIVATE_', text)
            self.assertEqual(json.loads(text)['status'], 'fail')


if __name__ == '__main__':
    unittest.main()
