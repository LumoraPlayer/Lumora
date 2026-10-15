#!/usr/bin/env bash
# Verification helper: wake the TV, open the Sports tab, screenshot. (not part of the app)
set -u
DEV=192.168.0.163:5555
SHOTS=/root/Lumora/.slim/shots
mkdir -p "$SHOTS"

adb -s "$DEV" shell input keyevent KEYCODE_WAKEUP
sleep 1
adb -s "$DEV" shell am start -n com.lumora.debug/com.lumora.MainActivity > /dev/null
sleep 6
adb -s "$DEV" exec-out screencap -p > "$SHOTS/$1.png"
ls -la "$SHOTS/$1.png"
