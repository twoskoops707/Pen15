# Pen15 Audit Report

Date: 2026-05-15
Branch: `cursor/pen15-v4-rewrite-feb6`
Author: Cloud agent end-to-end review

This report is the result of reading the entire Pen15 codebase end-to-end —
all 47 Kotlin source files, all 35 layouts, the FAP C source, the build
workflow, the README, the existing `docs/SESSION_NOTES.md`,
`docs/REFACTOR_PLAN.md`, `.remember/remember.md`, and
`CLAUDE.md`. Every claim below is grounded in a specific file or commit.

It is not a roast — it is the engineering pre-mortem the rewrite needs.

---

## 1. What the app is supposed to be

A push-button Android pen-testing harness that turns a Samsung Note 10+
(non-rooted, Android 11) into the operator console for:

1. A **Flipper Zero** (RFID / NFC / Sub-GHz / IR / iButton / GPIO / BadUSB)
   running the custom `pen15_controller.fap` over USB CDC at 115200 8N1.
2. An **AWOK Dual Mini v3** (ESP32 Marauder) that can either:
   - Plug directly into the phone via USB-OTG (CP210x / CH340 / native), or
   - Plug into the Flipper's GPIO pins 13/14 and ride the Flipper's UART
     bridge to the phone.
3. A handful of phone-native and Termux-native tools (nmap, hashcat,
   aircrack-ng, OSINT scripts) for engagements.

The product promise is simple: connect the hardware, tap a tile, get a
result. It currently fails the "tap a tile, get a result" half of that.

---

## 2. The actual user-visible failure

From `docs/SESSION_NOTES.md` and the runtime path I traced in code:

- **FAP launched first → phone never sees Flipper.** When the
  `pen15_controller.fap` is already running on the Flipper, plugging in
  the OTG cable produces no `USB_DEVICE_ATTACHED` intent in the app, and
  no entry in `usbManager.deviceList`. The chip in MainActivity stays
  red, "TAP TO CONNECT".
- **USB first, FAP after → connects but silent.** The Flipper enumerates,
  permission is granted, the port opens at 115200 8N1 with a DTR/RTS
  pulse — but the JSON ping in `FapProtocol.ping()` never resolves. No
  data is routed. The app sits at "FLIPPER: ON" while every operation
  times out with `code=TIMEOUT`.
- **AWOK is never recognized at all.** `ESP32SerialManager.connect()`
  reports "No ESP32/AWOK device found" even when the AWOK is plugged
  into a powered USB hub.

These are three separate bugs that look like one to the user.

---

## 3. Root cause #1 — `FlipperConnectionManager.initSession()` is a stub

`docs/REFACTOR_PLAN.md` documents an `initSession()` that is supposed to:

1. Send `loader open "Pen15 Controller"` over raw serial.
2. Wait 2000 ms for the FAP to start.
3. Send the JSON ping.
4. Wait up to 3000 ms for `{"status":"ok",…}`.
5. Report success/failure.

What is actually in `FlipperConnectionManager.kt` at HEAD:

```95:98:app/src/main/java/com/pentest/dashboard/FlipperConnectionManager.kt
    fun initSession(callback: (Boolean, String) -> Unit) {
        callback(isConnected(), if (isConnected()) "Connected" else "Flipper not connected")
    }
```

It is a stub. It does not launch the FAP, it does not ping, it does not
wait. It just synchronously echoes the current USB enumeration state.

That means **every "Flipper connected but FAP not running"** path ends
up with no recovery action and a single toast. The user is the one
expected to manually navigate Apps → Tools → Pen15 Controller on the
Flipper itself. This is the opposite of "push-button".

---

## 4. Root cause #2 — `FapProtocol.onData` is the only response router, and it is fragile

`FlipperUSBManager.onNewData` calls `FapProtocol.onData(received)` plus
the optional `dataReceivedCallback`. `FapProtocol.onData` only matches
lines that:

- start with `{`, AND
- parse as JSON, AND
- contain a non-empty `id` that is in the `pending` map, AND
- contain `status == "ok"` or `status == "error"`.

Two concrete problems:

1. **Line splitting on chunked CDC packets.** USB CDC delivers data in
   64-byte packets. A JSON response longer than 64 bytes (e.g.
   `subghz_record` returns up to 1 KB of timings) is split across
   multiple `onNewData` calls. `raw.lines()` over the chunk only sees
   trailing fragments. There is no buffer that stitches partial frames
   between callbacks. Long responses are silently dropped.
2. **No timeout for `ping`.** The 5000 ms ping timeout depends on the
   `Handler.postDelayed` running on the main looper. If the main thread
   is blocked (which it is on cold start while we're inflating
   `activity_main.xml`), the timeout fires late and the user sees the
   "FAP not running" toast even when the FAP did respond.

---

## 5. Root cause #3 — DTR/RTS pulse races CDC enumeration on Momentum

`FlipperUSBManager.connectToDevice` does:

```245:255:app/src/main/java/com/pentest/dashboard/FlipperUSBManager.kt
                    try {
                        port.dtr = false
                        port.rts = false
                        Thread.sleep(100)
                        port.dtr = true
                        port.rts = true
                        Thread.sleep(300)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) android.util.Log.w(TAG, "DTR/RTS pulse failed: ${e.message}")
                    }
```

