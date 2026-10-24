#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Leased, isolated dual-stack QA hotspot via an IPv6-capable WireGuard VPS."""
import argparse
import ipaddress
import json
import os
import pathlib
import re
import secrets
import shlex
import subprocess
import sys
P = None
VPS = SIGMA = None
SETTINGS = {}
def call(host,s):
    s = s.replace('@VPS_IPV4@', SETTINGS['vps_ipv4']).replace('@RADIO@', SETTINGS['radio']).replace('@PHY@', SETTINGS['phy']).replace('@VPS_IFACE@', SETTINGS['vps_interface'])
    r=subprocess.run(host,input=s,text=True,capture_output=True,timeout=40)
    if r.returncode:
        (P/'dualstack-last-error.txt').write_text(r.stdout+r.stderr)
        raise RuntimeError('Remote operation failed; private error saved')
    return r.stdout.strip()
def keys():
    values=call(SIGMA,'k=$(wg genkey); printf "%s\\n" "$k"; printf "%s\\n" "$k" | wg pubkey\n').splitlines()
    if len(values) != 2 or any(not re.fullmatch(r'[A-Za-z0-9+/]{43}=', value) for value in values):
        raise RuntimeError('WireGuard key generation did not complete')
    return {'private':values[0],'public':values[1]}

