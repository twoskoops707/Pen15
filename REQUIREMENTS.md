# PROJECT REQUIREMENTS - CRITICAL

## User's Core Requirements

### What Must Work
1. **Flipper Zero Connection** - Must be simple and reliable
   - Bluetooth connection should just work (no complex pairing BS)
   - USB connection should be straightforward
   - Auto-detect and connect without user hassle

2. **Core Features Must Actually Function**
   - RFID reading/cloning - CURRENTLY BROKEN
   - NFC reading/cloning - CURRENTLY BROKEN
   - All Flipper features must actually work, not just pretend to work

3. **UI Requirements**
   - Modern, professional design
   - NOT garbage/trash/designed by a 5-year-old
   - Progress bars must actually show when operations run
   - Visual feedback for everything

4. **Zero Learning Curve**
   - User should NOT have to learn complex processes
   - Should NOT require technical knowledge
   - Instructions and walkthroughs for everything
   - Make it brain-dead simple

### Suggested Architecture (User's Idea)
- **Embed Termux session within app**
- **Use Termux to handle Flipper communication** (more reliable than custom Bluetooth/USB)
- **Copy/paste commands approach** - App generates and runs Termux commands
- **GUI wrapper around Termux** - Visual interface that runs terminal commands underneath
- This approach avoids custom Bluetooth/USB complexity

## Current Problems to Fix

### Connection Issues
- [ ] Bluetooth connection doesn't work
- [ ] USB connection unclear/broken
- [ ] FlipperConnectionManager is probably fundamentally broken

### Feature Issues
- [ ] RFID doesn't work
- [ ] NFC doesn't work
- [ ] No progress bars showing despite claims
- [ ] Everything fails constantly

### UI Issues
- [ ] Looks like garbage
- [ ] No visual feedback
- [ ] Progress indicators don't show

## Action Plan

1. **Investigate Termux-based approach**
   - Use Termux commands to talk to Flipper via serial/USB
   - Wrap terminal commands in clean GUI
   - More reliable than custom Bluetooth implementation

2. **Fix connection layer**
   - Make Bluetooth pairing simple and automatic
   - Make USB connection straightforward
   - Auto-detect Flipper and connect

3. **Make features actually work**
   - Test RFID read/write with real commands
   - Test NFC read/write with real commands
   - Verify each feature before claiming it works

4. **Polish UI**
   - Modern design that doesn't look like trash
   - Real progress indicators
   - Visual feedback for all operations

## Rules
- ❌ NO MORE COMMITS until everything is tested and working
- ❌ NO MORE LYING about features being implemented
- ❌ NO MORE half-assed implementations
- ✅ TEST EVERYTHING before committing
- ✅ Make it simple and foolproof
- ✅ Focus on reliability over fancy features
