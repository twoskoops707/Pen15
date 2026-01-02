# REQUIRED FLIPPER ZERO SETTINGS FOR CLI ACCESS

## THE PROBLEM

The Android app connects to Flipper Zero via USB successfully, but then commands don't work.

**This is because the Flipper CLI requires specific settings to be enabled!**

## STEP-BY-STEP FIX

### Step 1: Check USB Channel Setting on Flipper

**ON YOUR FLIPPER ZERO:**

1. Press BACK button until you see the dolphin (desktop screen)
2. Navigate to: **Settings → System → USB Channel**
3. Make sure it's set to: **"0 (CLI)"**

**Why this matters:**
- Channel 0 = CLI access (what we need)
- Channel 1 = Creates second COM port (won't work for CLI)

### Step 2: Make Sure Flipper is at Desktop

- **Flipper MUST be at the desktop screen (showing the dolphin)**
- NOT in any app (SubGHz, NFC, etc.)
- CLI only works from desktop!

### Step 3: Test if CLI Actually Works

Run this test from Termux:

```bash
cd ~/Pen15
./test_flipper_cli_simple.sh
```

**What this test does:**
1. Finds Flipper device (/dev/ttyACM0 or /dev/ttyUSB0)
2. Sends "help" command
3. Reads response for 10 seconds

**Expected result:**
You should see a list of available CLI commands like:
```
>help
Available commands:
  help - This help
  ?    - Alias for help
  device_info - Device info
  loader - Loader commands
  storage - Storage commands
  ...
>
```

### Step 4: Interpret Results

**IF YOU SEE THE COMMAND LIST:**
✅ CLI is working!
✅ Baud rate is correct (115200)
✅ Settings are correct
→ **Problem is in the Android app** - I need to fix how it reads responses

**IF YOU SEE NOTHING:**
❌ CLI is not accessible
→ Check these on Flipper:
   1. USB Channel = 0 (CLI)?
   2. At desktop screen (not in app)?
   3. Using Unleashed firmware? (might have different settings)

## ADDITIONAL UNLEASHED FIRMWARE NOTES

If you're using Unleashed firmware, check:

**Settings → System → USB Settings**
- Make sure USB is enabled
- Try toggling USB off/on

**Settings → System → Debug**
- Some debug modes might interfere with CLI

## WHAT BAUD RATE TO USE

According to official docs:
- **115200** (standard, what the Android app uses)
- Some sources mention 230400, but 115200 is the official rate

## IF CLI TEST WORKS BUT ANDROID APP DOESN'T

Then the issue is how the Android app reads responses. I'll need to fix:
1. Add proper response reading with timeout
2. Add response parsing
3. Display responses in the UI

Let me know what you see when you run the test script!
