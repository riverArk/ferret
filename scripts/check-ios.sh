#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
derived="$work/ferret-derived"
result="$work/ferret-ios.xcresult"
simulator='iPhone 17'

cd "$root"
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test --no-daemon
if [[ -e $result ]]; then
    echo "Remove the existing result bundle before re-running: $result" >&2
    exit 1
fi
xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
    -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' \
    -derivedDataPath "$derived" -resultBundlePath "$result" CODE_SIGNING_ALLOWED=NO
app="$derived/Build/Products/Debug-iphonesimulator/Ferret.app"
[[ -d $app ]] || { echo "Built Ferret.app is missing" >&2; exit 1; }
xcrun simctl bootstatus "$simulator" -b
xcrun simctl install "$simulator" "$app"
xcrun simctl launch "$simulator" io.riverark.ferret
xcrun simctl io "$simulator" screenshot "$work/ferret-ios-launch.png"
printf 'xcresult: %s\nscreenshot: %s\n' "$result" "$work/ferret-ios-launch.png"
