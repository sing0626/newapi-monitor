#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { printf '%s\n' 'Simulator tests require macOS and Xcode.' >&2; exit 1; }
xcodegen generate --spec "$ROOT/project.yml" --project "$ROOT"
DEVICE="$(xcrun simctl list devices available -j | python3 -c '
import json, sys
all_devices = json.load(sys.stdin)["devices"]
for runtime, devices in sorted(all_devices.items(), reverse=True):
    if "iOS" not in runtime:
        continue
    for device in devices:
        if device.get("isAvailable") and device["name"].startswith("iPhone"):
            print(device["udid"])
            sys.exit(0)
sys.exit("No available iPhone simulator; install an iOS runtime in Xcode Settings.")
')"
mkdir -p "$ROOT/build"
RESULT="$(mktemp -d "$ROOT/build/tests.XXXXXX")"
xcodebuild test \
  -project "$ROOT/NewAPIMonitor.xcodeproj" \
  -scheme NewAPIMonitor \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$DEVICE" \
  -derivedDataPath "$ROOT/build/TestDerivedData" \
  -resultBundlePath "$RESULT/Tests.xcresult" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=''
