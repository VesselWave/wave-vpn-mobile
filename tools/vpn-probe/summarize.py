# SPDX-License-Identifier: GPL-3.0-or-later
"""Strict Ethernet-PCAP reader for the independent QA probes; no hostname export."""
import collections
import hashlib
import json
import socket
import struct
import sys
from pathlib import Path


def packets(path):
    data = Path(path).read_bytes()
    formats = {b'\xd4\xc3\xb2\xa1': ('<', 1e6), b'\xa1\xb2\xc3\xd4': ('>', 1e6),
               b'\x4d\x3c\xb2\xa1': ('<', 1e9), b'\xa1\xb2\x3c\x4d': ('>', 1e9)}
    if len(data) < 24 or data[:4] not in formats:
        raise ValueError('Invalid or truncated PCAP header')
    endian, scale = formats[data[:4]]
    major, minor, _, _, snaplen, link = struct.unpack_from(endian + 'HHIIII', data, 4)
    if (major, minor) != (2, 4) or link != 1 or not snaplen:
        raise ValueError('Expected Ethernet PCAP version 2.4')
    offset = 24
    while offset < len(data):
        if offset + 16 > len(data):
            raise ValueError('Truncated PCAP record header')
        sec, fraction, size, original = struct.unpack_from(endian + 'IIII', data, offset)
        offset += 16
        if size > snaplen or size > original or offset + size > len(data):
            raise ValueError('Invalid or truncated PCAP packet')
        frame = data[offset:offset + size]
        offset += size
        result = {'time': sec + fraction / scale, 'network_unparsed': False}
        if len(frame) < 14:
            yield result
            continue
        kind = struct.unpack_from('!H', frame, 12)[0]
        pos = 14
        if kind == 0x800 and len(frame) >= pos + 20:
            ihl = (frame[pos] & 15) * 4
            if ihl < 20 or len(frame) < pos + ihl or struct.unpack_from('!H', frame, pos + 6)[0] & 0x3fff:
                result['network_unparsed'] = True
                yield result
                continue
            proto = frame[pos + 9]
            src = socket.inet_ntop(socket.AF_INET, frame[pos + 12:pos + 16])
            dst = socket.inet_ntop(socket.AF_INET, frame[pos + 16:pos + 20])
            pos += ihl
            family = '4'
        elif kind == 0x86dd and len(frame) >= pos + 40:
            proto = frame[pos + 6]
            src = socket.inet_ntop(socket.AF_INET6, frame[pos + 8:pos + 24])
            dst = socket.inet_ntop(socket.AF_INET6, frame[pos + 24:pos + 40])
            pos += 40
            family = '6'
            if proto in (0, 43, 44, 50, 51, 60):
                result['network_unparsed'] = True
                yield result
                continue
        else:
            yield result
            continue
        if proto not in (6, 17) or len(frame) < pos + (20 if proto == 6 else 8):
            yield result
            continue
        sport, dport = struct.unpack_from('!HH', frame, pos)
        result.update(src=src, dst=dst, family=family, proto=proto, sport=sport, dport=dport,
                      payload=frame[pos + 8:] if proto == 17 else b'',
                      syn=proto == 6 and bool(frame[pos + 13] & 2) and not bool(frame[pos + 13] & 16))
        yield result


def summarize(path, wireguard_port=1637, client_addresses=None):
    counts = collections.Counter()
    start = end = None
    clients = set(client_addresses or [])
    for packet in packets(path):
        start = packet['time'] if start is None else start
        end = packet['time']
        counts['packets'] += 1
        if packet['network_unparsed']:
            counts['unsupported_network_packets'] += 1
        if 'dport' not in packet or (clients and packet['src'] not in clients):
            continue
        family, port = packet['family'], packet['dport']
        if packet['proto'] == 17:
            if port == wireguard_port:
                counts['wireguard_out'] += 1
            if port == 53:
                counts['all_dns' + family + '_out'] += 1
                if b'waveqa-20261022' in packet['payload']:
                    counts['raw_dns' + family + '_out'] += 1
                if b'probe-' in packet['payload'] and b'sslip' in packet['payload']:
                    counts['system_dns' + family + '_out'] += 1
        if packet['syn']:
            if port == 443:
                counts['all_https' + family + '_syn'] += 1
            if port == 53:
                counts['all_tcp_dns' + family + '_syn'] += 1
            if port == 853:
                counts['all_dot' + family + '_syn'] += 1
            if packet['dst'] in ('1.1.1.1', '2606:4700:4700::1111'):
                if port == 443:
                    counts['direct_https' + family + '_syn'] += 1
                if port == 853:
                    counts['dot' + family + '_syn'] += 1
    data = Path(path).read_bytes()
    return {'capture': Path(path).name, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
            'first_packet_unix': start, 'last_packet_unix': end,
            'observed_span_seconds': round(end - start, 3) if start is not None else 0,
            'counts': dict(counts)}


if __name__ == '__main__':
    print(json.dumps(summarize(sys.argv[1]), indent=2))
