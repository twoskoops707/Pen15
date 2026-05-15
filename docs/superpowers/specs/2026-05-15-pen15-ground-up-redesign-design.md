# Pen15 Ground-Up Redesign (Connection Core + UX)  
Date: 2026-05-15

## 1) What is going wrong today

### Primary connection failure chain
1. **Flipper FAP state machine mismatch**  
   `pen15_controller.c` started in `ModeMenu` and only parsed JSON in `ModeJson`, while bridge forwarding depended on `bridge_mode` that was not set consistently.
2. **Android protocol frame loss**  
   `FapProtocol.onData()` assumed complete JSON lines per callback and dropped partial serial frames.
3. **Connection races / duplicated attempts**  
   Main dashboard and helper paths retried USB connect quickly and could overlap permission + port-open flow.
4. **Single-callback data routing collisions**  
   Multiple activities overwrite transport callbacks; whichever screen last set callback received data.
5. **Bridge handshake fragility**  
   Bridge startup relied on a JSON `uart_send` after entering bridge mode, which conflicts with raw pass-through mode.

## 2) Timeline summary (high level)

- Early architecture relied heavily on Termux command execution paths.
- Pivot to direct AWOK USB and FAP JSON protocol happened across `awok-only`, `dolphin-rewrite`, and follow-up branches.
- Releases/tags advanced rapidly, but runtime USB/FAP handshake issues persisted despite builds passing.
- Session notes repeatedly reported:
  - FAP-first/USB-first inconsistent behavior
  - “connected but silent” data path
  - AWOK initialization instability

## 3) Current redesign goals

1. **Make transport deterministic** (one connection pipeline, one framing strategy, one callback dispatcher).
2. **Make firmware state explicit** (JSON command mode and bridge mode transitions are intentional and observable).
3. **Make user flow obvious** (dashboard should show exact readiness state and next action).
4. **Keep direct AWOK USB and Flipper bridge both usable**, but with clear status.

## 4) Changes implemented in this pass

### Android
- `FapProtocol.kt`
  - Added buffered line framing to handle partial serial packets.
  - Added bounded buffer protection for noisy/fragmented streams.
- `FlipperUSBManager.kt`
  - Added connect-in-progress guard to reduce duplicate/racy connects.
  - Serialized `sendCommand` writes through I/O executor.
- `ESP32SerialManager.kt`
  - Added connect-in-progress guard.
  - Added CDC fallback driver path for broader AWOK compatibility.
  - Serialized writes through I/O executor.
- `FlipperConnectionManager.kt`
  - Added shared data listener dispatcher (`add/removeDataReceivedListener`).
  - Preserved legacy `setDataReceivedCallback` behavior without wiping all listeners.
- `ConnectionHelper.kt`
  - Fixed USB timeout callback double-fire race.
- `MainActivity.kt`
  - Added single listener setup and safer handshake flow.
  - Added two-step FAP verification (`ping` + optional reopen + re-ping).
  - Added operational status logic tied to real readiness.
- `activity_main.xml`
  - Added an operational status card (`SYSTEM CHECK` panel) and quick-connect action for clearer UX.
- `WiFiDeauthActivity.kt`, `ESP32ManagerActivity.kt`
  - Switched Flipper data handling to additive listener model to reduce callback stomping.

### Flipper FAP
- `fap/pen15_controller/pen15_controller.c`
  - Start in `ModeJson` by default.
  - Set `bridge_mode = true` when entering bridge.
  - Parse JSON frames whenever JSON path is active (no unreachable command path).
  - Tightened bridge-exit control-line condition.

## 5) What remains for a full “from-ground-up” rebuild

1. **Session service layer**
   - Move connection + protocol orchestration out of activities into a dedicated foreground/session controller.
2. **Command contract versioning**
   - Add protocol schema version + capability checks (`ping` handshake should advertise supported actions).
3. **UI information architecture redesign**
   - Replace large static card wall with role/task-driven workflows (Mission presets, Run history, Device diagnostics).
4. **Verification strategy**
   - Add parser tests for fragmented frames.
   - Add protocol integration tests (mock serial streams).
   - Add hardware smoke checklist in CI release notes.
5. **Device diagnostics**
   - Surface USB descriptor + active mode + last command/response timeline directly in app.

## 6) Immediate success criteria for this phase

- Flipper reports connected and FAP-ready distinctly.
- JSON responses are no longer lost on chunked serial reads.
- Bridge activation no longer depends on contradictory JSON-vs-raw mode behavior.
- AWOK and Flipper screens can coexist without callback override failures.