def prepare():
    call(SIGMA, '''set -eu
test "$(id -u)" = 0
test -d /sys/class/net/@RADIO@/phy80211
test ! -e /sys/class/net/@RADIO@/master
test -z "$(ip -o address show dev @RADIO@)"
test "$(iw dev @RADIO@ info | awk '$1 == "type" {print $2}')" = managed
flags=$(cat /sys/class/net/@RADIO@/flags)
test $((flags & 1)) = 0
test ! -e /run/wave-qa6
test ! -e /run/netns/waveqa6
''')
    c={'sigma':keys(),'vps':keys()};(P/'tunnel-keys.json').write_text(json.dumps(c))
    vps_cleanup='''#!/bin/sh
set -eu
iptables -D INPUT -p udp --dport 51876 -m comment --comment wave-qa6 -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i waveqa6 -s 10.77.24.0/24 -o @VPS_IFACE@ -m comment --comment wave-qa6 -j ACCEPT 2>/dev/null || true
iptables -D FORWARD -i @VPS_IFACE@ -o waveqa6 -d 10.77.24.0/24 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment wave-qa6 -j ACCEPT 2>/dev/null || true
ip6tables -D FORWARD -i waveqa6 -s fd42:77:24:2::/64 -o @VPS_IFACE@ -m comment --comment wave-qa6 -j ACCEPT 2>/dev/null || true
ip6tables -D FORWARD -i @VPS_IFACE@ -o waveqa6 -d fd42:77:24:2::/64 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment wave-qa6 -j ACCEPT 2>/dev/null || true
nft delete table inet wave_qa6 2>/dev/null || true
ip link del waveqa6 2>/dev/null || true
sysctl -q -w net.ipv6.conf.all.forwarding=$(cat /run/wave-qa6/forward6)
rm -f /run/wave-qa6/wg.conf
'''
    (P/'vps-cleanup.sh').write_text(vps_cleanup)
    s='''set -eu
umask 077
test "$(sysctl -n net.ipv4.ip_forward)" = 1
test -d /sys/class/net/@VPS_IFACE@
test -z "$(ss -Hlun 'sport = :51876')"
mkdir /run/wave-qa6
sysctl -n net.ipv6.conf.all.forwarding > /run/wave-qa6/forward6
cat > /run/wave-qa6/cleanup.sh <<'CLEAN'
'''+vps_cleanup+'''CLEAN
chmod 700 /run/wave-qa6/cleanup.sh
trap 'sh /run/wave-qa6/cleanup.sh' EXIT
systemd-run --unit=wave-qa6-expiry --on-active=90m /bin/sh /run/wave-qa6/cleanup.sh >/dev/null
cat > /run/wave-qa6/wg.conf <<'CONF'
[Interface]
PrivateKey = '''+c['vps']['private']+'''
ListenPort = 51876
[Peer]
PublicKey = '''+c['sigma']['public']+'''
AllowedIPs = 10.77.25.2/32,10.77.24.0/24,fd42:77:24:1::2/128,fd42:77:24:2::/64
CONF
ip link add waveqa6 type wireguard
wg setconf waveqa6 /run/wave-qa6/wg.conf
ip addr add 10.77.25.1/30 dev waveqa6
ip -6 addr add fd42:77:24:1::1/64 dev waveqa6
ip link set waveqa6 mtu 1380 up
ip route add 10.77.24.0/24 dev waveqa6
ip -6 route add fd42:77:24:2::/64 dev waveqa6
iptables -I INPUT 1 -p udp --dport 51876 -m comment --comment wave-qa6 -j ACCEPT
iptables -I FORWARD 1 -i waveqa6 -s 10.77.24.0/24 -o @VPS_IFACE@ -m comment --comment wave-qa6 -j ACCEPT
iptables -I FORWARD 1 -i @VPS_IFACE@ -o waveqa6 -d 10.77.24.0/24 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment wave-qa6 -j ACCEPT
ip6tables -I FORWARD 1 -i waveqa6 -s fd42:77:24:2::/64 -o @VPS_IFACE@ -m comment --comment wave-qa6 -j ACCEPT
ip6tables -I FORWARD 1 -i @VPS_IFACE@ -o waveqa6 -d fd42:77:24:2::/64 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment wave-qa6 -j ACCEPT
nft add table inet wave_qa6
nft 'add chain inet wave_qa6 postrouting { type nat hook postrouting priority srcnat; policy accept; }'
nft add rule inet wave_qa6 postrouting ip saddr 10.77.24.0/24 oifname @VPS_IFACE@ masquerade
nft add rule inet wave_qa6 postrouting ip6 saddr fd42:77:24:2::/64 oifname @VPS_IFACE@ masquerade
sysctl -q -w net.ipv6.conf.all.forwarding=1
trap - EXIT
'''
    call(VPS,s)
    # Create WireGuard in the root namespace so its UDP socket retains the normal
    # IPv4 uplink when the interface is moved into the isolated test namespace.
    sigma_cleanup='''#!/bin/sh
set -eu
systemctl stop wave-qa6-hostapd.service wave-qa6-dnsmasq.service 2>/dev/null || true
if [ -e /run/netns/waveqa6 ]; then
 ip netns exec waveqa6 ip link set @RADIO@ down 2>/dev/null || true
 ip netns exec waveqa6 ip addr del 10.77.24.1/24 dev @RADIO@ 2>/dev/null || true
 ip netns exec waveqa6 ip -6 addr del fd42:77:24:2::1/64 dev @RADIO@ 2>/dev/null || true
 ip netns exec waveqa6 iw dev @RADIO@ set type managed 2>/dev/null || true
 ip netns exec waveqa6 iw phy @PHY@ set netns 1 2>/dev/null || true
 ip netns del waveqa6
fi
if [ -f /run/wave-qa6/radio-mtu ]; then
 ip link set @RADIO@ mtu "$(cat /run/wave-qa6/radio-mtu)"
fi
ip link del waveqa6 2>/dev/null || true
rm -f /run/wave-qa6/wg.conf /run/wave-qa6/hostapd.conf
'''
    (P/'sigma-cleanup.sh').write_text(sigma_cleanup)
    s='''set -eu
umask 077
mkdir /run/wave-qa6
cat /sys/class/net/@RADIO@/mtu > /run/wave-qa6/radio-mtu
cat > /run/wave-qa6/cleanup.sh <<'CLEAN'
'''+sigma_cleanup+'''CLEAN
chmod 700 /run/wave-qa6/cleanup.sh
trap 'sh /run/wave-qa6/cleanup.sh' EXIT
systemd-run --unit=wave-qa6-expiry --on-active=90m /bin/sh /run/wave-qa6/cleanup.sh >/dev/null
ip netns add waveqa6
cat > /run/wave-qa6/wg.conf <<'CONF'
[Interface]
PrivateKey = '''+c['sigma']['private']+'''
[Peer]
PublicKey = '''+c['vps']['public']+'''
Endpoint = @VPS_IPV4@:51876
AllowedIPs = 0.0.0.0/0,::/0
PersistentKeepalive = 20
CONF
ip link add waveqa6 type wireguard
wg setconf waveqa6 /run/wave-qa6/wg.conf
ip link set waveqa6 netns waveqa6
ip netns exec waveqa6 ip link set lo up
ip netns exec waveqa6 ip addr add 10.77.25.2/30 dev waveqa6
ip netns exec waveqa6 ip -6 addr add fd42:77:24:1::2/64 dev waveqa6
ip netns exec waveqa6 ip link set waveqa6 mtu 1380 up
ip netns exec waveqa6 ip route add default dev waveqa6
ip netns exec waveqa6 ip -6 route add default dev waveqa6
trap - EXIT
'''
    call(SIGMA,s)
    print('Temporary dual-stack tunnel prepared; cleanup timers armed on both hosts.')

