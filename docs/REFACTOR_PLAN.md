# Plan: End-to-End JSON Protocol + Custom Flipper FAP

## Context
**Root cause confirmed:** The phone detects the Flipper (USB recognized, app sees it) but **zero data flows in either direction**. The Flipper's interactive CLI operates on the USB CDC serial port in a way the current app can't reliably talk to — the CLI requires reading a prompt (`>: `) before commands are accepted, has no structured response format, and the app doesn't handle this handshake. Commands are being sent into the void.

**Fix:** Replace the CLI approach entirely with a custom FAP (Flipper Application Package) that runs on the Flipper, takes ownership of the USB CDC serial port, and speaks a clean JSON protocol. The app no longer talks to the Flipper CLI — it talks to the FAP directly. This completely bypasses the CLI handshake problem.

**Architecture:**
```
Android App (JSON) → USB Serial → Flipper FAP → GPIO pins → AWOK Dual Mini v3
                                               ← JSON responses ←
```

---

## Part 1: FAP Source Code (C)

### New files: `fap/pen15_controller/`

**`application.fam`** — FAP manifest:
```
App(
    appid="pen15_controller",
    name="Pen15 Controller",
    apptype=FlipperAppType.EXTERNAL,
    entry_point="pen15_app",
    requires=["gui"],
    stack_size=2 * 1024,
    fap_version=(1, 0),
    fap_category="Tools",
)
```

**`jsmn.h`** — Copy of jsmn lightweight JSON tokenizer (MIT license, header-only, ~400 lines). No external deps.

**`pen15_controller.c`** — Main FAP:
- Screen layout: Title bar "PEN15", status row, "CMD:" row (truncated), progress bar, "RX:" response preview
- Main loop reads USB CDC serial line-by-line
- Parses JSON using jsmn
- Dispatches by `"action"` field:
  - `"ping"` → `{"status":"ok","device":"flipper_zero","fw":"mntm-014"}`
  - `"gpio_mode"` → calls `furi_hal_gpio_init()`, responds ok/error
  - `"gpio_write"` → calls `furi_hal_gpio_write()`, responds ok/error
  - `"gpio_read"` → calls `furi_hal_gpio_read()`, returns value
  - `"uart_init"` → configures USART1 (GPIO pins 13/14) at given baud
  - `"uart_send"` → sends bytes to AWOK via USART1, collects response for 500ms, returns as `uart_rx`
  - `"get_device_info"` → returns device_info fields
- Screen animates a spinning progress indicator while executing
- Pin state registry: tracks configured modes, rejects conflicting writes
- Back button exits FAP
- Error responses: `{"status":"error","code":"X","message":"Y"}`

### FAP build integrated into existing release workflow
- No separate workflow — FAP build is added to the existing APK release workflow
- Uses `ghcr.io/flipperdevices/flipperzero-firmware:dev` Docker image step in the same job
- After FAP is compiled, it is uploaded as a **GitHub Release asset** alongside the APK (via `gh release upload`)
- User goes to GitHub Releases page → downloads `pen15_controller.fap` → copies to Flipper SD card `/apps/Tools/pen15_controller.fap`
- Every app release = both APK + FAP bundled together in the release

---

## Part 2: Android Protocol Layer

### New file: `FlipperProtocol.kt`
JSON command builder + response parser:
```kotlin
object FlipperProtocol {
    fun ping(): String
    fun gpioMode(pin: Int, mode: String): String      // "input"|"output"
    fun gpioWrite(pin: Int, value: Int): String        // 0|1
    fun gpioRead(pin: Int): String
    fun uartInit(baud: Int): String
    fun uartSend(data: String): String
    fun getDeviceInfo(): String

    fun parseResponse(json: String): FlipperResponse   // data class
    data class FlipperResponse(val status: String, val data: Map<String,String>)
}
```

