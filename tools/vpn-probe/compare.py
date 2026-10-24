#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Run the same capture/probe assertions against an already installed VPN.

UI selectors belong in a private config, so version-specific layouts fail closed.
No third-party APK is installed, replaced, signed or removed by this adapter.
"""
import hashlib
import json
from pathlib import Path
import signal
import stat
import sys
import time

import run


class InstalledVpnRun(run.Run):
    def run_scenario(self, name):
        if name not in ('positive-control', 'steady', 'disconnect', 'reconnect', 'wifi-reconnect', 'crash', 'force-stop'):
            raise run.Skipped('Installed-app comparison supports steady, disconnect, reconnect, Wi-Fi recovery, crash and force-stop only')
        return super().run_scenario(name)

    def after_positive_control(self):
        # Disabling Always-on may revoke an app's existing service session.
        # Reset only during unprotected preparation, never to pass crash recovery.
        self.d.shell('am', 'force-stop', '--user', '0', self.package)
        self.results[-1]['comparison_restart_after_positive_control'] = True
        self.write_report()

    def install(self, package, apk, helper=False):
        if helper:
            return super().install(package, apk, helper=True)
        run.require(self.d.installed(package), 'Comparison target must already be installed')
        paths = self.d.shell('pm', 'path', '--user', '0', package).splitlines()
        self.state['installed_apks'] = []
        for index, remote in enumerate(paths):
            run.require(remote.startswith('package:'), 'Invalid installed APK path')
            destination = self.output / 'private' / ('installed-' + str(index) + '.apk')
            self.d.call('pull', remote.split(':', 1)[1], str(destination))
            self.state['installed_apks'].append({'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()})
        run.require(self.state['installed_apks'][0]['sha256'] == self.state['artifact']['sha256'],
                    'Configured APK differs from installed base APK')
        self.state['target_created'] = False
        self.persist()

    def steps(self, name):
        for selector in self.config['comparison_ui'].get(name, []):
            if name == 'overview' and any(n.get('selected') and self.matches(n, selector) for n in self.d.nodes()):
                continue
            self.d.ui('tap', selector=selector)
            time.sleep(0.5)

    def login(self):
        self.d.open(self.package)
        self.steps('overview')
        run.require(self.config.get('comparison_ui'), 'Explicit comparison UI selectors required')
        authentication = self.config['comparison_ui'].get('authentication')
        if authentication and any(self.matches(n, authentication['needed']) for n in self.d.nodes()):
            for selector in authentication['open']:
                self.d.ui('tap', selector=selector)
                time.sleep(0.5)
            path = Path(self.config['credentials_file'])
            run.require(not stat.S_IMODE(path.stat().st_mode) & 0o077, 'Credentials file must be private')
            values = json.loads(path.read_text())
            self.d.fill(values['username'], **authentication['username'])
            self.d.fill(values['password'], **authentication['password'])
            del values
            self.d.ui('tap', selector=authentication['submit'])
            self.wait_for(lambda: any(self.matches(n, authentication['ready']) for n in self.d.nodes()),
                          60, 'Comparison sign-in did not complete')

    def connect(self):
        self.login()
        selector = self.config['comparison_ui']['connected']
        connected_label = any(self.matches(n, selector) for n in self.d.nodes())
        if not self.active_vpn() or not connected_label:
            self.steps('connect')
        def active_or_consent():
            if self.active_vpn():
                return True
            labels = self.d.labels()
            if 'Connection request' in labels or any('wants to set up a VPN connection' in label for label in labels):
                self.d.tap('OK')
            return self.active_vpn()
        self.wait_for(active_or_consent, 60, 'Comparison VPN did not become active')
        self.wait_for(lambda: any(self.matches(n, selector) for n in self.d.nodes()), 60,
                      'Comparison VPN did not report a connected tunnel')

    @staticmethod
    def matches(node, selector):
        if not node['visible']:
            return False
        for key, value in selector.items():
            if key == 'prefix':
                matched = node.get('label', '').startswith(value)
            elif key == 'text':
                matched = value in (node.get('label'), node.get('description'))
            else:
                matched = node.get(key) == value
            if not matched:
                return False
        return True

    def disconnect(self):
        self.d.open(self.package)
        self.steps('overview')
        if self.active_vpn():
            self.steps('disconnect')
        self.wait_for(lambda: not self.active_vpn(), 25,
                      'Comparison VPN retained an active VPN; teardown was not exercised')


if __name__ == '__main__':
    # Reuse the runner's positive control, workload, capture rules, cleanup and exits.
    run.Run = InstalledVpnRun
    def interrupted(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(run.main())
    except run.Failure as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
    except (OSError, ValueError, KeyError):
        print('Invalid configuration or unavailable comparison prerequisite. No successful run recorded.', file=sys.stderr)
        sys.exit(2)
