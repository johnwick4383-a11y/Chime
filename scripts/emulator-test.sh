#!/usr/bin/env bash
# Emulator smoke test for AI Reply. Runs inside an Android emulator on GitHub Actions.
# It enables the accessibility service with adb (this skips the "restricted settings" prompt that
# real phones show), taps the floating bubble and checks what appears on screen.
set -u
PKG=com.aireply.keyboard
SVC="$PKG/$PKG.ReplyService"
FAILS=0
ok()  { echo "PASS: $1"; }
bad() { echo "FAIL: $1"; FAILS=$((FAILS+1)); }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
sleep 8

echo "=== accessibility services (dumpsys)"
adb shell dumpsys accessibility | grep -i -E "aireply|Enabled services|Bound services" | head -20
echo "=== settings value"
adb shell settings get secure enabled_accessibility_services

adb shell am start -a android.settings.SETTINGS >/dev/null
sleep 5

echo "=== log after enabling"
adb logcat -d -s AIReply:D AndroidRuntime:E | tail -30
adb logcat -d -s AIReply:D | grep -q "bubble added" && ok "service started and bubble added" || bad "bubble was not added"

# find the bubble: right edge, one third down (see showBubble in ReplyService.kt)
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DENS=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
BS=$((52 * DENS / 160))
X=$((W - BS - 4 * DENS / 160 + BS / 2))
Y=$((H / 3 + BS / 2))
echo "screen ${W}x${H} density $DENS bubble tap at $X,$Y"
adb shell input tap $X $Y
sleep 3
adb logcat -d -s AIReply:D AndroidRuntime:E | tail -15
adb logcat -d -s AIReply:D | grep -q "sheet shown" && ok "sheet opened after tapping the bubble" || bad "sheet did not open"

# NOTE: screen-reading tools (uiautomator) switch accessibility services off while they run,
# so UI contents are not inspected here. These log checks cover the app's own steps.
adb logcat -d | grep -q "FATAL EXCEPTION" && bad "app crashed" || ok "no crash while opening the sheet"

echo "=== final log"
adb logcat -d | grep -E "AIReply|FATAL EXCEPTION|AndroidRuntime" | tail -30
echo "failures: $FAILS"
[ "$FAILS" -eq 0 ]