def ap():
    password=secrets.token_urlsafe(24); (P/'dualstack-account.json').write_text(json.dumps({'ssid':'Wave-QA-Sigma','password':password}))
    # Prior bridged AP must have been stopped/restored before moving the radio.
    s='''set -eu
umask 077
trap 'sh /run/wave-qa6/cleanup.sh' EXIT
test ! -e /sys/class/net/@RADIO@/master
test -z "$(ip -o address show dev @RADIO@)"
iw phy @PHY@ set netns name waveqa6
ip netns exec waveqa6 ip link set @RADIO@ mtu 1380
ip netns exec waveqa6 ip addr add 10.77.24.1/24 dev @RADIO@
ip netns exec waveqa6 ip -6 addr add fd42:77:24:2::1/64 dev @RADIO@
ip netns exec waveqa6 sysctl -q -w net.ipv4.ip_forward=1 net.ipv6.conf.all.forwarding=1
ip netns exec waveqa6 iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
ip netns exec waveqa6 ip6tables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
cat > /run/wave-qa6/hostapd.conf <<'CONF'
interface=@RADIO@
driver=nl80211
ssid=Wave-QA-Sigma
hw_mode=g
channel=6
wmm_enabled=1
auth_algs=1
wpa=2
wpa_passphrase='''+password+'''
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
max_num_sta=1
ap_isolate=1
CONF
cat > /run/wave-qa6/dnsmasq.conf <<'CONF'
interface=@RADIO@
bind-interfaces
port=0
dhcp-leasefile=/run/wave-qa6/leases
pid-file=/run/wave-qa6/dnsmasq.pid
dhcp-range=10.77.24.20,10.77.24.30,255.255.255.0,1h
dhcp-option=option:router,10.77.24.1
dhcp-option=option:dns-server,1.1.1.1
enable-ra
dhcp-range=fd42:77:24:2::,ra-stateless,64,1h
dhcp-option=option6:dns-server,[2606:4700:4700::1111]
ra-param=@RADIO@,10,600
CONF
systemd-run --unit=wave-qa6-hostapd ip netns exec waveqa6 hostapd /run/wave-qa6/hostapd.conf >/dev/null
sleep 8
systemd-run --unit=wave-qa6-dnsmasq ip netns exec waveqa6 dnsmasq --keep-in-foreground --conf-file=/run/wave-qa6/dnsmasq.conf >/dev/null
sleep 2
systemctl is-active wave-qa6-hostapd wave-qa6-dnsmasq
trap - EXIT
'''
    print(call(SIGMA,s))

