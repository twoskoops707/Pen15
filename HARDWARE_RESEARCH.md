# Pen15 Hardware Research & Integration Guide

## 1. FLIPPER ZERO - Core Device

### Physical Specifications
- **Dimensions:** 100.3mm (W) × 40.1mm (H) × 25.6mm (D)
- **Weight:** 102 grams (3.6 ounces)
- **Display:** 128×64 pixel monochrome LCD (1.4" diagonal)
- **Battery:** 2100 mAh LiPo (up to 28 days battery life)
- **Operating Temperature:** 0°C to 40°C (32°F to 104°F)

### Microcontroller
- **MCU:** STM32WB55RG (ARM Cortex-M4 @ 64 MHz + Cortex-M0+ @ 32 MHz)
- **Flash:** 1024 KB (shared between app and radio)
- **SRAM:** 256 KB (shared)
- **Connectivity:** Bluetooth LE 5.4, 802.15.4, proprietary protocols

### Hardware Modules

#### Sub-1 GHz Transceiver (CC1101)
- **Frequency Bands:** 315 MHz, 433 MHz, 868 MHz, 915 MHz (region-dependent)
- **TX Power:** -20 dBm max
- **RX Range:** ~50 meters (expandable with amplifier)
- **Capabilities:** Radio remote control reading/emulation, frequency analysis

#### NFC Module (ST25R3916)
- **Frequency:** 13.56 MHz
- **Supported Standards:** ISO-14443A/B, NFC Forum protocols
- **Card Types:** MIFARE Classic/Ultralight/DESFire, FeliCa, HID iClass, etc.
- **Range:** ~10 cm typical

#### RFID Module (125 kHz)
- **Frequency:** 125 kHz
- **Modulation:** AM, OOK
- **Supported Cards:** EM4100, HID H10301, Indala, AWID, FDX-B, etc.
- **Range:** ~10 cm typical

#### Infrared Module
- **RX Wavelength:** 950 nm (±100 nm)
- **RX Carrier:** 38 kHz (±5%)
- **TX Wavelength:** 940 nm
- **TX Power:** 300 mW
- **Supported Protocols:** NEC, Kaseikyo, RCA, RC5/RC6, Samsung, SIRC

#### iButton Module (1-Wire)
- **Supported Protocols:** Dallas DS199x, DS1971, CYFRAL, Metakom, TM2004, RW1990
- **Interface:** 1-Wire protocol

#### GPIO Expansion
- **Available Pins:** 13 I/O pins on external 2.54mm connectors
- **Voltage:** 3.3V CMOS level (5V tolerant input)
- **Max Current:** 20 mA per digital pin
- **Use Cases:** Custom hardware interfacing, sensor connections, module expansion

### Connectivity
- **USB-C:** USB 2.0 (12 Mbps data transfer, 1 A max charging)
- **Bluetooth LE:** Wireless pairing with mobile apps and accessories
- **MicroSD Card:** Up to 256 GB (recommended 2-32 GB)

---

## 2. AWOK Mini V3 - Expansion Board

### Overview
The AWOK Mini V3 is a professional-grade expansion board designed for Flipper Zero that adds WiFi and Bluetooth capabilities via dual ESP32-WROOM modules.

### Key Features
- **Dual ESP32-WROOM Modules:** Two independent WiFi/BLE processors
- **WiFi Capabilities:** 2.4 GHz 802.11 b/g/n (up to 150 Mbps)
- **Bluetooth:** Dual BLE 5.0 radios
- **GPS Integration:** Optional GPS modules (toggleable via DIP switches)
- **Long-Range Antennas:** Dual external antennas for extended range
- **Touch Screen Option:** AWOK Dual Touch v3 variant includes LCD display
- **Connection:** GPIO expansion via Flipper Zero's 13 I/O pins

### Technical Specifications
- **Processor:** ESP32-WROOM-32 (Xtensa dual-core @ 240 MHz)
- **Memory:** 4 MB Flash, 520 KB SRAM
- **Power:** 3.3V supply from Flipper GPIO
- **Firmware:** Custom Marauder firmware for WiFi wardriving/deauth
- **Data Logging:** SD card support for GPS/WiFi data collection

### Capabilities with Pen15
1. **WiFi Scanning & Analysis**
   - SSID enumeration
   - Signal strength mapping (RSSI)
   - Channel analysis
   - WPA/WEP detection

2. **WiFi Deauthentication**
   - Targeted deauth attacks
   - Beacon flooding
   - Probe request injection

3. **Bluetooth Scanning**
   - BLE device discovery
   - MAC address collection
   - RSSI mapping

4. **GPS Wardriving**
   - Location-based WiFi mapping
   - Geolocation data logging
   - Heat map generation

5. **Packet Capture**
   - Raw packet capture via tshark
   - PCAP file generation
   - Wireshark integration

---

## 3. SubGHz Amplifier - Range Extension

### Purpose
Extends the Flipper Zero's built-in CC1101 SubGHz range from ~50 meters to 200+ meters.

### Technical Details

#### Amplifier Types
1. **CC1101 External Module (Ebyte)**
   - Frequency: 300-348 MHz, 387-464 MHz, 779-928 MHz
   - Output Power: +12 dBm (vs. -20 dBm stock)
   - Range Extension: 4-5x improvement (~50m → 200-250m)
   - Connection: GPIO pins (SPI interface)

2. **CC1352P LaunchPad (TI Reference)**
   - Dual-band: 433/868/915 MHz + 2.4 GHz
   - Higher power output
   - Better sensitivity (-96 dBm RX)
   - More complex integration

#### Key Parameters
- **Frequency Coverage:** 300-928 MHz (covers all Flipper bands)
- **TX Power Gain:** +32 dBm improvement
- **RX Sensitivity:** -96 dBm (excellent for weak signals)
- **Antenna:** SMA connector for external antenna options

### Integration with Pen15
1. **Enhanced SubGHz Reading**
   - Detect remote signals from greater distances
   - Capture weak or distant transmissions
   - Frequency analysis over larger area

2. **SubGHz Transmission**
   - Replay captured signals with greater range
   - Garage door/gate opener attacks
   - Car key fob cloning/replay

3. **Signal Jamming** (where legal)
   - Frequency jamming in specific bands
   - Signal interference testing

---

## 4. Integration Architecture for Pen15

### Hardware Stack
```
┌─────────────────────────────────────┐
│   Android Phone (Pen15 App)         │
├─────────────────────────────────────┤
│   USB-C or Bluetooth Connection     │
├─────────────────────────────────────┤
│   Flipper Zero (Main Controller)    │
│   ├─ CC1101 SubGHz Module           │
│   ├─ ST25R3916 NFC Module           │
│   ├─ 125 kHz RFID Module            │
│   ├─ Infrared Module                │
│   └─ GPIO Expansion Pins            │
├─────────────────────────────────────┤
│   GPIO Expansion (via pins)         │
│   ├─ AWOK Mini V3 (WiFi/BLE)        │
│   └─ SubGHz Amplifier (CC1101 ext)  │
└─────────────────────────────────────┘
```

### Communication Protocols

#### USB Connection (Recommended)
- **Speed:** 12 Mbps (USB 2.0)
- **Latency:** <1ms
- **Power:** Device powered by phone
- **Protocol:** Serial over USB (CDC)
- **Advantages:** Fast, reliable, no pairing needed

#### Bluetooth Connection (Alternative)
- **Speed:** 2 Mbps (Bluetooth LE)
- **Latency:** 5-20ms
- **Power:** Independent battery
- **Protocol:** BLE GATT
- **Advantages:** Wireless freedom, longer range

### Python/pyflipper Integration
```
Pen15 App (Kotlin)
    ↓
TermuxIntegration (Bash)
    ↓
Python Scripts (pyflipper)
    ↓
Flipper RPC Protocol
    ↓
Flipper Zero Hardware
    ↓
Connected Modules (AWOK, Amplifier)
```

---

## 5. Pen15 Module Mapping

| Module | Hardware | Frequency | Range | Python Script |
|--------|----------|-----------|-------|----------------|
| SubGHz | CC1101 + Amplifier | 300-928 MHz | 200-250m | `subghz_receive.py` |
| RFID | 125 kHz Module | 125 kHz | 10-20cm | `rfid_read.py` |
| NFC | ST25R3916 | 13.56 MHz | 10cm | `nfc_read.py` |
| Infrared | IR Module | 950nm | 5-10m | `infrared_learn.py` |
| iButton | 1-Wire | 1-Wire | Contact | `ibutton_read.py` |
| WiFi | AWOK Mini V3 | 2.4 GHz | 100-200m | `wifi_scan.py` |
| Bluetooth | AWOK Mini V3 | 2.4 GHz | 100-200m | `bluetooth_scan.py` |
| GPIO | Expansion Pins | 3.3V | Local | `gpio_control.py` |

---

## 6. Recommended Configuration for Pen15

### Optimal Setup
1. **Flipper Zero** - Core device (USB-C to phone)
2. **AWOK Mini V3** - Connected via GPIO for WiFi/BLE
3. **SubGHz Amplifier** - Connected via GPIO for extended range
4. **External Antennas** - SMA connectors for better reception

### Power Management
- **Primary:** Phone USB-C provides power to Flipper
- **Secondary:** Flipper battery as backup
- **AWOK/Amplifier:** Powered from Flipper GPIO (3.3V)

### Data Flow
1. Pen15 app sends command via USB/Bluetooth
2. Flipper receives via RPC protocol
3. Appropriate module executes operation
4. Results returned to app via pyflipper
5. App parses and displays in UI

---

## 7. Future Expansion Possibilities

- **NRF24 Module:** 2.4 GHz custom protocol scanning
- **GPS Module:** Location-based attack mapping
- **UART/I2C Sensors:** Environmental monitoring
- **Custom Firmware:** Marauder for WiFi wardriving
- **Signal Analysis:** Software-defined radio (SDR) capabilities

---

## References

- [Flipper Zero Official Docs](https://docs.flipper.net/zero)
- [AWOK Dynamics](https://awokdynamics.com/)
- [pyflipper Library](https://github.com/flipperdevices/pyflipper)
- [CC1101 Datasheet](https://www.ti.com/product/CC1101)
- [CC1352P Reference](https://www.ti.com/product/CC1352P)
- [ESP32 Documentation](https://docs.espressif.com/projects/esp-idf/)

