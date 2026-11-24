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

dump() {
  adb shell uiautomator dump --windows /sdcard/ui.xml >/dev/null 2>&1 || adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | sed 's/>/>\n/g' > /tmp/ui.xml
}
has() { grep -qF "$1" /tmp/ui.xml; }
tap_text() {
  local want="$1" b
  dump
  b=$(grep -F "text=\"$want" /tmp/ui.xml | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ')
  if [ -z "$b" ]; then echo "NOT FOUND: $want"; return 1; fi
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  echo "tapped: $want"
  sleep 2
}

dump
echo "=== screen texts"; grep -o 'text="[^"]*"' /tmp/ui.xml | grep -v 'text=""' | head -40
has "Reply with Claude" && ok "sheet title visible" || bad "sheet title not visible"
has "Choose a tone" && ok "tone starts on Choose a tone (no default)" || bad "tone button text wrong"
has "Write a new message" && ok "Write a new message option present" || bad "compose option missing"

tap_text "Generate replies"; dump
has "Choose a tone for this chat first" && ok "Generate asks for a tone first" || bad "no tone prompt after Generate"

tap_text "Choose a tone"; dump
has "Informational" && ok "Informational tone listed" || bad "Informational missing"
has "Spiritual" && ok "Spiritual tone listed" || bad "Spiritual missing"
has "Professional" && ok "Professional tone listed" || bad "Professional missing"

tap_text "Spiritual"; dump
has "Spiritual" && ok "Spiritual selected" || bad "Spiritual not selected"

tap_text "Write a new message"
tap_text "Generate replies"; dump
has "Add your Claude API key" && ok "missing API key is explained" || bad "no API key message"

echo "=== final log"
adb logcat -d | grep -E "AIReply|FATAL EXCEPTION|AndroidRuntime" | tail -30
echo "failures: $FAILS"
[ "$FAILS" -eq 0 ]
