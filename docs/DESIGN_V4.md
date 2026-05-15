# Pen15 v4 Design Spec

Date: 2026-05-15
Branch: `cursor/pen15-v4-rewrite-feb6`
Companion document: `docs/AUDIT_REPORT.md`

This is the design spec for a complete rewrite of the Pen15 Android
side. The Flipper FAP (`fap/pen15_controller`) and the GitHub Actions
build pipeline are kept as-is.

The product north star, in one sentence:

> **A four-year-old can pick up the phone, plug in the Flipper +
> AWOK, tap one big button, and finish a real penetration test that
> a security company can hand to a client.**

That is the only thing that matters. Every architectural decision
below is in service of it.

---

## 1. Two non-negotiable requirements

### 1.1 Full capability exposure

Every feature exposed by the Flipper Zero firmware and the AWOK
Dual Mini v3 (ESP32 Marauder) **must** be reachable from this app.
That includes, at minimum:

| Source | Feature | v4 surface |
|--------|---------|------------|
| Flipper | RFID 125 kHz read / write / emulate / brute force | One screen |
| Flipper | NFC read / write / emulate / MFKey32 / mfoc / Mifare nested | One screen |
| Flipper | Sub-GHz read / record / replay / brute force / jammer | One screen |
| Flipper | Infrared learn / blast / TV-B-Gone / universal remotes | One screen |
| Flipper | iButton read / emulate / brute | One screen |
| Flipper | BadUSB DuckyScript runner + script library | One screen |
| Flipper | GPIO pin control (input/output read/write) | One screen |
| Flipper | Bluetooth scan / advertise / spoof | One screen |
| AWOK | WiFi scan / target / channel hop | All under one screen |
| AWOK | Deauth attack | Same screen, one button |
| AWOK | PMKID capture | Same screen, one button |
| AWOK | Probe request sniff | Same screen, one button |
| AWOK | Beacon spam / SSID flood | Same screen, one button |
| AWOK | Evil portal (captive portal phishing) | Same screen, one button |
| AWOK | Karma attack (probe response) | Same screen, one button |
| AWOK | Bluetooth LE scan / spam / sour apple | One screen |
| AWOK | Packet capture (.pcap to SD or stream) | One screen |
| AWOK | MITM / ARP poison helper | One screen |

### 1.2 Termux + Linux backend stays for the heavy crypto

The user explicitly wants hash cracking and handshake cracking
on-device. That means we keep a Termux integration, but we own the
setup so the user never has to think about it.

| Job | Engine | Where |
|-----|--------|-------|
| WPA/WPA2 handshake → hashcat -m 22000 | hashcat (binary or python fallback) | Termux |
| WPA handshake → aircrack-ng | aircrack-ng (built from source) | Termux |
| MD5 / SHA / NTLM / bcrypt | hashcat / john / python hashlib | Termux |
| Wordlist management (rockyou, top10k, etc.) | curl + sha256 verify | Termux |
| pcap → hashcat hashfile | hcxpcapngtool | Termux |
| Network scan from phone | nmap | Termux |
| Port knock / sslscan / dns recon | curl + dig | Termux |

We keep these in Termux because nothing on Android user space replaces
them. We do **not** keep:

- monitor-mode WiFi capture (needs root or USB WiFi adapter — out
  of scope; AWOK does this)
- aireplay-ng deauth (AWOK does it better)
- mitmproxy on Android (UX is awful; we proxy through the laptop or
  the AWOK evil portal)

---

## 2. UX principle: every screen has one big button

Every operational screen has the same anatomy:

