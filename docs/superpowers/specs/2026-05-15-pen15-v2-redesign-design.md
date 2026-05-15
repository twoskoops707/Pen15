# Pen15 v2 — Ground-Up Redesign Design

**Date:** 2026-05-15  
**Status:** Draft for stakeholder review  
**Scope:** Product vision, architecture, connectivity model, UI/UX direction, and phased delivery. Does not replace in-repo bugfix work until this document is approved.

---

## 1. Purpose and ethics

Pen15 is intended as a **professional, consent-based** physical-security demonstration tool: Flipper Zero plus AWOK Mini v3, controlled from an Android phone over USB OTG, for **your hardware** or **client hardware under written authorization**. The app must make authorization, logging, and safe teardown **first-class**, not afterthoughts.

This redesign treats **reliability of the USB/FAP/UART stack** as the product’s spine; everything else hangs off that.

---

## 2. What the current codebase is trying to be

- **Flipper path:** USB serial (115200 8N1, DTR/RTS) to a custom Flipper FAP (`fap/pen15_controller/pen15_controller.c`) that can speak **JSON** for structured actions (RFID, NFC, SubGHz, IR, iButton, storage, `uart_init`, `uart_send`, etc.) and can enter **bridge mode** to forward bytes between USB CDC and GPIO UART (pins toward AWOK).
- **AWOK path A (via Flipper):** Phone → Flipper USB → FAP → USART/GPIO → AWOK Marauder CLI (`scanap`, `select`, `attack -t deauth`, …).
- **AWOK path B (direct USB):** Phone → CP2102/CH340/etc. on a standalone ESP32 USB device (`ESP32SerialManager`).
- **Recon / heavy tools:** Termux (`ProcessManager`, `TermuxIntegration`) for nmap, OSINT scripts, hash tools, etc., with scoped storage and inline scripts to avoid Android 11 permission traps.

`docs/SESSION_NOTES.md` and `docs/REFACTOR_PLAN.md` capture historical pain: CLI handshake hell (addressed by FAP+JSON), CDC re-enumeration when the FAP takes VCP, ordering of FAP vs USB, and AWOK init only after bridge start. A focused **code review** of the current tree confirms: `FapProtocol.onData` is wired from `FlipperUSBManager` (so the old “FlipperHAL.init never called” story is largely **stale**), but **CDC reopen after handoff**, **stub `initSession`**, **raw vs JSON on the same link**, and **two AWOK topologies conflated in the UI** remain real risks.

---

## 3. Core problems (consolidated)

| Area | Issue |
|------|--------|
| **Transport contract** | Marauder commands sometimes go as **raw bytes** (`FlipperGPIOBridge.sendMarauderCommand` → `sendRawBytes`) while the rest of the session uses **JSON** (`FapProtocol`). FAP must guarantee mutually exclusive modes or the phone must use one API only. |
| **USB CDC lifecycle** | `reopenPort()` exists in `FlipperUSBManager` but is **never called**; FAP `cli_vcp_disable` / mode changes can leave the host port stale. |
| **Session API** | `FlipperConnectionManager.initSession` is a **stub**; docs still describe a real handshake. |
| **Product clarity** | Dashboard “AWOK” chip tracks **direct USB ESP32 only**; AWOK **behind Flipper GPIO** does not light that chip but can still work via bridge — reads as “AWOK dead.” |
| **Bluetooth** | `sendRawBytes` over BT converts bytes to UTF-8 `String` — unsafe for binary / noisy Marauder streams. |
| **UX surface** | Many activities, neon/terminal metaphors, inconsistent connection gating; high cognitive load vs “one obvious flow” for demos. |
| **Termux** | Powerful but fragile (permissions, F-Droid builds, `RUN_COMMAND`, no guarantee on client phones). Fine as **optional power mode**, poor as **required core**. |

---

## 4. Target experience (v2)

### 4.1 Primary persona

Security consultant on site: plug Flipper + pigtail to AWOK, plug phone OTG, **three taps** to a live “mission” screen: connection health, Flipper+FAP version, AWOK path (GPIO vs direct), and **big safe actions** (scan, select, deauth demo on authorized lab AP, stop all).

### 4.2 Information architecture

1. **Connect** — Single screen: USB permission, device cards (Flipper / AWOK-direct), FAP status (ping + version), explicit “AWOK via Flipper GPIO” vs “AWOK USB” labels, troubleshooting copy from `HardwareGuide` distilled into inline steps.
2. **Operate** — Mode-based flows (Wi-Fi lab, RFID lab, …) with shared **connection banner** and **emergency stop**.
3. **Report** — Session timeline (commands, responses, timestamps), export (JSON/CSV) to app storage / share sheet.
4. **Settings** — Termux optional, feature flags, legal disclaimer acknowledgment.

