#!/usr/bin/env bash
# Build, install and drive the trackpad on a device connected over adb.
#
#   scripts/dev.sh install   build and install the debug APK
#   scripts/dev.sh enable    switch the accessibility service on (keeps other services enabled)
#   scripts/dev.sh disable   switch it off again
#   scripts/dev.sh open      open the settings screen
#   scripts/dev.sh logs      follow the app's log
#   scripts/dev.sh info      print the device facts that matter for gesture injection
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=dev.tablettrackpad
SERVICE=$PKG/$PKG.service.TrackpadService

# The system Gradle avoids the wrapper downloading its own copy.
gradle_cmd() {
    if command -v gradle >/dev/null; then gradle "$@"; else ./gradlew "$@"; fi
}

enabled_services() {
    local current
    current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
    [[ $current == null ]] && current=
    echo "$current"
}

case "${1:-}" in
    install)
        gradle_cmd :app:installDebug
        ;;
    enable)
        current=$(enabled_services)
        if [[ ":$current:" != *":$SERVICE:"* ]]; then
            adb shell settings put secure enabled_accessibility_services "${current:+$current:}$SERVICE"
        fi
        adb shell settings put secure accessibility_enabled 1
        ;;
    disable)
        current=$(enabled_services)
        remaining=$(tr ':' '\n' <<<"$current" | grep -vx "$SERVICE" | paste -sd: || true)
        adb shell settings put secure enabled_accessibility_services "${remaining:-\"\"}"
        ;;
    open)
        adb shell am start -n $PKG/.settings.SettingsActivity
        ;;
    logs)
        adb logcat -v time -s TabletTrackpad:V AccessibilityManagerService:W InputDispatcher:W
        ;;
    info)
        adb shell getprop ro.build.version.release
        adb shell getprop ro.build.version.sdk
        adb shell getprop ro.build.version.oneui 2>/dev/null || true
        adb shell getprop ro.build.display.id
        adb shell wm size
        adb shell wm density
        echo "--- input flags"
        adb shell aflags list 2>/dev/null | grep -iE 'multi_device|input\.flags' | head -40 || true
        ;;
    *)
        sed -n '2,10p' "$0"
        exit 1
        ;;
esac