```
┌────────────────────────────────────┐
│  ←  WIFI                       (?) │   ← back, screen name, help
├────────────────────────────────────┤
│                                    │
│            HUGE STATUS             │   ← "READY" / "SCANNING" /
│                                    │      "ATTACKING" — one word
│                                    │
├────────────────────────────────────┤
│  ┌──────────────────────────────┐  │
│  │                              │  │
│  │       BIG GREEN BUTTON       │  │   ← single primary action
│  │                              │  │      label changes with state
│  └──────────────────────────────┘  │
├────────────────────────────────────┤
│  Plain-English status line.        │   ← what the app is doing
│  e.g. "Looking for nearby WiFi."   │      right now, in human words
├────────────────────────────────────┤
│  Smaller details (collapsed).      │   ← tap to expand: technical
│                                    │      data for the operator who
│                                    │      actually knows
└────────────────────────────────────┘
```

**Rules:**

1. The big button is always green when safe to press, red when
   actively attacking, and grey when prerequisites are missing.
2. Text under the button is one sentence in plain English. No
   "BSSID", no "deauth packets", no "PMKID" in the primary copy. The
   secondary "Details" panel can use those words.
3. Help icon (`?`) opens a single-screen explanation: "What this
   does", "When you'd use it", "What permission you need from the
   client" — not a wall of text.
4. Confirmation dialogs are mandatory for any disruptive action and
   must show the engagement target name + scope check.
5. Voice-prompt-style hints: "Plug in the AWOK now", "Tap a network
   in the list", "Hold the iButton key against the Flipper". The
   app talks to the user like a guided tour.

---

## 3. The home screen: four big tiles

```
┌────────────────────────────────────┐
│  PEN15        [engagement chip]    │  ← shows current engagement
├────────────────────────────────────┤
│                                    │
│  ┌──────────┐    ┌──────────┐      │
│  │          │    │          │      │
│  │  FLIPPER │    │   WIFI   │      │   ← Flipper card lights up
│  │  (green) │    │  (green) │      │      green when FAP is talking
│  │          │    │          │      │
│  └──────────┘    └──────────┘      │
│                                    │
│  ┌──────────┐    ┌──────────┐      │
│  │          │    │          │      │
│  │  CRACK   │    │   RECON  │      │   ← Termux + OSINT
│  │          │    │          │      │
│  │          │    │          │      │
│  └──────────┘    └──────────┘      │
│                                    │
├────────────────────────────────────┤
│  [ NEW MISSION ]    [ SETTINGS ]   │
└────────────────────────────────────┘
```

Each big tile drills into a sub-menu of equally large tiles. No tile
is smaller than 120 dp. Long-press any tile to read its help text
out loud (TalkBack-friendly).

---

## 4. The "New Mission" wizard (the engagement gate)

Before any disruptive action runs, the app forces a one-time wizard:

1. **"Whose stuff are you testing?"** — pick from saved engagements
   or start a new one.
2. **"What's the scope?"** — operator types a name ("Acme HQ WiFi"),
   pastes a list of MACs / SSIDs / IP ranges / phone numbers /
   building addresses. A free-text "Notes" field for the SOW summary.
3. **"Sign-off"** — operator types client name, date, "I have written
   permission" toggle. Signature pad (finger-draw). PDF generated and
   stored at `{externalFilesDir}/engagements/{id}/authorization.pdf`.
4. **Engagement chip** appears on the home screen. Every action from
   that point until the operator taps "End Mission" is logged under
   this engagement.

Without an active engagement, the disruptive feature tiles
(WiFi → Deauth, Sub-GHz → TX, BadUSB → Run, etc.) are **disabled**
and tapping them shows the wizard. Read-only features (RFID read,
NFC read, scans without attacks) are always available.

This is what makes the app sellable to a security company:
**every byte the operator sends has provenance.**

---

## 5. Architecture (Kotlin + Compose)

