#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reproduce Wave's device, recovery and release-capture tests. Python stdlib only."""
import argparse
import base64
import collections
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import signal
import socket
import stat
import subprocess
import sys
import time
import uuid

from summarize import packets, summarize

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PROBE = 'me.vwave.vpnprobe'
DRIVER = PROBE + '.driver'
KINDS = ('ipv4', 'ipv6', 'dns', 'direct4', 'doh', 'dot4', 'dot6', 'bound-dns', 'bound-tcp4', 'bound-tcp6')
SCENARIOS = ('positive-control', 'steady', 'crash', 'disconnect', 'reconnect', 'server-switch',
             'peer-outage', 'kill-stress', 'private-dns', 'split-tunnel', 'screen-off', 'doze',
             'airplane', 'handoff', 'force-stop', 'reboot', 'traffic-soak')
SAVED_SETTINGS = [('secure', 'always_on_vpn_app'), ('secure', 'always_on_vpn_lockdown'),
                  ('global', 'private_dns_mode'), ('global', 'private_dns_specifier'),
                  ('global', 'mobile_data'), ('global', 'airplane_mode_on')]


class Failure(Exception):
    """Messages are fixed diagnostics, never command output or editable values."""


class Skipped(Failure):
    pass


def require(condition, reason):
    if not condition:
        raise Failure(reason)


