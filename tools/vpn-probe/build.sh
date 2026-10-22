#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
probe_source=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
probe_output=${1:?Usage: build.sh /absolute/private/output-directory}
case "$probe_output" in /*) ;; *) echo 'Use an absolute output directory.' >&2; exit 2 ;; esac
probe_sdk=${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}
probe_platform="$probe_sdk/platforms/android-37.0/android.jar"
probe_tools="$probe_sdk/build-tools/37.0.0"
mkdir -p "$probe_output/classes" "$probe_output/dex"
chmod 700 "$probe_output"
probe_key="$probe_output/probe-debug.p12"
if [[ ! -f "$probe_key" ]]; then
  keytool -genkeypair -keystore "$probe_key" -storetype PKCS12 -storepass android \
    -keypass android -alias probe -keyalg RSA -keysize 2048 -validity 365 \
    -dname 'CN=Local VPN QA probe' >/dev/null 2>&1
  chmod 600 "$probe_key"
fi
javac -source 8 -target 8 -classpath "$probe_platform" -d "$probe_output/classes" \
  "$probe_source/Probe.java" "$probe_source/KeepAlive.java"
"$probe_tools/d8" --min-api 26 --lib "$probe_platform" --output "$probe_output/dex" \
  "$probe_output/classes/me/vwave/vpnprobe/"*.class
"$probe_tools/aapt2" link -I "$probe_platform" --manifest "$probe_source/AndroidManifest.xml" \
  -o "$probe_output/probe.apk"
(cd "$probe_output/dex" && zip -q "$probe_output/probe.apk" classes.dex)
"$probe_tools/apksigner" sign --ks "$probe_key" --ks-pass pass:android --ks-key-alias probe \
  "$probe_output/probe.apk"
mkdir -p "$probe_output/driver-classes" "$probe_output/driver-dex"
javac -source 8 -target 8 -classpath "$probe_platform" -d "$probe_output/driver-classes" "$probe_source/Driver.java"
"$probe_tools/d8" --min-api 26 --lib "$probe_platform" --output "$probe_output/driver-dex" \
  "$probe_output/driver-classes/me/vwave/vpnprobe/driver/"*.class
"$probe_tools/aapt2" link -I "$probe_platform" --manifest "$probe_source/DriverManifest.xml" -o "$probe_output/driver.apk"
(cd "$probe_output/driver-dex" && zip -q "$probe_output/driver.apk" classes.dex)
"$probe_tools/apksigner" sign --ks "$probe_key" --ks-pass pass:android --ks-key-alias probe "$probe_output/driver.apk"
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  case "$(uname -s)" in
    Darwin) probe_host=darwin-x86_64 ;;
    Linux) probe_host=linux-x86_64 ;;
    *) echo 'Skipping optional capture binary: unsupported NDK host.' >&2; exit 0 ;;
  esac
  case "${PROBE_CAPTURE_ABI:-arm64-v8a}" in
    arm64-v8a) probe_target=aarch64-linux-android26 ;;
    x86_64) probe_target=x86_64-linux-android26 ;;
    *) echo 'Unsupported capture ABI (use arm64-v8a or x86_64).' >&2; exit 2 ;;
  esac
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$probe_host/bin/$probe_target-clang" \
    -O2 -Wall -Wextra -Werror "$probe_source/capture.c" -o "$probe_output/capture"
fi
printf 'Built probe APK in %s\n' "$probe_output"
