#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
LOG=activity-smoke.log
: > "$LOG"

wait_for_device() {
  adb wait-for-device
  for _ in $(seq 1 120); do
    if adb shell getprop sys.boot_completed 2>/dev/null | grep -q '^1$'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

echo "waiting for emulator boot..." | tee -a "$LOG"
wait_for_device | tee -a "$LOG"
adb shell input keyevent 82 >/dev/null 2>&1 || true
sleep 20

for attempt in 1 2 3 4 5 6 7 8; do
  adb reconnect offline >/dev/null 2>&1 || true
  adb devices | tee -a "$LOG"
  if OUT=$(adb install -r -g "$APK" 2>&1); then
    echo "$OUT" | tee -a "$LOG"
    break
  fi
  echo "$OUT" | tee -a "$LOG"
  echo "install attempt $attempt failed; retrying..." | tee -a "$LOG"
  sleep 20
  if [[ $attempt -eq 8 ]]; then
    echo "APK install failed after retries" | tee -a "$LOG"
    exit 1
  fi
done

bash scripts/emulator-activity-smoke.sh | tee -a "$LOG"