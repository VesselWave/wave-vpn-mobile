#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Temporary, leased Linux Wi-Fi AP for link-side VPN capture (Python stdlib)."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys

ROOT = '/run/wave-vpn-qa-ap'
UNIT = 'wave-vpn-qa-hostapd'
EXPIRY = 'wave-vpn-qa-ap-expiry'


def validate(args):
    if not re.fullmatch(r'[A-Za-z0-9_@.:-]+', args.ssh) or args.ssh.startswith('-'):
        raise ValueError('Invalid SSH target')
    if args.command == 'start':
        for value in (args.interface, args.bridge):
            if not re.fullmatch(r'[A-Za-z0-9_.-]{1,15}', value):
                raise ValueError('Invalid interface')
        if args.interface == args.bridge or args.interface == 'lo':
            raise ValueError('AP interface must be a separate unused radio')
        if not 1 <= args.channel <= 11 or not 5 <= args.minutes <= 180:
            raise ValueError('Use channel 1–11 and a lease of 5–180 minutes')


def remote(args, script):
    privilege = ['sudo', '-n'] if args.sudo else []
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
                             args.ssh, shlex.join(privilege + ['sh', '-s'])],
                            input=script, text=True, capture_output=True, timeout=40)
    if result.returncode:
        # Remote stdout/stderr could contain private configuration. Keep it local.
        raise RuntimeError('Remote AP operation failed; inspect its private runtime state and journal')
    return result.stdout.strip()


def start_script(args, password):
    iface, bridge = args.interface, args.bridge
    # Validate/lock before changes; arm expiry before starting hostapd. Never alter
    # the uplink, forwarding sysctls, firewall, persistent network files or DHCP.
    return f'''set -eu
umask 077
test "$(id -u)" = 0
command -v hostapd >/dev/null
command -v iw >/dev/null
command -v systemd-run >/dev/null
test -d /sys/class/net/{iface}/phy80211
test -d /sys/class/net/{bridge}/bridge
test ! -e /sys/class/net/{iface}/master
test -z "$(ip -o address show dev {iface})"
test "$(iw dev {iface} info | awk '$1 == "type" {{print $2}}')" = managed
# Refuse even an unnumbered radio that is administratively up.
flags=$(cat /sys/class/net/{iface}/flags)
test $((flags & 1)) = 0
mkdir {ROOT}
trap 'if [ -f {ROOT}/cleanup.sh ]; then sh {ROOT}/cleanup.sh; else rmdir {ROOT}; fi; systemctl stop {EXPIRY}.timer 2>/dev/null || true' EXIT
cat > {ROOT}/cleanup.sh <<'CLEAN'
#!/bin/sh
set -eu
systemctl stop {UNIT}.service 2>/dev/null || true
ip link set {iface} nomaster
ip link set {iface} down
iw dev {iface} set type managed
rm -f {ROOT}/hostapd.conf
CLEAN
chmod 700 {ROOT}/cleanup.sh
systemd-run --unit={EXPIRY} --on-active={args.minutes}m /bin/sh {ROOT}/cleanup.sh >/dev/null
cat > {ROOT}/hostapd.conf <<'CONFIG'
interface={iface}
bridge={bridge}
driver=nl80211
ssid=Wave-QA-Sigma
hw_mode=g
channel={args.channel}
wmm_enabled=1
auth_algs=1
wpa=2
wpa_passphrase={password}
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
max_num_sta=1
ap_isolate=1
CONFIG
systemd-run --unit={UNIT} --property=Restart=no /usr/sbin/hostapd {ROOT}/hostapd.conf >/dev/null
sleep 8
systemctl is-active --quiet {UNIT}.service
iw dev {iface} info | awk '$1 == "type" {{if ($2 != "AP") exit 1; found=1}} END {{if (!found) exit 1}}'
trap - EXIT
'''


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start', 'status', 'stop'])
    parser.add_argument('--ssh', required=True)
    parser.add_argument('--sudo', action='store_true', help='use sudo -n on a non-root SSH account')
    parser.add_argument('--interface', default='wlp0s20f3')
    parser.add_argument('--bridge', default='vmbr0')
    parser.add_argument('--channel', type=int, default=6)
    parser.add_argument('--minutes', type=int, default=90)
    parser.add_argument('--output', type=Path, help='new private directory for AP credentials/state')
    args = parser.parse_args(argv)
    validate(args)
    if args.command == 'start':
        if args.output is None:
            raise ValueError('start requires a new --output directory outside Git')
        os.umask(0o077)
        args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
        password = secrets.token_urlsafe(24)
        (args.output / 'account.json').write_text(json.dumps({'ssid': 'Wave-QA-Sigma', 'password': password}))
        (args.output / 'state.json').write_text(json.dumps({
            'ssh': args.ssh, 'interface': args.interface, 'bridge': args.bridge,
            'lease_minutes': args.minutes, 'sudo': args.sudo}))
        remote(args, start_script(args, password))
        print(f'AP started; automatic cleanup in {args.minutes} minutes. Private credentials: {args.output / "account.json"}')
    elif args.command == 'stop':
        remote(args, f'''set -eu
if [ -f {ROOT}/cleanup.sh ]; then sh {ROOT}/cleanup.sh; fi
systemctl stop {EXPIRY}.timer 2>/dev/null || true
systemctl reset-failed {UNIT}.service {EXPIRY}.service 2>/dev/null || true
if [ -d {ROOT} ]; then rm -f {ROOT}/cleanup.sh; rmdir {ROOT}; fi
''')
        print('Temporary AP stopped; radio restored to managed/down.')
    else:
        print(remote(args, f'systemctl is-active {UNIT}.service || true\nsystemctl list-timers {EXPIRY}.timer --no-pager\n'))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