```
com.pen15
├── Pen15App                          (Application, init connection service)
├── ui
│   ├── MainActivity                  (Compose root, NavHost)
│   ├── theme/                        (Material 3 dark theme, single color file)
│   ├── home/HomeScreen
│   ├── engagement/EngagementWizard, EngagementListScreen
│   ├── flipper/                      (RfidScreen, NfcScreen, SubGhzScreen, IrScreen,
│   │                                  IButtonScreen, BadUsbScreen, GpioScreen, BluetoothScreen)
│   ├── wifi/                         (WifiHomeScreen, WifiScanScreen, DeauthScreen,
│   │                                  EvilPortalScreen, PmkidScreen, BeaconSpamScreen,
│   │                                  KarmaScreen, MitmScreen, PacketCaptureScreen)
│   ├── crack/                        (HandshakeCrackScreen, HashCrackScreen,
│   │                                  WordlistManagerScreen)
│   └── recon/                        (OsintScreen, NmapScreen, GoogleDorkScreen,
│                                      PhoneSensorsScreen)
├── domain
│   ├── connection/
│   │   ├── ConnectionService         (Foreground service, owns USB lifecycle)
│   │   ├── ConnectionState           (sealed: Idle, Searching, FlipperOnly,
│   │   │                              AwokOnly, Both, Error)
│   │   ├── HardwareGate              (predicate: "is X feature usable now?")
│   │   └── DataRouter                (single byte sink, dispatches to JSON parser
│   │                                  OR raw subscriber per channel)
│   ├── flipper/
│   │   ├── FapClient                 (typed wrapper around the JSON protocol)
│   │   ├── FapBridge                 (UART bridge mode helper)
│   │   └── FlipperOps                (ping, rfid_read, nfc_detect, …, returns Result<T>)
│   ├── awok/
│   │   ├── MarauderCli               (typed builders for every Marauder command)
│   │   ├── ScanResultParser          (regex/state-machine parser, replaces
│   │   │                              WiFiDeauthActivity inline regex)
│   │   └── AwokOps                   (scanWifi(), deauth(), evilPortal(), …)
│   ├── termux/
│   │   ├── TermuxRunner              (RUN_COMMAND intent + bootstrap check)
│   │   ├── ToolInstaller             (bootstrap script: hashcat / aircrack-ng / john)
│   │   ├── WordlistRepository        (download, verify SHA-256, cache)
│   │   ├── HandshakeCrackJob         (input: pcap, output: hashcat result + plaintext)
│   │   └── HashCrackJob              (input: hash + type, output: plaintext)
│   ├── engagement/
│   │   ├── Engagement                (data class: id, client, scope, signature, dates)
│   │   ├── EngagementRepository      (Room DB)
│   │   ├── ScopeChecker              (predicate: is this MAC/SSID/IP in scope?)
│   │   └── AuditLog                  (append-only JSONL per engagement)
│   └── reports
│       ├── PdfReporter               (engagement summary + audit log → PDF)
│       └── ZipExporter               (engagement bundle for client handoff)
└── data
    ├── usb/                          (mik3y serial wrapper, retry, reconnect)
    ├── storage/                      (StorageManager — captures/, hashes/, recon/, logs/)
    └── prefs/                        (DataStore for app settings)
```

### 5.1 ConnectionService: foreground, single source of truth

