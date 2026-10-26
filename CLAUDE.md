# NewAPI Monitor — native app development instructions

Native Kotlin / Jetpack Compose Android lives under android/. Independent Swift / SwiftUI iOS lives under ios/ (see README-IOS.md for its narrower first-release scope). Verify the real filesystem before describing the project. The previous PWA is retired; do not recreate HTML, JavaScript, a service worker, or a web build.

## iOS build and test

- iOS 0.1.0 (build 1), iOS 17+, XcodeGen; no third-party Swift packages.
- On macOS with Xcode: bash ios/scripts/test-ios.sh, then bash ios/scripts/build-unsigned-ipa.sh.
- On Windows: python ios/scripts/check-project.py checks resources only; Swift compilation and XCTest require macOS. Never claim an IPA was built without a successful Xcode run.
- Generated Xcode projects, IPA files, and build directories are ignored. Unsigned IPA packaging needs no Apple account; installation on a stock device still requires signing.
- First release supports HTTPS-only sources, read-only token/channel lists and foreground alerts; do not imply Android feature parity or background refresh guarantees.

## Build and test

- Read README.md for the current version, capabilities and data limitations.
- The project has no Gradle wrapper. Never call ./gradlew.
- Load /d/vibeproject/android-tools/env.sh in every Git Bash invocation. It configures JDK 21, Android SDK and Gradle 8.13.
- Standard verification: cd android, then gradle testDebugUnitTest assembleDebug.
- On this Windows host, scripts/verify.ps1 also gives Java a long-form local TEMP/TMP path for this invocation, avoiding the AF_UNIX short-path issue.
- Do not run clean routinely. Do not claim a successful build unless Gradle reports BUILD SUCCESSFUL.
- Debug APK: android/app/build/outputs/apk/debug/app-debug.apk.
- Do not install on a phone unless the user requests it. The designated device is R5CY12YEKLB; launch component com.newapimonitor/.MainActivity.

## Implementation constraints

- API keys and tokens stay on the user's device and are sent only to the configured Base URL, never to third-party services.
- Keep demo mode completely offline and label simulated values.
- Keep credentials, active instance and in-flight requests isolated when switching sources. Cancel work on background / navigation.
- Do not substitute zero for failed or unknown financial data. Clearly label partial pagination and stale data.
- Streaming probes are explicit paid-capable actions, never automatic; do not retry them automatically or persist generated text.
- Backups omit credentials by default. Credential-bearing exports must use the authenticated BackupCipher envelope.
- Maintain versionName/versionCode and documentation together.
