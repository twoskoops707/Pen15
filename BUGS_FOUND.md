# CRITICAL BUGS FOUND

## Problem #1: FAKE FLIPPER COMMANDS

All the activities are sending **MADE UP** commands to Flipper that don't exist:

### Example from RFIDActivity.kt (line 100-104):
```kotlin
FlipperConnectionManager.sendCommand("loader open RFID")  // ❌ DOESN'T EXIST
FlipperConnectionManager.sendCommand("rfid read")        // ❌ DOESN'T WORK
```

### Flipper Zero REAL APIs:

**Option 1: CLI Commands (What we're trying to use)**
```bash
# System
device_info
power info
storage list /ext

# RFID - THESE DON'T EXIST IN CLI!
# RFID is controlled via GUI or RPC, not CLI commands

# Sub-GHz (these DO work)
subghz rx 433920000
subghz tx_from_file /ext/subghz/myfile.sub

# GPIO (these work)
gpio mode 7 output
gpio write 7 1
gpio read 7
```

**Option 2: RPC Protocol (Proper way)**
- Flipper uses Protobuf RPC messages
- Need to send binary protobuf data
- Much more complex but ACTUALLY WORKS

## Problem #2: Wrong Architecture

The app is trying to use Flipper's CLI, but:
1. Flipper CLI doesn't have commands for RFID/NFC
2. Those features require RPC protocol or GUI interaction
3. We're in RPC mode but sending text commands instead of protobuf

## THE FIX

### Option A: Use Termux + pyflipper (RECOMMENDED)
```python
# Install in Termux:
pip install pyflipper

# Python script to read RFID:
from flipperzero import FlipperZero
flipper = FlipperZero('/dev/ttyACM0')  # or ttyUSB0
result = flipper.rfid.read()
print(f"Card detected: {result.type}")
print(f"UID: {result.uid}")
```

This ACTUALLY WORKS because pyflipper handles the RPC protocol correctly!

### Option B: Implement Protobuf RPC (Hard)
- Would need to add protobuf library
- Generate Flipper protobuf classes
- Send binary RPC messages
- Parse binary responses
- 1000+ lines of code

### Option C: CLI-only features
Only use features that ACTUALLY have CLI commands:
- ✅ Sub-GHz (has CLI commands)
- ✅ GPIO (has CLI commands)
- ✅ Storage (has CLI commands)
- ❌ RFID (NO CLI commands)
- ❌ NFC (NO CLI commands)
- ❌ iButton (NO CLI commands)
- ❌ Infrared (NO CLI commands)

## RECOMMENDATION

**Use Termux + Python approach:**

1. Install pyflipper in Termux:
   ```bash
   pkg install python
   pip install pyflipper pyserial
   ```

2. Create Python scripts for each feature:
   - `rfid_read.py` - Uses pyflipper to read RFID
   - `nfc_read.py` - Uses pyflipper to read NFC
   - etc.

3. App just runs these Python scripts via TermuxIntegration

4. Parse Python script output and display in app

This way:
- ✅ Features ACTUALLY WORK (pyflipper handles RPC properly)
- ✅ Simple implementation (run Python scripts)
- ✅ No need to implement protobuf
- ✅ Can show real results in app
- ✅ Users see actual Flipper functionality

## Current Commands Being Sent (ALL WRONG):

```
RFIDActivity: "loader open RFID", "rfid read"
NFCActivity: "loader open NFC", "nfc detect"
iButtonActivity: "loader open iButton", "ibutton read"
InfraredActivity: "loader open Infrared", "ir learn"
```

**NONE of these commands exist in Flipper!**

## What Actually Works:

```
SubGHzActivity: "subghz rx 433920000" ✅ REAL COMMAND
WiFiDeauth (via Marauder): "scanap", "deauth" ✅ REAL (Marauder firmware)
GPIO: "gpio mode 7 output", "gpio write 7 1" ✅ REAL COMMANDS
```

That's why some features might work and others don't!
