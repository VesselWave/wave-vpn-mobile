#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Runs the forum UI/emulator checks (report items 3 and 4) on one emulator.

Installs only the isolated me.vwave.vpn.qa build, which carries a synthetic offline session:
no AirVPN account, credentials or network request. The signed-in me.vwave.vpn.dev and
release installs are never touched. See docs/qa/FORUM-UI-2026-11-07.md.

Runs the positive controls and the forum contracts (fixed after v0.1.4; they must pass).
Exit status: 0 all executed tests passed; 1 a forum contract failed (a regression of
items 3/4 or the Cancel accessibility fix); 2 a positive control or the harness failed, so
contract results are not meaningful; 3 setup refused (device busy, wrong target).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
QA_PACKAGE = "me.vwave.vpn.qa"
TEST_PACKAGE = QA_PACKAGE + ".test"
RUNNER = f"{TEST_PACKAGE}/me.vwave.vpn.qa.ForumQaRunner"
CONTROLS = "me.vwave.vpn.qa.ForumUiControlsTest"
CONTRACTS = "me.vwave.vpn.qa.ForumUiContractTest"
EVIDENCE = f"/sdcard/Android/data/{QA_PACKAGE}/files/forum-ui"
EXPECTED = {
    CONTROLS: {"includeRoutesOnlyTheSelectedApp", "bypassExcludesOnlyTheSelectedApp",
               "allAppsRoutesEveryApp", "splitTunnelingScreenDrivesSettings"},
    CONTRACTS: {"forum3_failedStartMustNotHideABlockingInterface",
                "forum4_onlySelectedWithNoAppsMustNotTunnelEveryApp",
                "forum4_emptyOnlySelectedTearsDownARunningTunnel",
                "forum4_onlySelectedWithNoAppsMustExplainTheEffect", "busyCancelIsExposedToAccessibility"},
}
STATUS = {"0": "passed", "-1": "error", "-2": "failed", "-3": "ignored", "-4": "skipped"}


def parse_instrumentation(text: str) -> tuple[list[dict], dict]:
    """Parses `am instrument -r` raw output into per-test results and the final summary."""
    results: list[dict] = []
    current: dict[str, str] = {}
    key = None
    summary: dict[str, str] = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.split(": ", 1)[1].strip()
            if code != "1" and "test" in current:
                results.append({
                    "class": current.get("class", ""), "test": current["test"],
                    "outcome": STATUS.get(code, f"code {code}"),
                    "message": (current.get("stack") or "").strip(),
                })
            current, key = {}, None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            k, _, v = line[len("INSTRUMENTATION_RESULT: "):].partition("=")
            summary[k] = v
            key = None
        elif line.startswith("INSTRUMENTATION_CODE: "):
            summary["code"] = line.split(": ", 1)[1].strip()
        elif key is not None:
            current[key] += "\n" + line
    return results, summary


def verdict(results: list[dict], summary: dict, contracts: bool = False,
            expected: set[tuple[str, str]] | None = None) -> int:
    """0 pass, 1 contract failure, 2 harness/control failure or anything unexecuted."""
    if expected is not None:
        actual = [(r["class"], r.get("test")) for r in results]
        if set(actual) != expected or len(actual) != len(expected) or summary.get("code") != "-1":
            return 2  # Partial output, duplicate results or missing final completion is not qualification.
    if not results or "shortMsg" in summary:
        return 2
    if any(r["outcome"] not in ("passed", "failed") for r in results):
        return 2  # A skipped/ignored/errored check must never count as evidence.
    if contracts and not any(r["class"] == CONTRACTS for r in results):
        return 2
    bad = [r for r in results if r["outcome"] == "failed"]
    if any(r["class"] != CONTRACTS for r in bad):
        return 2
    return 1 if bad else 0


def adb(serial: str, *args: str, check: bool = True, capture: bool = True) -> str:
    done = subprocess.run(["adb", "-s", serial, *args], text=True, capture_output=capture, check=False)
    if check and done.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {done.stderr or done.stdout}")
    return done.stdout or ""


