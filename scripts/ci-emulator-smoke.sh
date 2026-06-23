#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
sleep 15
for attempt in 1 2 3 4 5; do
  if adb install -r -g "$APK"; then
    break
  fi
  echo "install attempt $attempt failed; retrying..."
  sleep 15
  if [[ $attempt -eq 5 ]]; then
    echo "APK install failed after retries"
    exit 1
  fi
done
bash scripts/emulator-activity-smoke.sh | tee activity-smoke.log