### New file: `FlipperHAL.kt`
Hardware abstraction layer. Validates before sending, tracks pin state:
```kotlin
object FlipperHAL {
    private val pinModes = mutableMapOf<Int, String>()  // registry

    fun ping(cb: (Boolean, String) -> Unit)
    fun gpioMode(pin: Int, mode: String, cb: (Boolean, String) -> Unit)
    fun gpioWrite(pin: Int, value: Int, cb: (Boolean, String) -> Unit)  // validates mode first
    fun gpioRead(pin: Int, cb: (Boolean, Int) -> Unit)
    fun uartInit(baud: Int = 115200, cb: (Boolean, String) -> Unit)
    fun uartSend(data: String, cb: (Boolean, String) -> Unit)  // returns uart_rx
    fun getDeviceInfo(cb: (Boolean, Map<String,String>) -> Unit)

    private fun send(json: String, cb: (FlipperProtocol.FlipperResponse) -> Unit)
}
```

### Updated file: `FlipperConnectionManager.kt`
Add session initialization:
```kotlin
fun initSession(callback: (Boolean, String) -> Unit) {
    // 1. Send "loader open \"Pen15 Controller\"\r\n" via raw serial
    // 2. Wait 2000ms for FAP to start
    // 3. Send ping JSON
    // 4. Wait up to 3000ms for {"status":"ok","device":"flipper_zero"}
    // 5. Report success/failure
}
```
Add JSON response router in `setDataReceivedCallback` — buffer incoming data, detect `{...}\n` frames, route to `FlipperHAL` pending callbacks vs. raw data listeners.

---

## Part 3: Fix Android Activities

### `FlipperGPIOBridge.kt`
- Change `BRIDGE_OPEN_CMD` from `loader open "USB-UART Bridge"` to `loader open "Pen15 Controller"`
- `startBridge()`: calls `FlipperConnectionManager.initSession()` instead of raw loader command
- `sendMarauderCommand(cmd)`: sends via `FlipperHAL.uartSend(cmd + "\r\n", ...)` instead of raw serial

### `GPIOActivity.kt`
Remove wrong commands. Replace with:
- `controlGPIO()` → `FlipperHAL.gpioMode(5, "output")` then `FlipperHAL.gpioWrite(5, 1)`
- `marauderDeauth()` → `FlipperHAL.uartSend("attack -t deauth\r\n", ...)`
- `marauderEvilPortal()` → `FlipperHAL.uartSend("evilportal\r\n", ...)`
- `marauderWiFiScan()` → `FlipperHAL.uartSend("scanap\r\n", ...)`
- `marauderBLESpam()` → `FlipperHAL.uartSend("blespam\r\n", ...)`

### `WiFiDeauthActivity.kt`
- Keep existing Marauder commands (`scanap`, `stopscan`, `select -a N`, `attack -t deauth`, `sniffpmkid`)
- Route through `FlipperHAL.uartSend()` instead of `FlipperGPIOBridge.sendMarauderCommand()` directly

---

## Part 4: Result Logging to Phone Storage

### `SessionLogger.kt` (new)
- Logs every JSON command sent + response received
- Stores to `context.getExternalFilesDir("pen15_logs")/<date>.json`
- `exportCsv()` method
- Called from `FlipperHAL.send()`

---

## Files to Create
1. `fap/pen15_controller/application.fam`
2. `fap/pen15_controller/jsmn.h`
3. `fap/pen15_controller/pen15_controller.c`
4. `.github/workflows/build_fap.yml`
5. `app/src/main/java/com/pentest/dashboard/FlipperProtocol.kt`
6. `app/src/main/java/com/pentest/dashboard/FlipperHAL.kt`
7. `app/src/main/java/com/pentest/dashboard/SessionLogger.kt`

## Files to Modify
1. `FlipperConnectionManager.kt` — add `initSession()`, JSON response router
2. `FlipperGPIOBridge.kt` — point to Pen15 FAP, use HAL for UART
3. `GPIOActivity.kt` — fix all wrong commands
4. `WiFiDeauthActivity.kt` — route through HAL

## Verification
1. Push to main → GitHub Actions builds APK + FAP artifact
2. Install FAP on Flipper SD: `/apps/Tools/pen15_controller.fap`
3. Install APK on phone
4. Connect Flipper via USB OTG
5. Tap Connect in app → `loader open "Pen15 Controller"` sent → FAP starts on Flipper screen
6. App sends ping → Flipper FAP responds → session established
7. Test GPIO: tap Pin Control → FAP screen shows command + progress bar
8. Test AWOK: tap WiFi Scan → FAP forwards to AWOK UART → results return to app
9. Check `getExternalFilesDir("pen15_logs")` for session log file
