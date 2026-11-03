#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Repeat device profiles sequentially, preserving every capture and failure."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys

from run import Device, Failure, HERE, SCENARIOS, load_config, private_directory, require, save


def launch(argv):
    # Keep Ctrl-C from killing a child in the middle of its finally/cleanup.
    process = subprocess.Popen(argv, start_new_session=True)
    interrupted = False
    def stop(*_):
        nonlocal interrupted
        if not interrupted:
            interrupted = True
            if process.poll() is None:
                process.send_signal(signal.SIGTERM)
    previous = {kind: signal.signal(kind, stop) for kind in (signal.SIGINT, signal.SIGTERM)}
    try:
        code = process.wait()
        return 130 if interrupted and code != 3 else code
    finally:
        for kind, handler in previous.items():
            signal.signal(kind, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--profiles', required=True, help='comma-separated profiles in comparison order')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--scenarios', default='steady,disconnect,reconnect,crash,force-stop')
    parser.add_argument('--allow-root', action='store_true')
    args = parser.parse_args()
    require(1 <= args.repetitions <= 100, 'Use 1–100 repetitions')
    scenarios = args.scenarios.split(',')
    require(all(name in SCENARIOS for name in scenarios) and len(scenarios) == len(set(scenarios)),
            'Use distinct valid scenarios; repeat with --repetitions')
    profiles = args.profiles.split(',')
    require(all(re.fullmatch(r'[A-Za-z0-9_-]+', profile) for profile in profiles), 'Invalid profile name')
    require(len(set(profiles)) == len(profiles), 'Profiles must be distinct')
    configs = {profile: load_config(args.config, profile) for profile in profiles}
    require(all(c.get('capture', {}).get('kind') in ('gateway', 'emulator') for c in configs.values()),
            'Leak matrix requires capture for every profile')
    devices = {}
    for config in configs.values():
        devices.setdefault(json.dumps(config['adb'], sort_keys=True), []).append(config)
    for targets in devices.values():
        if len({target['package'] for target in targets}) > 1:
            require(Device(targets[0]).setting('secure', 'always_on_vpn_app') == 'null',
                    'Multi-VPN matrices require an initially disabled owner-profile Always-on policy; save and disable it in Android Settings first')
    output = private_directory(args.output)
    require(not any(output.iterdir()), 'Matrix requires a new output directory')
    private = output / 'private'
    private.mkdir(mode=0o700)
    frozen_config = private / 'config.json'
    save(frozen_config, {'version': 1, 'profiles': configs})
    source = private / 'runner'
    shutil.copytree(HERE, source, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    hashes = {str(path.relative_to(source)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in sorted(source.rglob('*')) if path.is_file()}
    save(output / 'runner-sha256.json', hashes)
    results = []
    for repetition in range(args.repetitions):
        # Rotate the starting target; do not always give one app the first slot.
        order = profiles[repetition % len(profiles):] + profiles[:repetition % len(profiles)]
        for profile in order:
            destination = output / ('round-' + str(repetition + 1) + '-' + profile)
            script = 'compare.py' if configs[profile].get('comparison_ui') else 'run.py'
            command = [sys.executable, str(source / script), 'run', '--config', str(frozen_config),
                       '--profile', profile, '--output', str(destination), '--scenarios', args.scenarios, '--keep-app']
            if args.allow_root:
                command.append('--allow-root')
            code = launch(command)
            results.append({'round': repetition + 1, 'profile': profile, 'exit_code': code,
                            'report': str(destination / 'report.json')})
            save(output / 'matrix.json', {'runs': results, 'complete': False})
            state_path = destination / 'private' / 'state.json'
            state = json.loads(state_path.read_text()) if state_path.exists() else {}
            # Never start another app while a prior run needs restoration.
            if code not in (0, 1) or state.get('pending_cleanup', True):
                print('Matrix stopped: incomplete run or cleanup; inspect the last private state.')
                return 3 if state.get('pending_cleanup', True) else code
    save(output / 'matrix.json', {'runs': results, 'complete': True})
    return 1 if any(result['exit_code'] for result in results) else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
