#!/bin/bash
# Real keyboard test on an emulator: enable the IME, tap the on-screen keys, open the AI panel.
mkdir -p out
adb install -r app/build/outputs/apk/debug/app-debug.apk
IME=com.aireply.keyboard/.AiKeyboardService
sleep 10
adb shell ime list -a -s | sed "s/^/RESULT imelist: /"
adb shell pm query-services -a android.view.InputMethod | sed "s/^/RESULT services: /" | head -20
adb shell dumpsys package com.aireply.keyboard | grep -iE "BIND_INPUT|android.view.InputMethod|AiKeyboardService" | head -8 | sed "s/^/RESULT pkg: /"
adb shell ime enable $IME
adb shell ime set $IME
echo "RESULT default ime: $(adb shell settings get secure default_input_method)"
adb logcat -c
adb shell am start -n com.aireply.keyboard/.MainActivity
sleep 5
adb shell input swipe 500 1800 500 400 300
sleep 1
adb shell input swipe 500 1800 500 400 300
sleep 1
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml out/ui0.xml
python3 - <<'PY'
import re
x = open('out/ui0.xml', encoding='utf-8').read()
m = re.search(r'text="Tap here and type"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x) or re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="Tap here and type"', x)
if m:
    a = [int(v) for v in m.groups()]
    open('out/box.txt', 'w').write('%d %d' % ((a[0] + a[2]) // 2, (a[1] + a[3]) // 2))
else:
    open('out/box.txt', 'w').write('540 1500')
PY
read BX BY < out/box.txt
echo "RESULT tapping box at $BX $BY"
adb shell input tap $BX $BY
sleep 3
adb shell dumpsys input_method | grep -E "mInputShown|mCurId" | head -5 | sed 's/^/RESULT ime: /'
adb exec-out screencap -p > out/shot1.png
DENS=$(adb shell wm density | grep -oE '[0-9]+' | tail -1)
echo "RESULT density $DENS"
pip install --break-system-packages pillow >/dev/null 2>&1 || pip install pillow >/dev/null 2>&1
python3 - "$DENS" <<'PY'
import sys
from PIL import Image
d = int(sys.argv[1]) / 160.0
im = Image.open('out/shot1.png').convert('RGB')
W, H = im.size
def bg(p): return abs(p[0] - 233) < 6 and abs(p[1] - 234) < 6 and abs(p[2] - 238) < 6
y = H - 1
while y > 0 and not bg(im.getpixel((4, y))): y -= 1
rowH = 52 * d
top = y - 4 * rowH
pts = {}
pts['h'] = (0.6 * W, top + 1.5 * rowH)
pts['i'] = (0.75 * W, top + 0.5 * rowH)
pts['space'] = (0.5 * W, top + 3.5 * rowH)
pts['ai'] = (40 * d, top - 22 * d)
with open('out/pts.txt', 'w') as f:
    for k, v in pts.items(): f.write('%s %d %d\n' % (k, v[0], v[1]))
print('RESULT keyboard bottom row', y, 'of', H, 'bg-found', y > H * 0.4)
PY
cat out/pts.txt
tap() { adb shell input tap $(grep "^$1 " out/pts.txt | awk '{print $2" "$3}'); sleep 1; }
tap h
tap i
tap space
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml out/ui1.xml
echo "RESULT typed: $(grep -o 'text="[^"]*"' out/ui1.xml | grep -E 'text="hi ?"' | head -2 | tr '\n' ' ')"
adb exec-out screencap -p > out/shot2.png
tap ai
sleep 2
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml out/ui2.xml
echo "RESULT ai panel: $(grep -o 'text="[^"]*AI type[^"]*"' out/ui2.xml | head -1) $(grep -o 'text="Generate"' out/ui2.xml | head -1)"
adb exec-out screencap -p > out/shot3.png
adb logcat -d > out/logcat.txt
echo "RESULT fatal: $(grep -c 'FATAL EXCEPTION' out/logcat.txt)"
grep -A12 'FATAL EXCEPTION' out/logcat.txt | head -40
exit 0
