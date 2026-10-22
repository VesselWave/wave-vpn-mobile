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


class RunnerSafetyTests(unittest.TestCase):
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

    def test_failed_fresh_snapshot_never_reuses_cached_nodes(self):
        device = runner.Device({'adb': {'serial': 'test-only'}})
        device.shell = lambda *a, **kw: 'INSTRUMENTATION_RESULT: error=failed'
        with self.assertRaises(runner.Failure):
            device.nodes()

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