def other_vpn_owner(serial: str) -> str | None:
    dump = adb(serial, "shell", "dumpsys", "connectivity")
    for line in dump.splitlines():
        if line.strip().startswith("NetworkAgentInfo") and re.search(r"Transports: [A-Z_|]*VPN", line):
            owner = re.search(r"OwnerUid: (\d+)", line)
            return owner.group(1) if owner else "unknown"
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True, help="adb serial, e.g. emulator-5556")
    ap.add_argument("--contracts", action="store_true", help="accepted for compatibility; contracts always run")
    ap.add_argument("--only", choices=["controls", "contracts"], help="run one class")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--out", type=pathlib.Path, default=ROOT / "build" / "forum-ui")
    ap.add_argument("--allow-physical", action="store_true", help="permit a non-emulator serial")
    ap.add_argument("--force", action="store_true", help="run even if another app's VPN is active (it will be revoked)")
    ap.add_argument("--uninstall", action="store_true", help="remove the qa app and test APK afterwards")
    a = ap.parse_args()

    if not a.serial.startswith("emulator-") and not a.allow_physical:
        print("refusing a non-emulator serial without --allow-physical", file=sys.stderr)
        return 3
    owner = other_vpn_owner(a.serial)
    qa_uid = adb(a.serial, "shell", "cmd", "package", "list", "packages", "-U", QA_PACKAGE, check=False)
    if owner and f"uid:{owner}" not in qa_uid and not a.force:
        print(f"another app (uid {owner}) owns an active VPN; starting the qa tunnel would revoke it. "
              "Disconnect it, use another emulator, or pass --force.", file=sys.stderr)
        return 3
    if not a.no_build:
        subprocess.run([str(ROOT / "gradlew"), "--no-configuration-cache", "-q",
                        ":app:assembleQa", ":app:assembleQaAndroidTest"], cwd=ROOT, check=True)
    apks = [ROOT / "app/build/outputs/apk/qa/app-qa.apk",
            ROOT / "app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk"]
    for apk in apks:
        adb(a.serial, "install", "-r", "-t", str(apk))
    adb(a.serial, "shell", "rm", "-rf", EVIDENCE, check=False)

    classes = {"controls": CONTROLS, "contracts": CONTRACTS}
    selected = [classes[a.only]] if a.only else [CONTROLS, CONTRACTS]
    cmd = ["shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(selected)]
    started = time.time()
    raw = adb(a.serial, *cmd, RUNNER, check=False)
    results, summary = parse_instrumentation(raw)

    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "instrumentation.txt").write_text(raw)
    adb(a.serial, "pull", EVIDENCE, str(a.out), check=False)
    expected = {(cls, name) for cls in selected for name in EXPECTED[cls]}
    status = verdict(results, summary, contracts=CONTRACTS in selected, expected=expected)
    report = {
        "source": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "working_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)),
        "apk_sha256": {apk.name: hashlib.sha256(apk.read_bytes()).hexdigest() for apk in apks},
        "api": adb(a.serial, "shell", "getprop", "ro.build.version.sdk").strip(),
        "serial": a.serial, "package": QA_PACKAGE, "contracts": CONTRACTS in selected,
        "seconds": round(time.time() - started, 1), "exit": status,
        "meaning": {0: "all passed", 1: "forum contract failure (regression)", 2: "control/harness failure"}[status],
        "results": results, "summary": summary, "evidence": str(a.out / "forum-ui"),
    }
    (a.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    for r in results:
        first = r["message"].splitlines()[0] if r["message"] else ""
        print(f"{r['outcome']:8} {r['class'].rsplit('.', 1)[-1]}.{r['test']}  {first[:220]}")
    print(f"exit {status}: {report['meaning']}; report {a.out / 'report.json'}")
    if a.uninstall:
        adb(a.serial, "uninstall", TEST_PACKAGE, check=False)
        adb(a.serial, "uninstall", QA_PACKAGE, check=False)
    return status


if __name__ == "__main__":
    sys.exit(main())