A `ForegroundService` (notification: "Pen15 connected: Flipper +
AWOK") owns:

- the `UsbManager` permission receiver,
- the `UsbSerialPort` for the Flipper,
- the `UsbSerialPort` for the AWOK direct (when present),
- a single `DataRouter` per port,
- the FAP `loader open "Pen15 Controller"` / ping handshake,
- automatic reconnect on `UsbSerialPort.IOException`.

It exposes one `StateFlow<ConnectionState>`. Every screen observes
it and computes its own enable/disable state from it. No more
three-singletons-racing.

### 5.2 DataRouter: stop dropping bytes

Each port has one router. The router has two modes:

- **JSON mode** (default): bytes accumulate in a buffer, every
  `\n`-terminated line is tried as JSON. Successfully-parsed
  responses are matched by `id` to a pending request. Lines that
  fail JSON parsing are forwarded to the active raw subscriber if
  there is one.
- **Bridge mode**: every byte is forwarded to the raw subscriber.
  The router stops trying to parse JSON. Triggered by
  `FapBridge.start()`, ended by `FapBridge.stop()` (DTR pulse).

Subscribers are scoped to a `coroutineContext` so when the screen
is destroyed the subscription dies cleanly. No more "callback set
on singleton, lifecycle-leaked across activities".

### 5.3 The connection happy path (no user gymnastics)

1. App starts. ConnectionService starts as a foreground service.
2. ConnectionService enumerates USB. For each device:
   - VID 0x0483 → request permission, open as Flipper port at 115200.
   - VID in `{0x10C4, 0x1A86, 0x303A, 0x0403}` → request permission,
     open as AWOK port at 115200.
   - VID matches none of the above but device is CDC-ACM and not
     Espressif → try as Flipper (handles Momentum spoofing).
3. Once Flipper port is open, **automatically** send
   `loader open "Pen15 Controller"\r\n`. Wait 1500 ms. Send JSON
   ping. Retry up to three times. If all three fail, post a
   single non-blocking notification "Tap to launch Pen15 on
   Flipper" with a deep-link to a help screen.
4. As soon as ping returns ok, set `ConnectionState` to
   `FlipperReady` (or `Both` if AWOK is also open). The home
   screen shows green chips.

The user does not have to know the FAP exists. The app launches
it. If launching fails, the app says so in plain language.

---

## 6. Feature surface — full inventory

### 6.1 Flipper screens (one Compose screen each, all driven by `FapClient`)

| Screen | Buttons | Notes |
|--------|---------|-------|
| RFID | READ · WRITE COPY · EMULATE · BRUTE | Brute uses dictionary of known IDs |
| NFC | READ · WRITE · EMULATE · MFKEY32 · MFOC | MFKEY32 reads sniffed nonces from SD |
| Sub-GHz | LISTEN · RECORD · REPLAY · BRUTE · JAM | Frequency picker, big preset chips |
| Infrared | LEARN · BLAST · TV-B-GONE · UNIVERSAL | Universal = AC, projector, audio |
| iButton | READ · EMULATE · BRUTE | Same shape as RFID |
| BadUSB | RUN · LIBRARY · BUILD | Library = curated DuckyScripts; Build = drag-drop |
| GPIO | PIN GRID · UART BRIDGE · I2C SCANNER | Visual 8-pin grid |
| Bluetooth | SCAN · SPOOF · CONNECT | Flipper BLE scanner |

### 6.2 WiFi screens (driven by `MarauderCli` over either AWOK direct or AWOK-over-Flipper)

| Screen | Big button | What it does |
|--------|-----------|--------------|
| Scan | START SCAN | `scanap` → list of APs with SSID / MAC / channel / RSSI |
| Deauth | DEAUTH | Selected AP, `attack -t deauth`, with stop button |
| PMKID Capture | CAPTURE | `sniffpmkid`, save .pcap to SD, optional auto-handoff to crack screen |
| Beacon Spam | SPAM | `attack -t beacon -l rickroll.txt` etc., picker for SSID list |
| Karma | KARMA | `attack -t karma`, captures probe requests |
| Evil Portal | START PORTAL | `evilportal`, picker for HTML template + landing page |
| Probe Sniff | SNIFF | `sniffraw` filtered to probe requests, list of devices |
| Packet Capture | START | Streams pcap from AWOK over UART to phone storage |
| MITM Helper | START | Documents how to combine evil portal + ARP poison |

### 6.3 Crack screens (driven by `TermuxRunner`)

| Screen | Big button | Job |
|--------|-----------|-----|
| Handshake Crack | CRACK | Pick captured .pcap → `hcxpcapngtool` → `hashcat -m 22000` against chosen wordlist |
| Hash Crack | CRACK | Paste/scan hash → identify type → hashcat / john / python fallback |
| Wordlist Manager | DOWNLOAD | rockyou, top10k, top1m, custom URL; SHA-256 verified, cached |

Every crack job runs in a Termux foreground service started via
`com.termux.RUN_COMMAND` with output streamed back to a Compose
log view via a named pipe at
`$HOME/.pen15/jobs/{jobId}/stdout.fifo`.

### 6.4 Recon screens (mostly phone-native, some Termux)

| Screen | Engine | Notes |
|--------|--------|-------|
| OSINT | OkHttp + Termux fallback | HIBP, gravatar, crt.sh, ipinfo |
| Network Scan | Termux nmap | -sn, -A, -sV presets as big chips |
| Google Dork | OkHttp / browser intent | Builder UI, copy-to-clipboard |
| Phone Sensors | Android API | WifiManager, BluetoothManager, NfcAdapter |
| Exploit DB | OkHttp to exploit-db.com | Search, view, copy |

---

## 7. Termux integration without the Termux pain

The pain points from the audit:

- `allow-external-apps=true` is not set by default,
- removed packages,
- monitor mode does not work,
- `gh` crashes.

We solve these by:

1. **First-run Termux check.** On first launch the app pings Termux
   with a no-op intent. If Termux is missing, show "Install Termux
   from F-Droid" with a deep-link. If Termux is installed but
   `RUN_COMMAND` fails, show a screen with the exact two lines the
   user must paste into Termux:

   ```
   mkdir -p ~/.termux && \
     echo "allow-external-apps = true" >> ~/.termux/termux.properties
   termux-reload-settings
   ```

   plus a "Copy to clipboard" button. The app retries automatically
   in the background.

2. **One-tap bootstrap.** The "Crack" screen has a state
   `NEEDS_BOOTSTRAP` that runs `ToolInstaller.installAll()` — a
   single ~150-line bash script that:
   - `pkg update -y && pkg install -y nmap python git build-essential clang`,
   - `pip install hashid hashcat-utils sherlock-project`,
   - clones aircrack-ng + builds it with `make -j4`,
   - tries to fetch the prebuilt hashcat ARM64 release; falls back
     to the Python `hashlib` runner.

   The script's progress is streamed to a Compose terminal view
   with a percentage estimate.

3. **No monitor-mode commands in the UI.** All WiFi capture goes
   through the AWOK. Termux is only for *cracking* the captured
   pcap, not for capturing it. That single decision removes 80 %
   of the Termux flakiness.

4. **Python fallback that always works.** For every cracking job,
   if the binary build fails, we fall back to a Python script that
   uses `hashlib` for fast hashes and a pure-Python aircrack
   port for WPA. Slow, but it always finishes.

---

## 8. Engagement model (the security-company glue)

```kotlin
data class Engagement(
    val id: String,                 // UUID
    val clientName: String,
    val createdAt: Instant,
    val expiresAt: Instant,         // SOW end date
    val scope: Scope,
    val authorizationPdf: Uri,      // generated PDF with signature
    val operatorName: String,
    val notes: String,
    val active: Boolean,
)

data class Scope(
    val ssids: List<String>,
    val bssidsOrPrefixes: List<String>, // full MAC or vendor prefix
    val ipRanges: List<String>,         // CIDR
    val phoneNumbers: List<String>,
    val domains: List<String>,
    val physicalAddresses: List<String>,
)

interface ScopeChecker {
    fun isInScope(ssid: String?): Boolean
    fun isInScope(bssid: MacAddress): Boolean
    fun isInScope(ip: InetAddress): Boolean
    fun isInScope(domain: String): Boolean
}
```

Every disruptive call (`AwokOps.deauth`, `FapClient.subghzTx`,
`FapClient.rfidEmulate`, `FapClient.badusbRun`, `MarauderCli.evilPortal`)
goes through `ScopeChecker` first. Out-of-scope target = a hard stop
with a dialog: "This network is NOT in your engagement scope. Cancel
or override (will be logged)."

Every call (in or out of scope) is appended to the engagement's
`audit.jsonl` with timestamp, operator, target, command bytes,
response bytes (truncated), and outcome. At the end of the
engagement the operator taps "Generate Report" and gets a
`engagement-{id}.zip` containing:

- `authorization.pdf` (signed at start)
- `audit.jsonl` (every action)
- `report.pdf` (executive summary auto-generated from the audit)
- `captures/` (every pcap, sub file, hash file, pdf chunk)

That zip is what the operator hands to the client.

---

## 9. Visual language

- **Material 3** dark theme as default (`pure black background, neon
  green primary, danger red, info cyan, warning yellow`).
- One color file (`ui/theme/Color.kt`) — every palette token defined
  there. Drawables read from token names, not hex codes.
- Big sans-serif geometric type (Inter or similar). Two sizes: 32 sp
  for primary status, 14 sp for body. No tiny 9 sp captions like
  the v3 layouts have.
- All icons from Material Symbols + a small set of bespoke Flipper
  / AWOK glyphs.
- Animations: a single global "thinking" indicator (rotating
  geometric ring) used for every async op. No page-by-page
  animation choices.
- Colorblind-safe: deauth red also has a hatched fill, evil portal
  uses an icon plus color, etc.

---

## 10. Build / CI / release plan

- Keep `/.github/workflows/build.yml`. Bump tag to `v4.0.x`.
- Delete `/.github/workflows/Test AllGpt 2`,
  `/.github/workflows/Test AllGTP`,
  `/.github/workflows/rebuild_and_fix.yml`, and `archive/`.
- Add a `:detekt` step (lint config in repo) so we don't ship
  obvious smells.
- Compose preview snapshots run via Paparazzi on every PR.
- Each release uploads:
  - `pen15-v4.0.{run}.apk`
  - `pen15_controller.fap` (unchanged)
  - `engagement-template.pdf`
  - `bootstrap.sh` (the one-line Termux setup)

---

## 11. Compatibility & migration

- `applicationId` stays `com.pentest.dashboard` to keep installed
  users (`versionCode = 30`, `versionName = "4.0.0"`).
- Internal package moves from `com.pentest.dashboard.*` to
  `com.pen15.*`; the AndroidManifest entry-point activity is
  rewritten.
- The legacy 47 `.kt` files are deleted in this PR. The reasons are
  in the audit report; carrying them forward is the source of the
  current bugs.
- The FAP source under `fap/pen15_controller/` is unchanged.

---

## 12. Out of scope for v4

- iOS port.
- A web dashboard.
- Cloud sync of engagements (privacy: keep client data on-device).
- Custom ROM features that need root.
- A built-in browser for evil portal (we use the AWOK's hosted
  portal; the operator views the captured creds in the app).

---

## 13. Acceptance criteria (definition of done)

A release qualifies as v4.0.0 only when:

1. Plugging in a Flipper running stock or Momentum firmware and
   tapping the home screen produces a green Flipper chip within
   five seconds, **without** the user touching the Flipper
   physical UI.
2. Plugging in an AWOK Dual Mini v3 (CP210x or native ESP32 USB)
   produces a green AWOK chip within five seconds.
3. Tapping any Flipper feature tile and pressing its big button
   returns a result on screen within ten seconds, or shows a
   plain-language failure message with one suggested fix.
4. WiFi → Scan → Deauth on an in-scope SSID actually deauths
   clients on a test network.
5. WiFi → PMKID → Crack chains a captured pcap into hashcat and
   surfaces a plaintext password (when the password is in the
   chosen wordlist) without the user ever opening Termux.
6. Trying to deauth an out-of-scope SSID shows a stop dialog.
7. Ending an engagement produces a zip the operator can email to a
   client without further editing.
8. The whole UI is usable one-handed, all primary buttons are
   ≥ 64 dp tall, and TalkBack reads every screen sensibly.

---

This spec is the contract. The implementation that follows in this
PR builds the foundation: the connection service, the data router,
the engagement model, the home screen, and a working slice of one
Flipper feature (RFID) and one AWOK feature (WiFi scan) end-to-end.
The remaining feature screens are added in follow-up PRs against
the same architecture.