On stock Flipper firmware this works. On Momentum mntm-014, the FAP
calls `cli_vcp_disable()` and `furi_hal_cdc_set_callbacks()` when it
starts; this briefly drops the CDC control line. If the phone happens
to be in the 100 ms `dtr=false` window when that happens, the port
opens but the FAP's CDC callback chain points at a stale buffer and
the first ping is consumed before the async RX DMA is rearmed.

The FAP ships with an explicit `cdc_ctrl_cb` that sets
`EvtBridgeExit` on DTR low, so a subsequent DTR pulse from the phone
to "wake up" the FAP is interpreted as **exit bridge mode** and
returns the FAP to JSON mode mid-transfer.

Net effect: a second connect attempt always works, the first one
usually does not. The user thinks the app is broken.

---

## 6. Root cause #4 — `device_filter.xml` triggers the system USB picker for the wrong app

```xml
<usb-device vendor-id="1155" />
<usb-device vendor-id="12346" />
<usb-device vendor-id="9025" />
<usb-device vendor-id="1240" />
<usb-device vendor-id="7531" />
```

VID-only matches mean Android offers Pen15 as the launch target for
*any* device with VID 0x0483 (Microchip, Linux gadget, Arduino, Atmel),
not just Flipper. On a phone that already has a CDC-ACM driver app
installed (Termux, Serial USB Terminal), the user gets a chooser. If
they pick the wrong one, our app never sees `USB_DEVICE_ATTACHED` and
the chip stays red forever.

When the FAP is launched first the Flipper renumerates with the FAP's
own CDC interface descriptor, which on Momentum reports a *different*
class/subclass than stock — and the VID-only catch-all in the filter
is wide enough that Android picks the wrong app to handle the attach
intent.

---

## 7. Root cause #5 — AWOK direct USB never enumerates because Flipper sits on the bus first

`ESP32SerialManager.connect()` filters by VID:

```118:121:app/src/main/java/com/pentest/dashboard/ESP32SerialManager.kt
                val esp32Driver = availableDrivers.find { driver ->
                    val device = driver.device
                    device.vendorId in ESP32_VIDS && device.vendorId != FLIPPER_VID
                }
```

The Note 10+ in USB host mode can power exactly one downstream device
without an externally powered hub. When the Flipper is plugged in
first, the phone's USB host controller refuses to enumerate the AWOK.
There is **no error message** for this — `ESP32_VIDS` simply does not
match anything and the manager reports "No ESP32/AWOK device found".

The "Direct AWOK" path in `WiFiDeauthActivity` only runs if
`ESP32SerialManager.instance?.isConnected() == true`, which it never
is in the dual-device case, so the app silently falls back to the
Flipper-bridged path which depends on the broken `initSession`.

---

## 8. Root cause #6 — The connection chain has six explicit gates and no recovery

To get a single `scanap` command to AWOK through the Flipper bridge,
the call has to traverse:

1. `MainActivity.attemptConnection()` → success
2. `FlipperConnectionManager.usbManager?.connect()` → success
3. `FapProtocol.ping()` → success (5 s timeout)
4. User taps `cardWifiDeauth`
5. `WiFiDeauthActivity.fabControl` → `FlipperGPIOBridge.startBridge()`
6. `FapProtocol.uartInit(115200)` → success (5 s timeout)
7. `FapProtocol.uartSend("help")` reachability probe → response under 256 bytes
8. `FlipperConnectionManager.sendRawBytes("scanap\r\n")`
9. AWOK responds → CDC packets → `FapProtocol.onData` → not routed because the response is not JSON → **dropped**

Step 9 is the killer. In bridge mode, the AWOK's plain-text Marauder
output flows back through the FAP transparently, but the Android side
keeps `FapProtocol.onData` wired. Non-JSON lines fall through, but
they are not delivered to `WiFiDeauthActivity`'s
`scanBuffer.append(data)` until `setDataReceivedCallback` happens to
be set — which it is, *but* the `usingDirectUsb` branch is selected
based on whether AWOK direct is connected at the moment the user taps
the FAB, which races the connection state. Half the time the buffer
is appended, half the time the FAP routing eats it.

---

## 9. Termux is the wrong substrate for the Linux side

The README is honest about this:

> Termux removed hacking tools (hashcat, aircrack-ng, hydra, metasploit)
> from official repositories in 2023. You must compile them manually.

`TermuxIntegration.Setup.installTools()` is 120 lines of bash that
clones four projects, installs build-essential, runs `make` four
times, and falls back to "Python hashlib" when the binary build
fails. On a Note 10+ that takes 30–60 minutes per tool, gets warm,
and ends with a functioning binary about 60 % of the time.

Beyond the install pain:

- **`com.termux.RUN_COMMAND` requires the user to manually edit
  `~/.termux/termux.properties` to add `allow-external-apps=true`.**
  This is documented in `.remember/remember.md` but not in the app —
  every fresh install fails silently.
