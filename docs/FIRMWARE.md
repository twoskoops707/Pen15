# Flipper Zero Firmware Compatibility — Pen15 Controller FAP

## Minimum required

- **Flipper Zero F7** with **external FAP support** (all current stock, Momentum, and Unleashed builds)
- **USB CDC serial** enabled when connected to phone (OTG)
- SD card with FAP installed at `/apps/Tools/pen15_controller.fap`

## CI build target

GitHub Actions builds the FAP with:

```yaml
sdk-channel: release   # official Flipper ufbt release SDK
app-dir: fap/pen15_controller
```

- **FAP version:** 2.1 (`application.fam`)
- **Requires:** `gui`, `storage`
- **APIs used:** `cli_vcp_disable`, `furi_hal_cdc_*`, LFRFID, NFC scanner, SubGHz worker, GPIO ext pins, USART DMA

## Recommended firmware

| Fork | Notes |
|------|--------|
| **Stock (Flipper official release)** | Best match for CI-built FAP |
| **Momentum** | Works if SDK API matches release; USB VID/PID may be spoofed — Pen15 APK probes CDC-ACM |
| **Unleashed** | Same as Momentum — use release-built FAP first |

## User must update firmware?

**Usually NO** if you already run a recent fork (2024+) with external apps enabled.

**YES — update or rebuild FAP** if:

- FAP shows **Invalid file** or fails to launch → SDK mismatch; update firmware **or** rebuild FAP with `ufbt` using your fork's SDK channel
- **Pen15 Controller** missing from Apps → Tools → copy FAP to SD card path above
- USB opens but **FAP never pings** → open Pen15 Controller manually on Flipper; ensure `cli_vcp` is not held by another app

## GPIO / UART (AWOK bridge)

- **GPIO UART:** Flipper pins **13 (TX)** and **14 (RX)** + GND — same on stock, Momentum, Unleashed
- **Pen15 FAP** owns USB CDC when running (`cli_vcp_disable`); phone must use JSON/ping before `uart_bridge`
- **AWOK direct USB** bypasses Flipper (115200 baud, newline-terminated Marauder CLI)

## ACTION REQUIRED (user)

If FAP will not load after installing from release:

1. Update Flipper to the **latest release** of your firmware fork (Momentum recommended if already using it)
2. Re-download `pen15_controller.fap` from the matching GitHub release
3. Reboot Flipper, launch **Pen15 Controller**, then connect USB OTG to phone
