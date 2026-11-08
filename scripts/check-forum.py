#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline regression checks for Staff comment 268266; failed contracts exit 1.

No account, emulator, socket, signing identity, or external service is required by
these tests. Gradle may download build dependencies. UI/device checks have their
own runner; see docs/qa/FORUM-UI-2026-11-07.md.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESULTS = (ROOT / 'core/build/test-results/test', ROOT / 'app/build/test-results/testDebugUnitTest')


def collect(paths):
    cases = []
    for path in paths:
        root = ET.parse(path).getroot()
        for case in root.findall('testcase'):
            failure = case.find('failure')
            error = case.find('error')
            problem = failure if failure is not None else error
            status = 'failed' if problem is not None else 'skipped' if case.find('skipped') is not None else 'passed'
            cases.append({'id': case.get('classname', '') + '.' + case.get('name', ''), 'status': status,
                          'detail': problem.get('message', '').split('\n')[0][:500] if problem is not None else ''})
    return cases


def outcome(cases, returncode):
    # Missing/skipped cases and build failures cannot be interpreted as successful qualification.
    if not cases or any(c['status'] == 'skipped' for c in cases):
        return 2
    if returncode and not any(c['status'] == 'failed' for c in cases):
        return 2
    return 1 if any(c['status'] == 'failed' for c in cases) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, default=ROOT / 'build/reports/forum/contracts.json')
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Delete only this suite's old results, so a compile failure cannot reuse a previous pass.
    for directory in RESULTS:
        for path in directory.glob('TEST-*Forum*ContractTest.xml'):
            path.unlink()
    command = [str(ROOT / 'gradlew'), '--no-daemon', '--continue', ':core:test', ':app:testDebugUnitTest',
               '--tests', '*Forum*ContractTest', '--rerun-tasks']
    log = args.report.with_suffix('.log')
    with log.open('w') as output:
        run = subprocess.run(command, cwd=ROOT, env=os.environ.copy(),
                             stdout=output, stderr=subprocess.STDOUT)
    cases = collect(sorted(p for directory in RESULTS for p in directory.glob('TEST-*Forum*ContractTest.xml')))
    required = {'ForumManifestContractTest', 'ForumManifestControlsContractTest',
                'ForumTimeoutContractTest', 'ForumRecoveryContractTest'}
    present = {case['id'].split('.')[-2] for case in cases}
    missing = sorted(required - present)
    code = 2 if missing else outcome(cases, run.returncode)
    report = {'schema': 1, 'scope': 'offline production protocol and recovery policy; no device qualification',
              'source': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
              'working_tree_dirty': bool(subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain'], text=True)),
              'exit_code': code, 'gradle_exit_code': run.returncode, 'missing_suites': missing,
              'counts': {state: sum(c['status'] == state for c in cases) for state in ('passed', 'failed', 'skipped')},
              'cases': cases}
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ('scope', 'counts', 'exit_code')}))
    print(f'Report: {args.report}\nBuild log: {log}')
    return code


if __name__ == '__main__':
    sys.exit(main())