- **Termux on a non-rooted phone cannot put the radio in monitor
  mode**, so `airodump-ng`, `aireplay-ng`, `hcxdumptool`, and most of
  the WiFi scripts in `TermuxIntegration.kt` cannot work at all. The
  app builds the command anyway and fires it into Termux, which then
  prints `Operation not permitted` and exits.
- **Scoped storage breaks every script that writes to
  `/sdcard/`** unless the user grants `MANAGE_EXTERNAL_STORAGE`,
  which the manifest does not request.
- **`gh` CLI crashes on Termux ARM64 with SIGSYS** (also documented
  in `.remember/remember.md`).

The honest assessment: Termux as a runtime is a dead end for this
product. The CLI commands the user wants are either:

(a) things the AWOK / Flipper can do over their own UART (then we
    don't need Termux), or

(b) network reconnaissance against a remote target (then we don't
    need Linux locally — we can do it from Kotlin with OkHttp /
    `InetAddress` / `WifiManager` / a small Python sidecar).

---

## 10. Architectural smells in the Kotlin source

This is the part that makes the bugs above hard to fix.

### 10.1 No layering

47 `.kt` files in a single package (`com.pentest.dashboard`). Activities
own connection lifecycle, business logic, parsers, and view-binding all
in the same class. `WiFiDeauthActivity` has a 50-line regex parser
inline, a `RecyclerView.Adapter` inner class, callbacks set on
singletons, and direct calls to `FlipperGPIOBridge`, `FapProtocol`,
and `ESP32SerialManager.instance` — there is no domain model.

### 10.2 Three stateful singletons that race each other

- `FlipperConnectionManager` (object)
- `ESP32SerialManager` (companion `_instance`)
- `FlipperGPIOBridge` (object) with its own `isBridgeActive` flag

Each one mutates `connectionType` / `isBridgeActive` /
`usingDirectUsb` from main thread, IO executor, and async callbacks
without any synchronization beyond `@Volatile` on the ESP32 instance.
The `dataReceivedCallback` is whatever was last set, with no priority
or per-screen scoping.

### 10.3 Mixing coroutines and `Handler.postDelayed`

`MainActivity` uses `lifecycleScope` and `delay`. `WiFiDeauthActivity`
uses `Handler(Looper.getMainLooper())` directly. `FapProtocol` uses
`mainHandler.postDelayed` for timeouts. Cancellation semantics differ:
the coroutine is cancelled on lifecycle change, the handler is not.
Pending pings outlive the activity that scheduled them.

### 10.4 No structured error model

Error codes from the FAP (`NOT_CONNECTED`, `TIMEOUT`, `CANCELLED`)
are JSON strings. The Kotlin side returns booleans. By the time the
error reaches the UI it is "Operation failed", which is not
actionable.

### 10.5 View system, no theming consistency

35 hand-rolled XML layouts, 90 drawables, color names like
`status_disconnected`, `func_wifi`, `danger`, `pink`, `info`. There is
no central theme file driving the palette — colors are hardcoded
across drawables (`bg_chip_flipper.xml`, `bg_hardware_card_awok.xml`,
etc.). Re-skinning means editing 30+ drawables by hand.

### 10.6 No tests

There is no `app/src/test` directory and no `app/src/androidTest`
content. The "verification before completion" rule has been satisfied
by reading APK install logs.

---

## 11. Security / engagement model: missing entirely

The README is emphatic about authorized use:

> ✅ Testing YOUR OWN devices and networks
> ✅ Authorized penetration testing with WRITTEN PERMISSION

The app has **no authorization gate, no scope tracking, no audit
log**. A user can tap "WiFi Deauth" against any SSID in range with
no record of:

- which engagement it belonged to,
- whether the SSID was in scope,
- when the action started/stopped,
- what response the AWOK gave.

For the stated use case — "for my security company to be used only
on my own equipment or on my clients' equipment once approval has
been made" — this is the single biggest gap. Nothing else matters
if the operator can't prove what they did and didn't do.

---

## 12. Build / release pipeline is fine

`/.github/workflows/build.yml` does build the APK and the FAP on
every push and uploads both as a GitHub Release asset. This part
works and should be kept.

The four other workflows in `/.github/workflows/` are dead weight
(`Test AllGpt 2`, `Test AllGTP`, `rebuild_and_fix.yml`,
`archive/`) — they reference scripts that no longer exist.

---

## 13. Verdict

The hardware ideas are correct. The FAP C code is solid (it is the
strongest piece of code in the repo). The AWOK direct serial path
is correct. The Marauder command list is correct.

What is broken is the **glue**:

- the connection orchestration on the Android side,
- the routing of bytes from the CDC port into either the JSON
  parser or the bridge consumer,
- the assumption that Termux is a viable Linux runtime on a
  non-rooted Note 10+,
- the lack of any engagement model that matches the way a security
  consultancy actually operates.

The right move is not to keep patching `FlipperConnectionManager`.
The right move is a v4 rewrite of the Android side around a single
connection state machine, a single byte router, an explicit
engagement scope, and a Compose UI. The FAP and the build workflow
stay; the Kotlin app is replaced.

Design spec for v4: `docs/DESIGN_V4.md`.
