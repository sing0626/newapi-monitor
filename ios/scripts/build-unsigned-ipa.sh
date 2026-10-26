#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$(uname -s)" != "Darwin" ]]; then
  printf '%s\n' 'A macOS host with Xcode is required. Use the iOS GitHub Actions job from Windows.' >&2
  exit 1
fi
for tool in xcodegen xcodebuild ditto python3; do
  command -v "$tool" >/dev/null || { printf 'Missing tool: %s\n' "$tool" >&2; exit 1; }
done

xcodegen generate --spec "$ROOT/project.yml" --project "$ROOT"
mkdir -p "$ROOT/build"
STAGE="$(mktemp -d "$ROOT/build/package.XXXXXX")"
# Keep each build isolated so a failed build cannot package a previous .app.
xcodebuild build \
  -project "$ROOT/NewAPIMonitor.xcodeproj" \
  -scheme NewAPIMonitor \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -sdk iphoneos \
  -derivedDataPath "$STAGE/DerivedData" \
  CODE_SIGN_IDENTITY='' CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO

APP="$STAGE/DerivedData/Build/Products/Release-iphoneos/NewAPIMonitor.app"
[[ -d "$APP" && -f "$APP/Info.plist" ]] || { printf '%s\n' 'Device .app is missing.' >&2; exit 1; }
EXECUTABLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Info.plist")"
[[ -f "$APP/$EXECUTABLE" ]] || { printf '%s\n' 'App executable is missing.' >&2; exit 1; }
xcrun lipo -verify_arch arm64 "$APP/$EXECUTABLE"
mkdir "$STAGE/Payload"
ditto "$APP" "$STAGE/Payload/NewAPIMonitor.app"
ditto -c -k --keepParent "$STAGE/Payload" "$STAGE/NewAPIMonitor-unsigned.ipa"
python3 "$ROOT/scripts/validate-ipa.py" "$STAGE/NewAPIMonitor-unsigned.ipa"
printf '\nUnsigned IPA: %s\n' "$STAGE/NewAPIMonitor-unsigned.ipa"
printf '%s\n' 'This IPA needs personal signing before installation on a stock iPhone.'
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'ipa_path=%s\n' "$STAGE/NewAPIMonitor-unsigned.ipa" >> "$GITHUB_OUTPUT"
fi
