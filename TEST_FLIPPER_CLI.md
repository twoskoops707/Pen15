# Testing Flipper Zero CLI via USB

## Setup
1. Connect Flipper Zero via USB-C to Android phone
2. Flipper should show "USB Connected" screen
3. NO app needs to be running on Flipper
4. Android app connects to /dev/ttyACM0 (or /dev/ttyUSB0)

## Test Commands

### Test 1: Basic System Info
```bash
echo "help" > /dev/ttyACM0
# Should list available commands
```

### Test 2: Check Current App
```bash
echo "loader info" > /dev/ttyACM0
# Should show currently running app (if any)
```

### Test 3: Launch SubGHz App
```bash
echo "loader open SubGHz" > /dev/ttyACM0
# Should launch SubGHz app on Flipper screen
```

### Test 4: GPIO Test
```bash
echo "gpio set 5 1" > /dev/ttyACM0
sleep 1
echo "gpio set 5 0" > /dev/ttyACM0
# Should toggle GPIO pin 5
```

### Test 5: Read Responses
```bash
# Read what Flipper sends back
cat /dev/ttyACM0 &
echo "help" > /dev/ttyACM0
sleep 2
killall cat
```

## Expected Results

If CLI is accessible:
```
>help
Available commands:
  help - Print this help
  ?    - Alias for help
  loader - Loader commands
  gpio - GPIO commands
  ...
>
```

If CLI NOT accessible:
```
(no response)
```

## Alternative: Test from Android App

```kotlin
// In SubGHzActivity
flipperUSB.sendCommand("help")

// Should receive response via dataReceivedCallback
// If it works, we can use CLI!
```

## If CLI Works

Then we:
1. Remove Flipper companion app completely
2. Update Android app to use CLI commands
3. Document all available CLI commands
4. Test each feature with CLI

## If CLI Doesn't Work

Then we need to:
1. Use Flipper GPIO UART pins (pins 13/14)
2. Require USB-to-Serial adapter
3. Keep companion app but use FuriHalSerialIdUsart
4. Update docs to explain hardware requirements