def stop():
    errors = []
    for label, host in [('sigma', SIGMA), ('vps', VPS)]:
        try:
            call(host, "set -eu\nif [ -f /run/wave-qa6/cleanup.sh ]; then sh /run/wave-qa6/cleanup.sh; fi\nsystemctl stop wave-qa6-expiry.timer 2>/dev/null || true\nif [ -d /run/wave-qa6 ]; then rm -f /run/wave-qa6/cleanup.sh /run/wave-qa6/forward6 /run/wave-qa6/radio-mtu /run/wave-qa6/leases /run/wave-qa6/dnsmasq.pid /run/wave-qa6/dnsmasq.conf; rmdir /run/wave-qa6; fi\n")
        except RuntimeError:
            errors.append(label)
    if errors:
        raise RuntimeError('Cleanup incomplete on ' + ', '.join(errors) + '; retained state and host expiry timers require inspection')
    for name in ['tunnel-keys.json', 'dualstack-account.json']:
        (P / name).unlink(missing_ok=True)
    print('Tunnel/AP stopped on both hosts; forwarding restored and local keys/password removed.')


def main(argv=None):
    global P, VPS, SIGMA, SETTINGS
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','start-ap','status','stop'])
    parser.add_argument('--output',required=True,type=pathlib.Path)
    parser.add_argument('--sigma-ssh')
    parser.add_argument('--vps-ssh')
    parser.add_argument('--vps-ipv4')
    parser.add_argument('--vps-ssh-port',type=int,default=22)
    parser.add_argument('--known-hosts',type=pathlib.Path)
    parser.add_argument('--radio',default='wlp0s20f3')
    parser.add_argument('--phy',default='phy0')
    parser.add_argument('--vps-interface',default='eth0')
    args=parser.parse_args(argv)
    os.umask(0o077)
    P=args.output.resolve()
    if args.command=='prepare':
        for target in [args.sigma_ssh,args.vps_ssh]:
            if not target or not re.fullmatch(r'[A-Za-z0-9_@.:-]+',target) or target.startswith('-'):
                raise ValueError('Explicit valid Sigma/root and VPS/sudo SSH targets required')
        ipaddress.IPv4Address(args.vps_ipv4)
        if not 1 <= args.vps_ssh_port <= 65535: raise ValueError('Invalid VPS SSH port')
        for value in [args.radio,args.phy,args.vps_interface]:
            if not re.fullmatch(r'[A-Za-z0-9_.-]{1,15}',value): raise ValueError('Invalid interface/phy')
        if args.radio=='lo' or args.vps_interface=='lo': raise ValueError('Cannot use loopback')
        P.mkdir(mode=0o700,parents=True,exist_ok=False)
        SETTINGS={k:getattr(args,k) for k in ['sigma_ssh','vps_ssh','vps_ipv4','vps_ssh_port','radio','phy','vps_interface']}
        SETTINGS['known_hosts']=str(args.known_hosts.resolve()) if args.known_hosts else None
        (P/'settings.json').write_text(json.dumps(SETTINGS,indent=2))
    else:
        if P.stat().st_mode & 0o077: raise ValueError('Output directory must be private')
        SETTINGS=json.loads((P/'settings.json').read_text())
    base=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=8']
    if SETTINGS['known_hosts']: base += ['-o','UserKnownHostsFile='+SETTINGS['known_hosts']]
    SIGMA=base+[SETTINGS['sigma_ssh'],'sh -s']
    VPS=base+['-p',str(SETTINGS['vps_ssh_port']),SETTINGS['vps_ssh'],'sudo -n sh -s']
    if args.command=='prepare': prepare()
    elif args.command=='start-ap': ap()
    elif args.command=='stop': stop()
    else:
        print('Sigma:',call(SIGMA,'ip netns list; systemctl is-active wave-qa6-hostapd wave-qa6-dnsmasq || true'))
        print('VPS:',call(VPS,'test -d /sys/class/net/waveqa6 && echo tunnel-present; systemctl list-timers wave-qa6-expiry.timer --no-pager'))


if __name__=='__main__':
    try: main()
    except (ValueError,RuntimeError,OSError,subprocess.TimeoutExpired) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
