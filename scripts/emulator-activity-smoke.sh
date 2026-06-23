#!/usr/bin/env bash
set -euo pipefail
PKG="com.pentest.dashboard"
ACTIVITIES=(
  MainActivity WizardActivity RFIDActivity NFCActivity SubGHzActivity InfraredActivity
  BadUSBActivity IButtonActivity GPIOActivity BluetoothActivity WiFiDeauthActivity
  ESP32ManagerActivity WiFiCaptureActivity OSINTActivity GoogleDorkActivity
  NetworkScannerActivity ExploitDatabaseActivity PacketSnifferActivity HashCrackerActivity
  PhoneSensorsActivity SettingsActivity ScriptBuilderActivity PayloadGeneratorActivity
  ARPPoisonerActivity CheatSheetActivity CreditCardReaderActivity MissionFlowActivity
)
echo "=== Pen15 activity smoke matrix ==="
FAIL=0
for act in "${ACTIVITIES[@]}"; do
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb logcat -c >/dev/null 2>&1 || true
  set +e
  OUT=$(adb shell am start -W -n "$PKG/.$act" 2>&1)
  RC=$?
  set -e
  sleep 2
  CRASH=$(adb logcat -d 2>/dev/null | grep -F "FATAL EXCEPTION" | grep -F "$PKG" || true)
  if [[ $RC -ne 0 ]]; then
    echo "FAIL $act (start rc=$RC) $OUT"
    FAIL=1
  elif [[ -n "$CRASH" ]]; then
    echo "FAIL $act (runtime crash)"
    echo "$CRASH" | tail -n 8
    FAIL=1
  else
    echo "OK   $act"
  fi
done
echo "=== done; failures=$FAIL ==="
exit "$FAIL"