def save(path, value):
    path = Path(path)
    temp = path.with_suffix(path.suffix + '.new')
    with open(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')
    temp.replace(path)


def private_directory(path):
    path = Path(path).expanduser().resolve()
    require(not any((p / '.git').exists() for p in (path, *path.parents)),
            'Output must be outside source repositories; it contains private data')
    path.mkdir(parents=True, exist_ok=True)
    path.chmod(0o700)
    return path


def command(argv, *, data=None, timeout=45, check=True):
    try:
        result = subprocess.run([str(x) for x in argv], input=data, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise Failure('Command unavailable or timed out; no successful result recorded') from None
    if check and result.returncode:
        raise Failure('Command failed; no successful result recorded')
    return result


def expand(value):
    if isinstance(value, str):
        return os.path.expanduser(os.path.expandvars(value))
    if isinstance(value, dict):
        return {k: expand(v) for k, v in value.items()}
    if isinstance(value, list):
        return [expand(v) for v in value]
    return value


def load_config(path, profile):
    value = expand(json.loads(Path(path).read_text()))
    require(value.get('version') == 1, 'Configuration version must be 1')
    profiles = value.get('profiles', {'default': {}})
    require(profile in profiles, 'Unknown configuration profile')
    config = dict(value.get('common', {}))
    config.update(profiles[profile])
    require('serial' in config.get('adb', {}), 'An explicit ADB serial is required')
    require(re.fullmatch(r'[A-Za-z0-9_.]+', config.get('package', '')), 'Invalid package')
    require(config['package'] not in (PROBE, DRIVER), 'Target cannot be a QA helper')
    capture = config.get('capture', {'kind': 'none'})
    require(capture.get('kind') in ('none', 'emulator', 'gateway'), 'Unknown capture kind')
    if capture['kind'] != 'none':
        require(re.fullmatch(r'[A-Za-z0-9_.-]{1,15}', capture.get('interface', '')), 'Invalid capture interface')
    if capture['kind'] == 'gateway':
        require(re.fullmatch(r'[A-Za-z0-9_@.:-]+', capture.get('ssh', '')), 'Invalid SSH target')
        require(not capture['ssh'].startswith('-'), 'Invalid SSH target')
        ipaddress.IPv4Address(capture['client_ipv4'])
        for address in capture.get('client_ipv6', []):
            ipaddress.IPv6Address(address)
    port = config.get('wireguard_port', 1637)
    require(isinstance(port, int) and 0 < port < 65536, 'Invalid WireGuard port')
    return config


class Device:
    def __init__(self, config):
        self.config = config
        adb = config['adb']
        self.argv = [adb.get('path', 'adb'), '-H', adb.get('host', '127.0.0.1'),
                     '-P', str(adb.get('port', 5037)), '-s', adb['serial']]

    def call(self, *args, data=None, timeout=45, check=True):
        return command(self.argv + list(args), data=data, timeout=timeout, check=check)

    def shell(self, *args, **kwargs):
        return self.call('shell', '-T', shlex.join(str(x) for x in args), **kwargs).stdout.decode().strip()

    def setting(self, namespace, key):
        return self.shell('settings', 'get', namespace, key)

    def put(self, namespace, key, value):
        if value == 'null':
            self.shell('settings', 'delete', namespace, key)
        else:
            self.shell('settings', 'put', namespace, key, str(value))

    def pid(self, package):
        return self.shell('pidof', package, check=False).strip()

    def installed(self, package):
        return bool(self.shell('pm', 'path', '--user', '0', package, check=False).startswith('package:'))

    def wait(self, seconds=90):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            result = self.call('get-state', check=False, timeout=5)
            if result.returncode == 0 and result.stdout.strip() == b'device':
                return
            time.sleep(1)
        raise Failure('ADB device did not return before the deadline')

    def ui(self, action, **values):
        # A private command file avoids usernames/passwords in am-instrument arguments.
        data = json.dumps(dict(action=action, **values)).encode()
        script = 'umask 077; mkdir -p files; cat > files/command.json'
        self.shell('run-as', DRIVER, 'sh', '-c', script, data=data)
        result = self.shell('am', 'instrument', '--user', '0', '-w', '-r', DRIVER + '/.Driver', timeout=30)
        match = re.search(r'^INSTRUMENTATION_RESULT: result=(\S+)', result, re.M)
        require(match is not None, 'Fresh UI command failed (stale snapshots are never reused)')
        try:
            return json.loads(base64.b64decode(match.group(1), validate=True))
        except (ValueError, json.JSONDecodeError):
            raise Failure('Invalid driver response') from None

    def nodes(self):
        return self.ui('snapshot')['nodes']

    def tap(self, label=None, **selector):
        if label is not None:
            selector['text'] = label
        self.ui('tap', selector=selector)
        time.sleep(0.4)

    def fill(self, value, **selector):
        self.ui('fill', selector=selector, value=value)

    def open(self, package, activity=None):
        if activity:
            component = package + '/' + activity
        else:
            # Debug application IDs differ from their Kotlin activity namespace.
            # Resolve the installed launcher instead of guessing a class name.
            resolved = self.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                                  '-a', 'android.intent.action.MAIN',
                                  '-c', 'android.intent.category.LAUNCHER', package)
            matches = [line.strip() for line in resolved.splitlines()
                       if re.fullmatch(re.escape(package) + r'/[A-Za-z0-9_.$]+', line.strip())]
            require(len(matches) == 1, 'Could not uniquely resolve the target app launcher')
            component = matches[0]
        self.shell('am', 'start', '--user', '0', '-f', '0x10008000', '-n', component)
        time.sleep(1)

    def labels(self):
        return [value for n in self.nodes() if n['visible'] for value in (n['label'], n['description']) if value]

    def wait_labels(self, predicate, seconds=25):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            try:
                labels = self.labels()
                if predicate(labels):
                    return labels
            except Failure:
                pass
            time.sleep(0.5)
        raise Failure('Expected fresh UI state did not appear before the deadline')


class Capture:
    def __init__(self, run, name, seconds):
        self.run = run
        self.path = run.output / 'private' / (name + '.pcap')
        self.kind = run.capture['kind']
        self.remote = '/data/local/tmp/wave-qa-' + run.run_id + '-' + name + '.pcap'
        self.process = self.file = None
        self.seconds = seconds

    def start(self):
        if self.kind == 'none':
            return self
        self.file = self.path.open('wb')
        if self.kind == 'emulator':
            argv = self.run.d.argv + ['shell', '-T', shlex.join([self.run.capture_binary,
                    self.run.capture['interface'], self.remote, str(self.seconds)])]
            self.run.state.setdefault('device_capture_files', []).append(self.remote)
            self.run.persist()
        else:
            addresses = self.run.client_addresses()
            expression = ' or '.join('host ' + address for address in addresses)
            argv = self.run.ssh_argv() + [shlex.join(['sudo', '-n', 'timeout', str(self.seconds),
                    'tcpdump', '-n', '-i', self.run.capture['interface'], '-U', '-s', '0', '-w', '-', expression])]
        self.process = subprocess.Popen(argv, stdout=self.file, stderr=subprocess.PIPE)
        self.run.active_capture = self
        time.sleep(0.7)
        require(self.process.poll() is None, 'Capture process exited before the observation')
        return self

    def finish(self):
        if self.kind == 'none':
            return None
        try:
            try:
                _, error = self.process.communicate(timeout=self.seconds + 10)
            except subprocess.TimeoutExpired:
                raise Failure('Capture did not finish before the deadline') from None
            self.file.close()
            require(self.process.returncode in ((0,) if self.kind == 'emulator' else (0, 124)),
                    'Capture failed; packet counts are not valid')
            if self.kind == 'emulator':
                self.run.d.call('pull', self.remote, str(self.path))
                self.run.d.shell('rm', '-f', self.remote)
            return summarize(self.path, self.run.config.get('wireguard_port', 1637), self.run.client_addresses())
        finally:
            self.run.active_capture = None
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill(); self.process.wait(timeout=5)
            if not self.file.closed:
                self.file.close()

    def abort(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill(); self.process.wait(timeout=5)
        if self.file and not self.file.closed:
            self.file.close()


def probe_summary(raw, phase, start_ms, end_ms, after_ms=None):
    rows = []
    for line in raw.splitlines():
        fields = line.split('\t', 3)
        if len(fields) != 4:
            raise Failure('Truncated probe results')
        if fields[1] == phase and start_ms <= int(fields[0]) <= end_ms:
            rows.append(fields)
    counts = collections.Counter((r[2], r[3].split()[0]) for r in rows)
    result = {kind: {'success': counts[kind, 'OK'], 'failure': counts[kind, 'FAIL']} for kind in KINDS}
    late = [r for r in rows if after_ms is not None and int(r[0]) >= after_ms]
    late_counts = collections.Counter((r[2], r[3].split()[0]) for r in late)
    return result, rows, {kind: late_counts[kind, 'OK'] for kind in KINDS}


def capture_findings(result):
    if not result:
        return []
    counts = result['counts']
    findings = []
    for family in ('4', '6'):
        if any(counts.get(key, 0) for key in ('raw_dns' + family + '_out', 'system_dns' + family + '_out')):
            findings.append('probe_escape_ipv' + family)
        # These endpoints are shared with platform DNS and other applications. A SYN has
        # no query marker or UID: retain a privacy finding without assigning its origin.
        if any(counts.get(key, 0) for key in ('direct_https' + family + '_syn', 'dot' + family + '_syn')):
            findings.append('probe_endpoint_transport_observed_ipv' + family + '_origin_not_assumed')
        if counts.get('all_dns' + family + '_out', 0):
            findings.append('cleartext_dns_observed_ipv' + family + '_origin_not_assumed')
        if counts.get('all_dot' + family + '_syn', 0) > counts.get('dot' + family + '_syn', 0):
            findings.append('nonprobe_dot_transport_observed_ipv' + family + '_origin_not_assumed')
        if counts.get('all_tcp_dns' + family + '_syn', 0):
            findings.append('tcp_dns_observed_ipv' + family)
        if counts.get('unsupported_network_packets', 0):
            findings.append('unsupported_packet_forms_not_assessed')
    return sorted(set(findings))


class Run:
    def __init__(self, config, output, args, state=None):
        self.config, self.args = config, args
        self.output = private_directory(output)
        (self.output / 'private').mkdir(mode=0o700, exist_ok=True)
        self.d = Device(config)
        self.package = config['package']
        self.sdk = Path(config.get('sdk', os.environ.get('ANDROID_HOME', ''))).expanduser()
        self.capture = config.get('capture', {'kind': 'none'})
        self.run_id = state['run_id'] if state else uuid.uuid4().hex[:10]
        self.state = state or {'version': 1, 'run_id': self.run_id, 'config': config, 'pending_cleanup': True, 'keep_app': args.keep_app}
        self.capture_binary = '/data/local/tmp/wave-qa-' + self.run_id
        self.active_capture = None
        self.results = []
        self.validated_families = []
        self.wan_exits = set()
        self.tool_output = self.output / 'private' / 'tools'
        self.state_path = self.output / 'private' / 'state.json'
        self.persist()

    def persist(self):
        save(self.state_path, self.state)

    def log(self, message):
        print(message, flush=True)

    def ssh_argv(self):
        return ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', self.capture['ssh']]

    def remote(self, script):
        return command(self.ssh_argv() + ['sudo -n sh -s'], data=script.encode(), timeout=30).stdout.decode().strip()

    def client_addresses(self):
        if self.capture['kind'] == 'gateway':
            return [self.capture['client_ipv4']] + self.capture.get('client_ipv6', [])
        return []

    def artifact(self, path, verify_expected=True):
        path = Path(path).resolve()
        require(path.is_file(), 'APK does not exist')
        tools = self.sdk / 'build-tools' / self.config.get('build_tools', '37.0.0')
        output = command([tools / 'apksigner', 'verify', '--print-certs', path]).stdout.decode()
        match = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', output)
        require(match is not None, 'APK signer verification failed')
        signer = match.group(1).lower()
        expected = self.config.get('expected_signer_sha256')
        if expected and verify_expected:
            require(signer == expected.replace(':', '').lower(), 'APK signer does not match the configured identity')
        badging = command([tools / 'aapt2', 'dump', 'badging', path]).stdout.decode()
        package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        require(package is not None, 'Cannot inspect APK metadata')
        return {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'signer_sha256': signer,
                'package': package.group(1), 'version_code': int(package.group(2)), 'version_name': package.group(3),
                'debuggable': 'application-debuggable' in badging}

    def preflight(self):
        self.d.wait(10)
        require(self.d.shell('am', 'get-current-user') == '0', 'Only the owner profile is supported')
        self.emulator = self.d.shell('getprop', 'ro.kernel.qemu') == '1'
        info = {'api': int(self.d.shell('getprop', 'ro.build.version.sdk')),
                'release': self.d.shell('getprop', 'ro.build.version.release'),
                'security_patch': self.d.shell('getprop', 'ro.build.version.security_patch'),
                'page_size': int(self.d.shell('getconf', 'PAGE_SIZE')),
                'abi': self.d.shell('getprop', 'ro.product.cpu.abi'), 'emulator': self.emulator}
        require(info['api'] >= 26, 'The app and helpers require Android API 26 or later')
        if self.config.get('require_page_size'):
            require(info['page_size'] == self.config['require_page_size'], 'Device page size does not match the required matrix entry')
        self.state['device'] = info
        require(self.package != 'me.vwave.vpn' or self.config.get('expected_signer_sha256'),
                'Production package requires an explicit expected_signer_sha256')
        self.state['artifact'] = self.artifact(self.config['apk'])
        require(self.state['artifact']['package'] == self.package, 'APK package does not match target package')
        if self.capture['kind'] == 'emulator':
            require(self.emulator and self.args.allow_root, 'Emulator capture requires --allow-root and an emulator (never a physical phone)')
            ndk = self.config.get('ndk') or os.environ.get('ANDROID_NDK_HOME', '')
            require(ndk and Path(ndk).is_dir(), 'Emulator capture needs a valid Android NDK directory')
        if self.capture['kind'] == 'gateway':
            command(self.ssh_argv() + ['sudo -n true'])
            command(self.ssh_argv() + ['command -v tcpdump'])
        current = self.d.setting('secure', 'always_on_vpn_app')
        require(current in ('null', self.package), 'Another VPN owns the owner-profile Always-on policy')
        self.persist()
        return info

    def install(self, package, apk, helper=False):
        present = self.d.installed(package)
        if present and helper:
            raise Failure('A QA helper is already installed; remove it explicitly or restore its previous run first')
        if not helper:
            self.state['target_created'] = not present
            if present:
                remote = self.d.shell('pm', 'path', '--user', '0', package).splitlines()[0].split(':', 1)[1]
                backup = self.output / 'private' / 'original.apk'
                self.d.call('pull', remote, str(backup))
                old = self.artifact(backup, verify_expected=False)
                self.state['original_artifact'] = old
                require(old['signer_sha256'] == self.state['artifact']['signer_sha256'] or self.args.allow_replace,
                        'Existing app has another signer; replacement requires --allow-replace (owner app data is lost)')
                if old['signer_sha256'] != self.state['artifact']['signer_sha256']:
                    self.state['legacy_owner_uninstalled'] = True
                    self.persist()
                    self.d.shell('pm', 'uninstall', '--user', '0', package)
        self.state.setdefault('created_helpers', []).append(package) if helper else None
        self.persist()  # Recovery knows what might have been installed even if ADB drops here.
        self.d.call('install', '--no-incremental', '--user', '0', '-r', str(apk), timeout=120)

    def setup(self):
        self.state['settings'] = {namespace + '/' + key: self.d.setting(namespace, key) for namespace, key in SAVED_SETTINGS}
        original_vpn = self.d.shell('dumpsys', 'vpn_management')
        self.state['original_connected'] = self.active_vpn()
        self.state['was_root'] = self.d.shell('id', '-u') == '0'
        self.persist()
        if self.capture['kind'] == 'emulator' and not self.state['was_root']:
            self.state['root_changed'] = True; self.persist()
            self.d.call('root'); self.d.wait()
            require(self.d.shell('id', '-u') == '0', 'This emulator image does not permit root capture')
        env = dict(os.environ, ANDROID_HOME=str(self.sdk))
        if self.capture['kind'] == 'emulator':
            if self.config.get('ndk'):
                env['ANDROID_NDK_HOME'] = self.config['ndk']
        else:
            env.pop('ANDROID_NDK_HOME', None)
        env['PROBE_CAPTURE_ABI'] = self.state['device']['abi']
        with (self.output / 'private' / 'tools-build.log').open('wb') as log:
            result = subprocess.run([str(HERE / 'build.sh'), str(self.tool_output)], env=env, stdout=log, stderr=log)
        require(result.returncode == 0, 'QA helper build failed (see private/tools-build.log)')
        self.install(self.package, self.config['apk'])
        self.install(PROBE, self.tool_output / 'probe.apk', helper=True)
        self.install(DRIVER, self.tool_output / 'driver.apk', helper=True)
        self.d.ui('clock')
        if self.capture['kind'] == 'emulator':
            self.state['capture_binary'] = self.capture_binary; self.persist()
            self.d.call('push', str(self.tool_output / 'capture'), self.capture_binary)
            self.d.shell('chmod', '700', self.capture_binary)
        if self.capture['kind'] == 'gateway':
            self.setup_gateway()
        self.state['target_started'] = True; self.persist()
        self.login()

    def login(self):
        self.d.open(self.package)
        labels = self.d.labels()
        if 'Sign in' in labels:
            path = self.config.get('credentials_file')
            require(path is not None, 'Sign-in required; set credentials_file to a private JSON file')
            path = Path(path)
            require(not stat.S_IMODE(path.stat().st_mode) & 0o077, 'Credentials file must not be group/world accessible')
            values = json.loads(path.read_text())
            self.d.fill(values['username'], editable=True, ordinal=0)
            self.d.fill(values['password'], editable=True, ordinal=1)
            self.d.tap('Sign in')
            del values
            until = time.monotonic() + 60
            while time.monotonic() < until:
                labels = self.d.labels()
                if 'No thanks' in labels:
                    self.d.tap('No thanks'); continue
                if any(label.startswith('Sign-in failed.') for label in labels):
                    raise Failure('Account sign-in failed on device; check private credentials and network')
                if 'Connect' in labels or 'Disconnect' in labels:
                    break
                time.sleep(0.5)
            else:
                raise Failure('Sign-in did not reach the connection screen')
        self.dismiss_autofill()

    def dismiss_autofill(self):
        if 'No thanks' in self.d.labels():
            self.d.tap('No thanks')

    def open_overview(self):
        self.d.open(self.package)
        return self.d.wait_labels(lambda labels: any(x in labels for x in ('Connect', 'Disconnect', 'Retry')), 20)

    def active_vpn(self):
        text = self.d.shell('dumpsys', 'vpn_management')
        match = re.search(r'^  0: (\S+)(.*?)(?=^  \d+: |\Z)', text, re.M | re.S)
        return bool(match and match.group(1) == self.package and 'Active vpn type: 1' in match.group(2))

    def connect(self):
        labels = self.open_overview()
        if 'Connect' in labels:
            self.d.tap('Connect')
        elif 'Retry' in labels:
            self.d.tap('Retry')
        labels = self.d.labels()
        if 'Connection request' in labels:
            self.d.tap('OK')
        self.d.wait_labels(lambda labels: any(s.startswith('Connected') for s in labels), 45)

    def disconnect(self):
        labels = self.open_overview()
        if 'Disconnect' in labels:
            self.d.tap('Disconnect')
        self.d.wait_labels(lambda labels: 'Connect' in labels, 20)

    def vpn_settings(self):
        self.d.shell('am', 'start', '--user', '0', '-f', '0x10008000', '-a', 'android.settings.VPN_SETTINGS')
        time.sleep(1)
        # Try fresh Settings buttons; verify the app version before toggling anything.
        for ordinal in range(8):
            nodes = self.d.nodes()
            buttons = [n for n in nodes if n['visible'] and (n['description'] == 'Settings' or n['label'] == 'Settings')]
            if ordinal >= len(buttons):
                break
            try:
                self.d.tap(description='Settings', ordinal=ordinal)
            except Failure:
                self.d.tap('Settings', ordinal=ordinal)
            labels = self.d.labels()
            if self.state['artifact']['version_name'] in labels and 'Always-on VPN' in labels:
                return
            self.d.ui('back'); time.sleep(0.5)
        raise Failure('Could not identify the target app VPN settings by its installed version')

    def protection(self, enabled):
        current = self.d.setting('secure', 'always_on_vpn_app')
        blocking = self.d.setting('secure', 'always_on_vpn_lockdown')
        if (enabled and current == self.package and blocking == '1') or (not enabled and current == 'null' and blocking in ('0', 'null')):
            return
        self.vpn_settings()
        if enabled:
            if current != self.package:
                self.d.tap('Always-on VPN')
            if self.d.setting('secure', 'always_on_vpn_lockdown') != '1':
                self.d.tap('Block connections without VPN')
                labels = self.d.labels()
                if 'Turn on' in labels:
                    self.d.tap('Turn on')
            require(self.d.setting('secure', 'always_on_vpn_app') == self.package and
                    self.d.setting('secure', 'always_on_vpn_lockdown') == '1', 'Always-on/blocking was not enabled')
        else:
            if current != 'null':
                # This is the verified target app's screen, never another profile's VPN.
                require(current == self.package, 'Another VPN owns Always-on; restore that policy before testing')
                self.d.tap('Always-on VPN')
            require(self.d.setting('secure', 'always_on_vpn_lockdown') in ('0', 'null'), 'Blocking was not disabled')

    def probe_start(self, phase):
        self.d.shell('am', 'force-stop', '--user', '0', PROBE)
        # A new activity instance receives a unique phase; never reuse stale onNewIntent state.
        self.d.shell('am', 'start', '--user', '0', '-f', '0x10008000', '-n', PROBE + '/.Probe', '--es', 'phase', phase)
        self.wait_for(lambda: bool(self.d.pid(PROBE)), 10, 'Independent probe did not start')

    def observe(self, name, seconds=18, action=None, expected='working', protected=True, required_override=None):
        phase = self.run_id + '-' + name
        policy = {'always_on_target_matches': self.d.setting('secure', 'always_on_vpn_app') == self.package,
                  'blocking_enabled': self.d.setting('secure', 'always_on_vpn_lockdown') == '1'}
        self.probe_start(phase)
        start = self.d.ui('clock')['wall_ms']
        cap = Capture(self, name, seconds).start()
        detail = {}
        action_error = None
        try:
            if action:
                detail = action() or {}
            changed = self.d.ui('clock')['wall_ms']
            deadline = time.monotonic() + max(0, seconds - (self.d.ui('clock')['wall_ms'] - start) / 1000)
            last_message = time.monotonic()
            while time.monotonic() < deadline:
                time.sleep(min(1, max(0, deadline - time.monotonic())))
                if time.monotonic() - last_message >= 25:
                    self.log(name + ': still observing'); last_message = time.monotonic()
        except Failure as error:
            action_error = str(error)
        finally:
            captured = cap.finish()
        end = min(self.d.ui('clock')['wall_ms'], start + seconds * 1000)
        raw = self.d.shell('run-as', PROBE, 'cat', 'files/results.tsv')
        (self.output / 'private' / (name + '.tsv')).write_text(raw)
        probes, rows, late = probe_summary(raw, phase, start, end, changed + 6000 if not action_error else None)
        result = {'scenario': name, 'duration_seconds': round((end - start) / 1000, 1), 'probes': probes,
                  'window_start_unix_ms': start, 'window_end_unix_ms': end,
                  'vpn_policy_at_window_start': policy,
                  'capture': captured, 'expected': expected, **detail, 'status': 'pass', 'findings': []}
        if action_error:
            result.update(status='fail', error=action_error)
        if not action_error and action and changed - start > (seconds - 8) * 1000:
            result.update(status='incomplete', error='Action overran the capture window; no recovery pass claimed')
        result['privacy_assessed'] = bool(protected and captured)
        if protected and captured:
            result['findings'] = capture_findings(captured)
            require(self.validated_families, 'Protected capture has no successful positive control')
        required = required_override or self.config.get('required_probes', ['ipv4', 'dns', 'direct4', 'doh'])
        result['required_probes'] = required
        if expected == 'working' and any((late[k] if action else probes[k]['success']) == 0 for k in required):
            result.update(status='fail', error='Required application traffic did not recover')
        if expected == 'blocked' and any(late[k] for k in ('ipv4', 'direct4', 'doh')):
            result.update(status='fail', error='HTTPS requests succeeded after the disconnect grace period')
        if protected and captured:
            doh_endpoints = set()
            for row in rows:
                if row[2] == 'doh' and row[3].startswith('OK '):
                    for candidate in re.findall(r'/([0-9a-fA-F:.]+)', row[3]):
                        try:
                            doh_endpoints.add(str(ipaddress.ip_address(candidate)))
                        except ValueError:
                            pass
            clients = set(self.client_addresses())
            escaped = sum(p.get('syn', False) and p.get('dport') == 443 and p.get('dst') in doh_endpoints
                          and (not clients or p.get('src') in clients) for p in packets(cap.path))
            result['doh_endpoint_syn_outside'] = escaped
            if escaped:
                result['findings'].append('doh_endpoint_transport_observed_outside_origin_not_assumed')
        if protected:
            # Compare successful plain exit checks against the positive-control WAN exit without publishing either IP.
            unexpected = sum(r[2] == 'ipv4' and r[3].startswith('OK ') and r[3][3:].strip() in self.wan_exits for r in rows)
            result['wan_exit_observations'] = unexpected
            if unexpected:
                result['findings'].append('underlying_wan_exit_observed')
        if detail.get('stale_status_verified') is False:
            result.update(status='fail', error='Expected Not responding status was not observed in Overview')
        if detail.get('stress_complete') is False and not result['findings']:
            result.update(status='incomplete', error='Not every scheduled kill had an observed restart')
        if result['findings']:
            result['status'] = 'fail'
        self.results.append(result)
        self.write_report()
        return result, rows

    def setup_gateway(self):
        gateway = self.capture
        if gateway.get('manage', False):
            root = '/tmp/wave-vpn-qa-' + self.run_id
            table = 'wave_qa_' + self.run_id
            unit = 'wave-vpn-qa-' + self.run_id
            self.state.update(gateway_root=root, gateway_table=table, gateway_unit=unit,
                              gateway_cleanup_deadline_unix_ms=int(time.time() * 1000) + 5400 * 1000)
            self.persist()
            # A gateway-wide QA lock prevents two runs restoring each other's sysctls.
            script = '''set -eu
mkdir /run/lock/wave-vpn-qa-gateway
printf '%s' RUN_ID > /run/lock/wave-vpn-qa-gateway/owner
trap 'if [ -f ROOT/cleanup.sh ]; then sh ROOT/cleanup.sh; elif [ \"$(cat /run/lock/wave-vpn-qa-gateway/owner 2>/dev/null)\" = RUN_ID ]; then rm -f /run/lock/wave-vpn-qa-gateway/owner; rmdir /run/lock/wave-vpn-qa-gateway; fi; systemctl stop UNIT.timer 2>/dev/null || true' EXIT
umask 077
mkdir -p ROOT
forward=$(sysctl -n net.ipv4.ip_forward)
redirects=$(sysctl -n net.ipv4.conf.IFACE.send_redirects)
cat > ROOT/cleanup.sh <<EOF
#!/bin/sh
nft delete table ip TABLE 2>/dev/null || true
sysctl -q -w net.ipv4.ip_forward=$forward
sysctl -q -w net.ipv4.conf.IFACE.send_redirects=$redirects
if [ "\\$(cat /run/lock/wave-vpn-qa-gateway/owner 2>/dev/null)" = 'RUN_ID' ]; then
 rm -f /run/lock/wave-vpn-qa-gateway/owner
 rmdir /run/lock/wave-vpn-qa-gateway
fi
EOF
chmod 700 ROOT/cleanup.sh
systemd-run --unit=UNIT --on-active=90m ROOT/cleanup.sh >/dev/null
nft add table ip TABLE
nft 'add chain ip TABLE postrouting { type nat hook postrouting priority srcnat; policy accept; }'
nft add rule ip TABLE postrouting ip saddr CLIENT oifname IFACE masquerade
sysctl -q -w net.ipv4.ip_forward=1
sysctl -q -w net.ipv4.conf.IFACE.send_redirects=0
trap - EXIT
'''
            for key, value in {'RUN_ID': self.run_id, 'ROOT': root, 'TABLE': table, 'UNIT': unit,
                               'CLIENT': gateway['client_ipv4'], 'IFACE': gateway['interface']}.items():
                script = script.replace(key, value)
            self.remote(script)
        if gateway.get('configure_wifi', False):
            require(gateway.get('manage'), 'Automatic Wi-Fi routing requires a managed capture gateway')
            ipaddress.IPv4Address(gateway['gateway_ipv4'])
            self.wifi_form()
            require('DHCP' in self.d.labels(), 'Automatic Wi-Fi routing supports an original DHCP configuration only')
            self.d.tap('DHCP'); self.d.tap('Static')
            for name, value in {'ipaddress': gateway['client_ipv4'], 'gateway': gateway['gateway_ipv4'],
                                'network_prefix_length': str(gateway.get('prefix', 24)),
                                'dns1': '1.1.1.1', 'dns2': '1.0.0.1'}.items():
                self.fill_id('com.android.settings:id/' + name, value)
            self.state['wifi_changed'] = True; self.persist()
            self.d.tap('Save')
        expected = gateway.get('gateway_ipv4')
        if expected:
            self.wait_for(lambda: ('default via ' + expected + ' dev ' + gateway.get('phone_interface', 'wlan0'))
                          in self.d.shell('ip', 'route', 'show', 'table', 'all'), 25,
                          'Phone is not routed through the configured capture gateway')

    def wifi_form(self):
        status = self.d.shell('cmd', 'wifi', 'status')
        match = re.search(r'SSID: "([^"]+)"', status)
        ssid = self.capture.get('wifi_ssid') or (match.group(1) if match else None)
        require(ssid is not None, 'Cannot identify current Wi-Fi network; set capture.wifi_ssid privately')
        self.d.shell('am', 'start', '--user', '0', '-f', '0x10008000', '-a', 'android.settings.WIFI_SETTINGS')
        self.d.wait_labels(lambda labels: ssid in labels)
        self.d.tap(ssid)
        self.d.wait_labels(lambda labels: 'Modify' in labels or 'Advanced options' in labels)
        if 'Modify' in self.d.labels():
            self.d.tap('Modify')
        self.d.wait_labels(lambda labels: 'Advanced options' in labels or 'IP settings' in labels)
        if 'IP settings' not in self.d.labels():
            self.d.tap('Advanced options')
        self.d.wait_labels(lambda labels: 'IP settings' in labels)

    def restore_wifi(self):
        self.wifi_form()
        labels = self.d.labels()
        if 'Static' in labels:
            self.d.tap('Static'); self.d.tap('DHCP'); self.d.tap('Save')
        else:
            require('DHCP' in labels, 'Cannot verify the original DHCP configuration')
            self.d.ui('back')  # A prior restore may have saved DHCP before route convergence timed out.
        self.wait_for(lambda: 'default via ' + self.capture['gateway_ipv4'] + ' dev' not in
                      self.d.shell('ip', 'route', 'show', 'table', 'all'), 60,
                      'Wi-Fi DHCP restoration not verified')
        self.state.pop('wifi_changed', None)

    def fill_id(self, identity, value):
        for attempt in range(5):
            try:
                self.d.fill(value, id=identity)
                return
            except Failure:
                self.d.ui('scroll', selector={'scrollable': True})
        raise Failure('Required settings field could not be reached')

    @staticmethod
    def wait_for(predicate, seconds, reason):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if predicate():
                return
            time.sleep(0.5)
        raise Failure(reason)

    def positive_control(self):
        require(self.capture['kind'] != 'none', 'Positive control needs packet capture')
        original_mode = self.d.setting('global', 'private_dns_mode')
        try:
            self.d.put('global', 'private_dns_mode', 'off')
            self.protection(False); self.disconnect()
            result, rows = self.observe('positive-control', 18, expected='working', protected=False,
                                        required_override=['ipv4', 'dns', 'direct4', 'doh'])
        finally:
            self.d.put('global', 'private_dns_mode', original_mode)
        counts = result['capture']['counts']
        for family in ('4', '6'):
            if all(counts.get(key, 0) > 0 for key in ('raw_dns' + family + '_out', 'dot' + family + '_syn',
                                                      'direct_https' + family + '_syn') if family == '4' or not key.startswith('direct_https')):
                if family == '4' or result['probes']['ipv6']['success'] > 0:
                    self.validated_families.append('ipv' + family)
        require('ipv4' in self.validated_families, 'IPv4 positive control did not detect all expected packet types')
        require(counts.get('system_dns4_out', 0) > 0, 'Positive control could not see fresh system probe DNS')
        self.wan_exits = {r[3][3:].strip() for r in rows if r[2] == 'ipv4' and r[3].startswith('OK ')}
        require(self.wan_exits, 'Positive control did not establish an underlying IPv4 exit')
        self.state['validated_families'] = self.validated_families
        self.state['wan_exits'] = sorted(self.wan_exits)
        self.persist()
        self.connect(); self.protection(True)

    def crash_action(self):
        old = self.d.pid(self.package)
        require(old, 'VPN process was absent before process death')
        start = time.monotonic()
        if self.d.shell('id', '-u') == '0':
            self.d.shell('kill', '-9', old); method = 'root SIGKILL'
        elif self.state['artifact']['debuggable']:
            self.d.shell('run-as', self.package, 'kill', '-9', old); method = 'same-UID SIGKILL'
        else:
            self.d.shell('am', 'crash', '--user', '0', self.package); method = 'system-induced Java crash'
        self.wait_for(lambda: bool(self.d.pid(self.package)) and self.d.pid(self.package) != old,
                      self.config.get('restart_timeout', 30), 'VPN process did not restart without opening Wave')
        return {'death_method': method, 'restart_seconds': round(time.monotonic() - start, 3),
                'wave_opened_to_recover': False}

    def choose_server(self, server):
        self.open_overview(); self.d.tap('Servers')
        self.d.wait_labels(lambda labels: 'Refresh server list' in labels)
        self.d.fill(server, editable=True)
        self.d.wait_labels(lambda labels: server in labels)
        self.d.tap(server)
        self.d.open(self.package)
        self.d.wait_labels(lambda labels: server in labels and any(s.startswith('Connected') for s in labels), 40)
        return {'selection_verified_in_connected_overview': True}

    def firewall(self, enable, peer=None):
        chain = 'WQA_' + self.run_id
        if self.capture['kind'] == 'emulator':
            if enable:
                listing = self.d.shell('pm', 'list', 'packages', '--user', '0', '-U', self.package)
                match = re.search(r'package:' + re.escape(self.package) + r' uid:(\d+)', listing)
                require(match is not None, 'Cannot identify target UID for scoped peer outage')
                self.state['peer_chain'] = chain; self.state['peer_uid'] = match.group(1); self.persist()
                self.d.shell('iptables', '-N', chain)
                self.d.shell('iptables', '-A', chain, '-j', 'DROP')
                self.d.shell('iptables', '-I', 'OUTPUT', '1', '-m', 'owner', '--uid-owner', match.group(1),
                             '-p', 'udp', '--dport', str(self.config.get('wireguard_port', 1637)), '-j', chain)
                self.state['peer_uid'] = match.group(1); self.persist()
            else:
                uid = self.state.get('peer_uid')
                if uid:
                    self.d.shell('iptables', '-D', 'OUTPUT', '-m', 'owner', '--uid-owner', uid, '-p', 'udp',
                                 '--dport', str(self.config.get('wireguard_port', 1637)), '-j', chain, check=False)
                self.d.shell('iptables', '-F', chain, check=False); self.d.shell('iptables', '-X', chain, check=False)
                self.state.pop('peer_chain', None); self.persist()
        elif self.capture['kind'] == 'gateway' and self.capture.get('manage'):
            table = self.state['gateway_table']
            if enable:
                ipaddress.IPv4Address(peer)
                self.state['gateway_peer_block'] = True; self.persist()
                self.remote('nft "add chain ip ' + table + ' forward { type filter hook forward priority -10; policy accept; }"\n' +
                            'nft add rule ip ' + table + ' forward ip saddr ' + self.capture['client_ipv4'] +
                            ' ip daddr ' + peer + ' udp dport ' + str(self.config.get('wireguard_port', 1637)) + ' counter drop\n')
            else:
                self.remote('nft flush chain ip ' + table + ' forward\n')
                self.state.pop('gateway_peer_block', None); self.persist()
        else:
            raise Skipped('Peer outage requires a rooted emulator or a managed capture gateway')

    def dropped(self):
        if self.capture['kind'] == 'emulator':
            text = self.d.shell('iptables', '-nvxL', 'WQA_' + self.run_id)
            return sum(int(m) for m in re.findall(r'^\s*(\d+)\s+\d+\s+DROP', text, re.M))
        text = self.remote('nft list chain ip ' + self.state['gateway_table'] + ' forward\n')
        return sum(int(m) for m in re.findall(r'counter packets (\d+)', text))

    def peer_outage(self):
        require(self.capture['kind'] != 'none', 'Peer outage needs capture to identify/verify the transport')
        peer = None
        if self.capture['kind'] == 'gateway':
            cap = Capture(self, 'peer-identification', 5).start(); cap.finish()
            found = {p['dst'] for p in packets(cap.path) if p.get('src') in self.client_addresses()
                     and p.get('proto') == 17 and p.get('dport') == self.config.get('wireguard_port', 1637)}
            require(len(found) == 1, 'Capture did not identify exactly one peer; other-profile traffic cannot be assumed')
            peer = found.pop()
        seconds = int(self.config.get('peer_outage_seconds', 200))
        require(200 <= seconds <= 600 and seconds % 10 == 0, 'Peer outage must be 200–600 seconds in multiples of ten')
        def action():
            self.firewall(True, peer)
            try:
                self.wait_for(lambda: self.dropped() > 0, 10, 'Peer drop rule did not match actual packets')
                for _ in range(seconds // 10):
                    time.sleep(10)
                    if _ % 3 == 0:
                        self.log('peer-outage: peer remains blocked')
                dropped = self.dropped()
                self.d.open(self.package)
                labels = self.d.labels()
                stalled = 'Not responding' in labels
                return {'peer_block_seconds': seconds, 'dropped_packets': dropped,
                        'stale_status_verified': stalled}
            finally:
                self.firewall(False)
        return self.observe('peer-outage', seconds + 60, action)

    def dns_counters(self, enable):
        chain = 'WQDNS_' + self.run_id
        if enable:
            require(self.capture['kind'] == 'emulator', 'Per-UID attribution requires rooted emulator capture')
            output = self.d.shell('pm', 'list', 'packages', '--user', '0', '-U')
            uids = sorted(set(map(int, re.findall(r'uid:(\d+)', output))) | {0, 1000, 1051, 1073})
            self.state['dns_chain'] = chain; self.persist()
            commands = ['iptables -t mangle -N ' + chain]
            commands += ['iptables -t mangle -A ' + chain + ' -m owner --uid-owner ' + str(uid) + ' -j RETURN' for uid in uids]
            commands += ['iptables -t mangle -A ' + chain + ' -j RETURN',
                         'iptables -t mangle -I OUTPUT 1 -o ' + self.capture['interface'] + ' -p udp --dport 53 -j ' + chain]
            self.d.shell('sh', '-c', 'set -e\n' + '\n'.join(commands))
        else:
            self.d.shell('iptables', '-t', 'mangle', '-D', 'OUTPUT', '-o', self.capture['interface'], '-p', 'udp', '--dport', '53', '-j', chain, check=False)
            self.d.shell('iptables', '-t', 'mangle', '-F', chain, check=False)
            self.d.shell('iptables', '-t', 'mangle', '-X', chain, check=False)
            self.state.pop('dns_chain', None); self.persist()

    def kill_stress(self):
        if self.capture['kind'] != 'emulator':
            raise Skipped('The DNS-attribution stress case requires a rooted emulator')
        count = int(self.config.get('kill_count', 8)); interval = float(self.config.get('kill_interval', 7))
        require(1 <= count <= 20 and 5 <= interval <= 120, 'Kill stress limits are 1–20 kills, 5–120 second intervals')
        self.dns_counters(True)
        def action():
            events = []
            for number in range(count):
                pid = self.d.pid(self.package)
                if not pid:
                    events.append({'attempt': number + 1, 'status': 'no_process_at_scheduled_kill'})
                    time.sleep(interval)
                    continue
                start = time.monotonic(); self.d.shell('kill', '-9', pid)
                while time.monotonic() - start < interval:
                    new = self.d.pid(self.package)
                    if new and new != pid:
                        events.append({'attempt': number + 1, 'restart_seconds': round(time.monotonic() - start, 3)})
                        break
                    time.sleep(0.25)
                else:
                    events.append({'attempt': number + 1, 'status': 'restart_not_seen_before_next_tick'})
                time.sleep(max(0, interval - (time.monotonic() - start)))
                self.log('kill-stress: attempt ' + str(number + 1) + ' recorded')
            text = self.d.shell('iptables', '-t', 'mangle', '-nvxL', 'WQDNS_' + self.run_id)
            (self.output / 'private' / 'dns-uid-counters.txt').write_text(text)
            root = sum(int(p) for p in re.findall(r'^\s*(\d+)\s+\d+.*owner UID match 0\s*$', text, re.M))
            return {'planned_kill_attempts': count, 'kill_events': events, 'stress_complete': all('restart_seconds' in event for event in events), 'root_dns_socket_packets': root,
                    'attribution_scope': 'socket UID only; originating apps are not inferred'}
        try:
            return self.observe('kill-stress', int(count * interval) + 75, action)
        finally:
            self.dns_counters(False)

    def private_dns(self):
        original = {key: self.d.setting('global', key) for key in ('private_dns_mode', 'private_dns_specifier')}
        try:
            for name, mode in [('off', 'off'), ('automatic', 'opportunistic'), ('strict', 'hostname')]:
                def action(mode=mode):
                    if mode == 'hostname':
                        self.d.put('global', 'private_dns_specifier', 'one.one.one.one')
                    self.d.put('global', 'private_dns_mode', mode)
                    return {'private_dns_mode': mode}
                self.observe('private-dns-' + name, 30, action)
        finally:
            for key, value in original.items():
                self.d.put('global', key, value)

    def manual(self, message):
        if not self.args.interactive:
            raise Skipped('This stage requires --interactive and a physical operator')
        self.log(message)
        require(input('Press Enter after completing that step (or type stop): ').strip().lower() != 'stop', 'Operator stopped the run')

    def reboot(self):
        if not self.emulator and not self.args.interactive:
            raise Skipped('Physical reboot requires --interactive for first unlock')
        self.d.shell('am', 'force-stop', '--user', '0', PROBE)
        self.d.call('reboot')
        if not self.emulator:
            self.manual('Unlock the phone after reboot WITHOUT opening Wave or tapping Connect.')
        self.d.wait(150)
        self.wait_for(lambda: self.d.shell('getprop', 'sys.boot_completed') == '1', 90, 'Boot did not finish')
        self.wait_for(lambda: bool(self.d.pid(self.package)), 60, 'VPN process did not start automatically after boot/unlock')
        # No Wave activity is launched before post-reboot probe traffic is measured.
        return self.observe('reboot', 25, action=None)

    def split_screen(self):
        self.open_overview(); self.d.tap('Settings')
        self.d.wait_labels(lambda labels: 'Split tunneling' in labels)
        self.d.tap('Split tunneling')
        self.d.wait_labels(lambda labels: 'All apps' in labels)

    def probe_checkbox(self):
        nodes = self.d.nodes()
        matches = [n for n in nodes if n['label'] == PROBE and n['visible']]
        require(len(matches) == 1, 'Could not uniquely locate the probe package row')
        parent = matches[0]['parent']
        while parent >= 0:
            descendants = {parent}
            for node in nodes:
                if node['parent'] in descendants:
                    descendants.add(node['index'])
            checkboxes = [n for n in nodes if n['index'] in descendants and n.get('checkable')]
            if len(checkboxes) == 1:
                return checkboxes[0]['checked']
            parent = nodes[parent]['parent']
        raise Failure('Probe checkbox state is unavailable; no selection was toggled')

    def split_tunnel(self):
        if not self.config.get('split_tunnel_from_all_apps', False):
            raise Skipped('Split-tunnel test requires explicit split_tunnel_from_all_apps=true')
        self.split_screen()
        require("Every app's traffic goes through the VPN." in self.d.labels(),
                'Split test requires an observed initial All apps mode')
        self.state['split_changed'] = True; self.persist()
        def select(mode, select_probe):
            self.split_screen(); self.d.tap(mode)
            self.d.fill('Wave VPN test probe', editable=True)
            self.d.wait_labels(lambda labels: PROBE in labels)
            checked = self.probe_checkbox()
            if 'split_probe_checked_original' not in self.state:
                self.state['split_probe_checked_original'] = checked; self.persist()
            if not checked:
                self.d.tap(PROBE)
            require(self.probe_checkbox(), 'Probe app selection was not confirmed')
            self.d.tap('Back'); time.sleep(1)
            return {}
        try:
            self.observe('split-bypass', 60, lambda: select('Bypass', True), expected='blocked')
            self.observe('split-included', 60, lambda: select('Only selected', False))
        finally:
            self.restore_split()

    def restore_split(self):
        self.split_screen()
        if 'split_probe_checked_original' in self.state:
            self.d.tap('Bypass'); self.d.fill('Wave VPN test probe', editable=True)
            self.d.wait_labels(lambda labels: PROBE in labels)
            if self.probe_checkbox() != self.state['split_probe_checked_original']:
                self.d.tap(PROBE)
        self.d.tap('All apps'); self.d.tap('Back')
        self.state.pop('split_changed', None); self.persist()

    def lifecycle(self, name):
        if name == 'screen-off':
            def action():
                self.state['screen_changed'] = True; self.persist()
                self.d.shell('input', 'keyevent', 'KEYCODE_SLEEP')
                return {}
            try:
                return self.observe(name, 35, action)
            finally:
                self.d.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
                if not self.emulator:
                    self.manual('Unlock the phone to continue, without reconnecting Wave.')
                self.state.pop('screen_changed', None); self.persist()
        if name == 'doze':
            require('UPDATES STOPPED' not in self.d.shell('dumpsys', 'battery') and
                    'mForceIdle=true' not in self.d.shell('dumpsys', 'deviceidle'),
                    'Existing battery/idle simulation must be cleared before this test')
            if self.d.shell('id', '-u') != '0' and not self.state['artifact']['debuggable']:
                raise Skipped('Forced Doze is supported only on a rooted emulator or explicit debug-device profile')
            def action():
                self.state['doze_changed'] = True; self.persist()
                self.d.shell('dumpsys', 'battery', 'unplug')
                self.d.shell('input', 'keyevent', 'KEYCODE_SLEEP')
                self.d.shell('dumpsys', 'deviceidle', 'force-idle', 'deep')
                state = self.d.shell('dumpsys', 'deviceidle', 'get', 'deep')
                require('IDLE' in state, 'Device did not enter forced deep idle')
                return {'forced_idle_verified': True}
            try:
                return self.observe(name, 40, action)
            finally:
                self.d.shell('dumpsys', 'deviceidle', 'unforce'); self.d.shell('dumpsys', 'battery', 'reset')
                self.d.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
                self.state.pop('doze_changed', None); self.persist()
        if name == 'airplane':
            def action():
                self.state['airplane_changed'] = True; self.persist()
                self.d.shell('cmd', 'connectivity', 'airplane-mode', 'enable'); time.sleep(10)
                self.d.shell('cmd', 'connectivity', 'airplane-mode', 'disable')
                return {'airplane_seconds': 10}
            try:
                return self.observe(name, 45, action)
            finally:
                enabled = self.state['settings']['global/airplane_mode_on'] == '1'
                self.d.shell('cmd', 'connectivity', 'airplane-mode', 'enable' if enabled else 'disable')
                self.state.pop('airplane_changed', None); self.persist()
        if name == 'handoff':
            if self.emulator or not self.args.allow_cellular:
                raise Skipped('A physical phone with an active SIM and --allow-cellular is required')
            require(self.state['settings']['global/mobile_data'] in ('0', '1'), 'Original mobile data state is unknown')
            require('Wifi is connected' in self.d.shell('cmd', 'wifi', 'status'), 'Handoff requires initially connected Wi-Fi')
            self.state['radios_changed'] = True; self.persist()
            try:
                self.d.shell('svc', 'data', 'enable'); self.d.shell('svc', 'wifi', 'disable')
                self.observe('wifi-to-cellular', 30, protected=False)
                self.d.shell('svc', 'wifi', 'enable')
                self.observe('cellular-to-wifi', 30)
            finally:
                self.d.shell('svc', 'wifi', 'enable')
                self.d.shell('svc', 'data', 'enable' if self.state['settings']['global/mobile_data'] == '1' else 'disable')
                self.state.pop('radios_changed', None); self.persist()

    def run_scenario(self, name):
        if name == 'positive-control':
            return self.positive_control()
        if name == 'steady':
            self.connect(); self.protection(True)
            return self.observe(name)
        if name == 'crash':
            self.connect(); self.protection(True)
            return self.observe(name, 50, self.crash_action)
        if name == 'disconnect':
            self.protection(True)
            return self.observe(name, 35, self.disconnect, expected='blocked')
        if name == 'reconnect':
            return self.observe(name, 45, self.connect)
        if name == 'server-switch':
            if not self.config.get('switch_server') or not self.config.get('restore_target'):
                raise Skipped('Server switching requires switch_server and restore_target')
            self.state['selection_changed'] = True; self.persist()
            return self.observe(name, 75, lambda: self.choose_server(self.config['switch_server']))
        if name == 'peer-outage':
            return self.peer_outage()
        if name == 'kill-stress':
            return self.kill_stress()
        if name == 'private-dns':
            return self.private_dns()
        if name == 'split-tunnel':
            return self.split_tunnel()
        if name in ('screen-off', 'doze', 'airplane', 'handoff'):
            return self.lifecycle(name)
        if name == 'force-stop':
            self.observe(name, 25, lambda: self.d.shell('am', 'force-stop', '--user', '0', self.package) and {}, expected='blocked')
            self.connect(); return self.observe('force-stop-manual-recovery', 18)
        if name == 'reboot':
            return self.reboot()
        if name == 'traffic-soak':
            seconds = int(self.config.get('soak_seconds', 3600))
            max_gap = int(self.config.get('soak_max_gap_seconds', 30))
            require(60 <= seconds <= 86400 and 5 <= max_gap <= 300,
                    'Traffic soak requires 60–86400 seconds and a 5–300 second maximum success gap')
            if self.capture['kind'] == 'gateway' and self.capture.get('manage'):
                remaining = self.state.get('gateway_cleanup_deadline_unix_ms', 0) / 1000 - time.time()
                require(remaining > seconds + 300,
                        'Traffic soak exceeds the remaining managed-gateway cleanup lease; use a shorter run or an independently managed gateway')
            self.connect(); self.protection(True)
            result, rows = self.observe(name, seconds)
            result['success_gap_limit_seconds'] = max_gap
            # Include both edges: early successes cannot mask a later outage or a dead probe.
            start = result['window_start_unix_ms']
            end = result['window_end_unix_ms']
            gaps = {}
            for kind in result['required_probes']:
                times = [start] + sorted(int(row[0]) for row in rows
                                         if row[2] == kind and row[3].startswith('OK ')) + [end]
                gaps[kind] = round(max(b - a for a, b in zip(times, times[1:])) / 1000, 3)
            result['longest_success_gap_seconds'] = gaps
            if any(gap > max_gap for gap in gaps.values()):
                result.update(status='fail', error='Required traffic exceeded the maximum success gap during soak')
            result['scope_note'] = 'Active independent traffic workload; not natural idle or an overnight claim'
            self.write_report()
            return result, rows
        raise Failure('Unknown scenario')

    def write_report(self):
        failed = any(r['status'] == 'fail' for r in self.results)
        incomplete = any(r['status'] in ('skip', 'incomplete') for r in self.results)
        report = {'schema': 1, 'source_threads': ['fd5dd284-969f-435f-9afa-b1814ce8e6ef',
                  '2a7198b7-2664-4843-897e-2349a08a5be7', '240d3f84-ccf8-4071-b4fd-19554f697ec1'],
                  'artifact': self.state.get('artifact'), 'device': self.state.get('device'),
                  'validated_capture_families': self.validated_families,
                  'scope': 'Observed application probes and capture windows; never a device-wide leak-free certification',
                  'status': 'fail' if failed else ('incomplete' if incomplete else 'pass_for_exercised_scope'),
                  'capture_kind': self.capture['kind'], 'required_probes': self.config.get('required_probes', ['ipv4', 'dns', 'direct4', 'doh']), 'results': self.results,
                  'cleanup_complete': not self.state.get('pending_cleanup', True)}
        save(self.output / 'report.json', report)
        lines = ['# Wave Android device test run', '', 'Result: **' + report['status'] + '**.', '', report['scope'], '',
                 'Capture families with a successful positive control: ' + (', '.join(self.validated_families) or 'none'), '']
        lines += ['- ' + r['scenario'] + ': **' + r['status'] + '**' +
                  (' — ' + '; '.join(r.get('findings', [])) if r.get('findings') else '') +
                  (' — ' + r['error'] if r.get('error') else '') for r in self.results]
        lines += ['', 'Cleanup complete: ' + str(report['cleanup_complete']).lower() + '.', '',
                  'Raw PCAP, probe output, configuration and recovery state stay in `private/`.',
                  'Review filtered evidence before publishing it. Public reports omit IPs, account values and DNS names.', '']
        (self.output / 'report.md').write_text('\n'.join(lines))

    def cleanup(self):
        if not self.state.get('pending_cleanup', True):
            return []
        errors = []
        if self.active_capture:
            try:
                self.active_capture.abort()
            except (OSError, subprocess.TimeoutExpired):
                errors.append('capture process')
        # Each completed cleanup step is checkpointed, so restore is idempotent.
        def attempt(label, fn):
            try:
                fn()
                self.persist()
            except (Failure, OSError, ValueError):
                errors.append(label)
        if 'settings' not in self.state:
            if self.state.get('booted_avd'):
                attempt('test AVD shutdown', lambda: self.d.call('emu', 'kill'))
            self.state['pending_cleanup'] = bool(errors); self.persist()
            return errors
        try:
            self.d.wait(8)
        except Failure:
            if self.state.get('gateway_root'):
                try:
                    root = self.state['gateway_root']
                    self.remote('if [ -f ' + root + '/cleanup.sh ]; then sh ' + root + '/cleanup.sh; fi\n')
                except Failure:
                    pass
            self.state['cleanup_errors'] = ['ADB unavailable; restore when the device returns']
            self.persist()
            return self.state['cleanup_errors']
        if self.state.get('peer_chain'):
            attempt('peer firewall', lambda: self.firewall(False))
        if self.state.get('dns_chain'):
            attempt('DNS attribution counters', lambda: self.dns_counters(False))
        if self.state.get('doze_changed'):
            def undo_doze():
                self.d.shell('dumpsys', 'deviceidle', 'unforce'); self.d.shell('dumpsys', 'battery', 'reset')
                self.d.shell('input', 'keyevent', 'KEYCODE_WAKEUP'); self.state.pop('doze_changed', None)
            attempt('forced idle', undo_doze)
        if self.state.get('screen_changed'):
            attempt('screen wake', lambda: self.d.shell('input', 'keyevent', 'KEYCODE_WAKEUP'))
        if self.state.get('radios_changed'):
            def radios():
                self.d.shell('svc', 'wifi', 'enable')
                self.d.shell('svc', 'data', 'enable' if self.state['settings']['global/mobile_data'] == '1' else 'disable')
                self.state.pop('radios_changed', None)
            attempt('radio settings', radios)
        if self.state.get('airplane_changed'):
            def airplane():
                self.d.shell('cmd', 'connectivity', 'airplane-mode', 'enable' if self.state['settings']['global/airplane_mode_on'] == '1' else 'disable')
                self.state.pop('airplane_changed', None)
            attempt('airplane mode', airplane)
        def restore_dns():
            for key in ('private_dns_mode', 'private_dns_specifier'):
                self.d.put('global', key, self.state['settings']['global/' + key])
        attempt('Private DNS', restore_dns)
        if self.state.get('wifi_changed'):
            attempt('Wi-Fi DHCP (unlock the phone before restore)', self.restore_wifi)
        if self.state.get('gateway_root'):
            def gateway():
                root = self.state['gateway_root']; unit = self.state['gateway_unit']
                self.remote('if [ -f ' + root + '/cleanup.sh ]; then sh ' + root + '/cleanup.sh; fi\n' +
                            'systemctl stop ' + unit + '.timer 2>/dev/null || true\n' +
                            'if [ \"$(cat /run/lock/wave-vpn-qa-gateway/owner 2>/dev/null)\" = ' + self.run_id + ' ]; then rm -f /run/lock/wave-vpn-qa-gateway/owner; rmdir /run/lock/wave-vpn-qa-gateway; fi\n' +
                            'rm -f ' + root + '/cleanup.sh\nrmdir ' + root + ' 2>/dev/null || true\n')
                self.state.pop('gateway_root', None)
            attempt('gateway forwarding/firewall', gateway)
        if self.state.get('split_changed'):
            attempt('split tunneling', self.restore_split)
        if self.state.get('selection_changed'):
            def selection():
                target = self.config['restore_target']
                if target == 'best':
                    self.open_overview(); self.d.tap('Servers'); self.d.fill('', editable=True)
                    self.d.tap('Best server')
                else:
                    self.choose_server(target)
                self.state.pop('selection_changed', None)
            attempt('server selection', selection)
        def vpn_policy():
            if not self.d.installed(self.package):
                return
            if not self.state.get('target_started'):
                return
            original = self.state['settings']['secure/always_on_vpn_app']
            if original == self.package:
                self.connect(); self.protection(True)
                if self.state['settings']['secure/always_on_vpn_lockdown'] != '1':
                    self.vpn_settings(); self.d.tap('Block connections without VPN')
            elif original == 'null':
                self.protection(False)
                if self.state.get('original_connected'):
                    self.connect()
                elif self.active_vpn():
                    self.disconnect()
            else:
                raise Failure('Original VPN policy belongs to another package')
            for key in ('always_on_vpn_app', 'always_on_vpn_lockdown'):
                current = self.d.setting('secure', key)
                original_value = self.state['settings']['secure/' + key]
                equivalent_disabled = key == 'always_on_vpn_lockdown' and current in ('0', 'null') and original_value in ('0', 'null')
                require(current == original_value or equivalent_disabled,
                        'Original VPN policy was not restored')
        attempt('original VPN policy', vpn_policy)
        def remove_captures():
            if self.state.get('capture_binary'):
                self.d.shell('pkill', '-TERM', '-f', '^' + re.escape(self.capture_binary) + ' ', check=False)
            for path in self.state.get('device_capture_files', []) + [self.state.get('capture_binary')]:
                if path:
                    self.d.shell('rm', '-f', path)
            self.state.pop('capture_binary', None); self.state['device_capture_files'] = []
        attempt('device capture files', remove_captures)
        # Keep the driver if UI restoration failed, so `restore` can finish without reinstalling it.
        if not errors:
            for package in list(reversed(self.state.get('created_helpers', []))):
                def remove(package=package):
                    if self.d.installed(package):
                        self.d.shell('pm', 'uninstall', '--user', '0', package)
                    self.state['created_helpers'].remove(package)
                attempt('temporary QA package', remove)
        if self.state.get('target_created') and not self.state.get('keep_app') and not errors:
            def remove_target():
                if self.d.installed(self.package):
                    self.d.shell('pm', 'uninstall', '--user', '0', self.package)
                self.state['target_created'] = False
            attempt('temporary target app', remove_target)
        if self.state.get('root_changed') and not errors:
            def unroot():
                self.d.call('unroot'); self.d.wait(); self.state.pop('root_changed', None)
            attempt('emulator ADB root', unroot)
        if self.state.get('booted_avd') and not errors:
            attempt('test AVD shutdown', lambda: self.d.call('emu', 'kill'))
        self.state['cleanup_errors'] = errors
        self.state['pending_cleanup'] = bool(errors)
        self.persist()
        return errors


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=('doctor', 'run', 'restore', 'list'))
    p.add_argument('--config', type=Path)
    p.add_argument('--profile', default='default')
    p.add_argument('--output', type=Path)
    p.add_argument('--state', type=Path, help='private/state.json from an interrupted run')
    p.add_argument('--scenarios', help='comma-separated scenario names, overriding configuration')
    p.add_argument('--allow-root', action='store_true', help='allow root on an emulator, never on a physical phone')
    p.add_argument('--allow-replace', action='store_true', help='allow removing differently signed OWNER app data')
    p.add_argument('--allow-cellular', action='store_true', help='allow mobile data usage in handoff tests')
    p.add_argument('--interactive', action='store_true', help='permit operator checkpoints for phone unlock')
    p.add_argument('--keep-app', action='store_true', help='keep a newly installed target APK after the run')
    p.add_argument('--boot-avd', action='store_true', help='boot the configured AVD on its explicit serial/port')
    p.add_argument('--build-app', action='store_true', help='run core tests, lint and debug/release assemblies first')
    return p


def boot_avd(run):
    result = run.d.call('get-state', check=False, timeout=5)
    if result.returncode == 0 and result.stdout.strip() == b'device':
        return
    name = run.config.get('avd')
    require(name and run.args.boot_avd, 'Device is offline; --boot-avd and an avd configuration are required to start it')
    match = re.fullmatch(r'emulator-(\d+)', run.config['adb']['serial'])
    require(match is not None and int(match.group(1)) % 2 == 0, 'AVD serial must specify an even emulator console port')
    port = int(match.group(1))
    require(5554 <= port <= 5680, 'Emulator port outside the supported range')
    for current in (port, port + 1):
        with socket.socket() as sock:
            require(sock.connect_ex(('127.0.0.1', current)) != 0, 'Configured emulator port is already occupied')
    require(run.config['adb'].get('host', '127.0.0.1') in ('127.0.0.1', 'localhost'), 'AVD startup is local only')
    run.state['booted_avd'] = True; run.persist()
    with (run.output / 'private' / 'emulator.log').open('wb') as log:
        subprocess.Popen([str(run.sdk / 'emulator' / 'emulator'), '-avd', name, '-port', str(port),
                          '-no-snapshot-save', '-no-boot-anim'], stdout=log, stderr=log)
    run.log('Waiting for the configured AVD to boot…')
    run.d.wait(150)
    run.wait_for(lambda: run.d.shell('getprop', 'sys.boot_completed') == '1', 120, 'AVD did not finish booting')


def main(argv=None):
    args = parser().parse_args(argv)
    if args.command == 'list':
        print('\n'.join(SCENARIOS)); return 0
    if args.command == 'restore':
        require(args.state is not None, 'restore requires --state')
        state = json.loads(args.state.read_text())
        run = Run(state['config'], args.state.resolve().parents[1], args, state)
        run.emulator = state.get('device', {}).get('emulator', False)
        errors = run.cleanup()
        print('Restoration complete.' if not errors else 'Restoration incomplete; retry when the device is unlocked and reachable.')
        return 3 if errors else 0
    require(args.config is not None and args.output is not None, 'doctor/run require --config and a private --output directory')
    config = load_config(args.config, args.profile)
    output = private_directory(args.output)
    require(not any(output.iterdir()), 'Use a new output directory for each run; use restore for an interrupted run')
    run = Run(config, output, args)
    if args.command == 'doctor':
        info = run.preflight()
        run.state['pending_cleanup'] = False  # Preflight is read-only.
        run.persist()
        print(json.dumps({'device': info, 'artifact': run.state['artifact'], 'capture_kind': run.capture['kind']}, indent=2))
        return 0
    require(not (args.scenarios and any(n not in SCENARIOS for n in args.scenarios.split(','))), 'Unknown requested scenario')
    selected = args.scenarios.split(',') if args.scenarios else config.get('scenarios', ['steady', 'crash', 'disconnect', 'reconnect'])
    require(selected and all(n in SCENARIOS for n in selected), 'Invalid scenario list')
    if run.capture['kind'] != 'none' and 'positive-control' not in selected:
        selected.insert(0, 'positive-control')
    elif 'positive-control' in selected:
        selected.remove('positive-control'); selected.insert(0, 'positive-control')
    exit_code = 0
    try:
        if args.boot_avd:
            boot_avd(run)
        if args.build_app:
            env = dict(os.environ, ANDROID_HOME=str(run.sdk))
            with (output / 'private' / 'application-build.log').open('wb') as log:
                built = subprocess.run([str(REPO / 'gradlew'), '--no-configuration-cache', ':core:test',
                                        ':app:lintDebug', ':app:assembleDebug', ':app:assembleRelease'], cwd=REPO, env=env, stdout=log, stderr=log)
            require(built.returncode == 0, 'Application checks/build failed (private/application-build.log)')
        run.preflight(); run.setup()
        for name in selected:
            run.log('Running ' + name + '…')
            before = len(run.results)
            try:
                run.run_scenario(name)
            except Skipped as error:
                run.results.append({'scenario': name, 'status': 'skip', 'error': str(error)})
            except Failure as error:
                if len(run.results) == before:
                    run.results.append({'scenario': name, 'status': 'fail', 'error': str(error)})
                if name == 'positive-control':
                    for remaining in selected[1:]:
                        run.results.append({'scenario': remaining, 'status': 'skip', 'error': 'Capture positive control did not pass'})
                    break
            run.write_report()
    except (Failure, OSError, ValueError, KeyError) as error:
        # Raw third-party exceptions/configuration values are not emitted to public reports.
        run.results.append({'scenario': 'setup-or-run', 'status': 'fail',
                            'error': str(error) if isinstance(error, Failure) else 'Configuration or tool failure; inspect private state'})
        exit_code = 2
    except KeyboardInterrupt:
        run.results.append({'scenario': 'interrupted', 'status': 'incomplete', 'error': 'Operator interrupted; cleanup attempted'})
        exit_code = 130
    finally:
        run.log('Restoring device and capture settings…')
        errors = run.cleanup()
        run.write_report()
        if errors:
            exit_code = 3
            print('Cleanup needs a retry: python3 tools/vpn-probe/run.py restore --state ' + shlex.quote(str(run.state_path)))
    if not exit_code:
        exit_code = 1 if any(r['status'] == 'fail' for r in run.results) else (2 if any(r['status'] != 'pass' for r in run.results) else 0)
    print('Filtered report: ' + str(output / 'report.md'))
    return exit_code


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(main())
    except Failure as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
    except (OSError, ValueError, KeyError):
        print('Invalid configuration or unavailable prerequisite. No successful run recorded.', file=sys.stderr)
        sys.exit(2)