### 4.3 UI / UX technology (Android, store-quality direction)

- **Material 3** (`Material3` theme, `DynamicColor` on Android 12+, light/dark) with a **calm professional** palette (not “hacker neon” as default; optional “cyber” theme later).
- **Jetpack Compose** for new screens where feasible; incremental migration from XML (start with Connect + one pilot flow) to avoid a Big Bang rewrite.
- **Navigation Compose** + single `Activity` + typed routes for deep links and testability.
- **Design tokens** in `ui/theme` (spacing, typography `TypeScale`, motion via `MotionScheme`).
- **Accessibility:** TalkBack labels, large touch targets (min 48dp), high-contrast mode.

### 4.4 Connection and protocol layer (v2 architecture)

Introduce a **`FlipperSession` state machine** (kotlin sealed class + explicit transitions):

1. `Disconnected` → user grants USB → `UsbOpen`
2. `UsbOpen` → DTR/RTS pulse → `FapProbe` (ping JSON, timeout, retries **with** optional `reopenPort` between retries)
3. `FapReady` → user chooses **Marauder path**:  
   - **Bridge:** `uart_init` then **only** `FapProtocol.uartSend` (or a dedicated `bridge_send` JSON that wraps line payloads) — **no raw `sendBytes` on the same interface** unless FAP documents transparent mode exclusively.  
   - **Direct AWOK:** separate `Esp32Session` with its own I/O manager and parsers.

**Implement or delete** `initSession`: either real (ping, caps, firmware strings) or removed and docs updated.

**Binary-safe BT:** if bridge-over-BT is supported, base64-in-JSON or length-prefixed frames — never blind UTF-8 cast.

---

## 5. Linux / Termux / “Python instead”

| Option | Role |
|--------|------|
| **Keep Termux** | Optional “Pro” module: documented setup, version-pinned scripts, graceful degradation when Termux missing. |
| **In-app** | Pure Kotlin for parsing, wordlists from storage, simple hash/id tools — no shell. |
| **On-device Python** | Chaquopy / Kivy / BeeWare are heavy for store policy and binary size; recommend **not** as core v2 unless there is a clear ROI. |
| **Fish shell** | Irrelevant on Android host; Termux users can choose their shell; app should not depend on it. |

Recommendation: **v2 core = Kotlin + USB + optional Termux intents**; revisit embedded Python only if a specific compliance or performance requirement appears.

---

## 6. Flipper FAP alignment

- Single documented contract: JSON line protocol + explicit `bridge_mode` behavior; Android side enforced by `FlipperSession`.
- FAP: ensure `uart_send` / bridge responses cannot interleave malformed JSON on the CDC IN endpoint; add sequence numbers if needed.
- Verify `cdc_ctrl_cb` / DTR ordering with `FlipperGPIOBridge.stopBridge()` (reviewer flag).

---

## 7. Phased delivery (no calendar estimates)

**Phase 0 — Truth in docs and API**  
Align `SESSION_NOTES` / `REFACTOR_PLAN` with code; wire `reopenPort` + re-ping experiment behind a feature flag; add UI labels for AWOK topology.

**Phase 1 — Session state machine + single Marauder API**  
Implement `FlipperSession`; unify Marauder sends through one path; unit tests for JSON framing.

**Phase 2 — Connect screen + Compose pilot**  
New Connect experience; migrate Wi-Fi deauth pilot to shared session + M3 layout.

**Phase 3 — Navigation + reporting**  
Single-activity shell, session export, “stop all hardware” action.

**Phase 4 — Theming and polish**  
Visual refresh, optional themes, Play policy checklist (permissions rationale, data safety).

---

## 8. Success criteria

- **Reliability:** Documented order of operations (FAP vs USB) with automated retry and user-visible state; measurable reduction in “silent failure” reports.
- **Clarity:** Users with AWOK-on-GPIO never think the app failed USB enumeration because the AWOK chip tile is off.
- **Safety:** Prominent authorization reminder + session logging defaults on for pro features.
- **Maintainability:** One session owner; dead code (`initSession` stub or real impl) eliminated; FAP and app version negotiated at connect.

---

## 9. Open decisions (need your input after review)

1. **Minimum supported Android** version (recommend 8+ or 10+ based on USB stack).
2. **Play Store vs sideload-only** — affects Termux coupling and dangerous-permission narrative.
3. **Compose migration pace** — pilot-only vs full rewrite schedule.

---

## 10. Approval gate

Implementation work beyond Phase 0 should not start until this spec is explicitly approved or revised. After approval, use the **writing-plans** skill to break Phase 0–1 into concrete tasks and acceptance tests.
