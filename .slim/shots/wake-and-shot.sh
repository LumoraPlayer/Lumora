#!/usr/bin/env bash
# Verification helper: reconnect over wifi adb, wake the TV, open the debug build, screenshot.
set -u
DEV=192.168.0.163:5555
SHOTS=/root/Lumora/.slim/shots
mkdir -p "$SHOTS"

for attempt in 1 2 3 4 5; do
  adb connect "$DEV" > /dev/null 2>&1
  state=$(adb devices | awk -v d="$DEV" '$1 == d { print $2 }')
  echo "attempt $attempt: ${state:-missing}"
  if [ "${state:-}" = "device" ]; then
    adb -s "$DEV" shell input keyevent KEYCODE_WAKEUP
    sleep 1
    adb -s "$DEV" shell am start -n com.lumora.debug/com.lumora.MainActivity > /dev/null
    sleep 7
    adb -s "$DEV" exec-out screencap -p > "$SHOTS/$1.png"
    ls -la "$SHOTS/$1.png"
    exit 0
  fi
  sleep 5
done
exit 1
