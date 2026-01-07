# ATTACK WORKFLOWS - UI Design Reference

## WiFi Deauth Attack (Marauder/Flipper)
1. **Connect** → Device connection screen
2. **Scan** → Swipe UP to start
3. **Results** → List of APs found
4. **Select Target** → Tap AP to select
5. **Choose Attack** → Deauth / Handshake Capture
6. **Execute** → Swipe UP to start attack
7. **Monitor** → Real-time stats
8. **Stop** → Swipe DOWN
9. **Follow-ups**:
   - Crack captured handshake?
   - Rescan for more APs?
   - Change target?

## RFID Read/Clone (Flipper)
1. **Connect** → Flipper via USB/BT
2. **Mode Select** → READ / EMULATE / WRITE
3. **Read** → Swipe UP, place card
4. **Results** → Card UID, type, data
5. **Follow-ups**:
   - Save card to Flipper?
   - Emulate this card?
   - Clone to new tag?
   - Analyze data?

## NFC Operations (Flipper)
1. **Connect** → Flipper
2. **Mode** → READ / WRITE / EMULATE
3. **Scan** → Swipe UP, place card
4. **Results** → UID, Type (Mifare/NTAG/etc), Data blocks
5. **Follow-ups**:
   - Read full dump?
   - Write to new card?
   - Emulate?
   - Extract keys (Mifare)?

## SubGHz Capture/Replay (Flipper)
1. **Connect** → Flipper
2. **Frequency Select** → 315/433/868/915 MHz
3. **Listen** → Swipe UP to start receiver
4. **Capture** → Press button on remote
5. **Results** → Signal captured, frequency, modulation
6. **Follow-ups**:
   - Replay signal?
   - Save to Flipper?
   - Analyze waveform?
   - Brute force codes?

## GPIO/Hardware Control (Flipper)
1. **Connect** → Flipper
2. **Pin Config** → Select GPIO pins
3. **Mode** → INPUT / OUTPUT / PWM
4. **Control** → Swipe to read/write
5. **Follow-ups**:
   - Save configuration?
   - Create automation?
   - Monitor continuously?

## BadUSB Payload (Flipper)
1. **Connect** → Flipper
2. **Select Payload** → List of .txt payloads
3. **Preview** → Show payload commands
4. **Execute** → Swipe UP to inject
5. **Monitor** → Real-time execution
6. **Follow-ups**:
   - Run another payload?
   - Edit payload?
   - Create new payload?

## iButton Read/Emulate (Flipper)
1. **Connect** → Flipper
2. **Mode** → READ / EMULATE / WRITE
3. **Read** → Swipe UP, touch iButton
4. **Results** → Key type, ID
5. **Follow-ups**:
   - Emulate key?
   - Save to Flipper?
   - Write to blank iButton?

## Infrared Learn/Replay (Flipper)
1. **Connect** → Flipper
2. **Mode** → LEARN / REPLAY / UNIVERSAL
3. **Learn** → Swipe UP, point remote at Flipper
4. **Results** → IR protocol, command
5. **Follow-ups**:
   - Replay command?
   - Save to library?
   - Try universal remote?

## Network Scanner (Termux)
1. **Configure** → IP range, scan type
2. **Scan** → Swipe UP to start nmap
3. **Progress** → Live host discovery
4. **Results** → Hosts, open ports, services
5. **Follow-ups**:
   - Scan specific host deeper?
   - Export results?
   - Run exploit check?
   - Start packet capture on target?

## Packet Sniffer (Termux)
1. **Interface Select** → wlan0/wlan0mon
2. **Filter** → All / HTTP / FTP / Custom BPF
3. **Capture** → Swipe UP to start
4. **Live Stats** → Packets, size, protocols
5. **Stop** → Swipe DOWN
6. **Results** → PCAP file saved
7. **Follow-ups**:
   - Crack WiFi from pcap?
   - Extract credentials?
   - Analyze with tshark?
   - Convert to CSV?

## Hash Cracker (Termux)
1. **Input** → Paste hash OR select file
2. **Hash Type** → MD5/SHA1/NTLM/etc
3. **Wordlist** → Online (rockyou) or local
4. **Crack** → Swipe UP to start
5. **Progress** → Hashes/sec, ETA
6. **Results** → Cracked passwords
7. **Follow-ups**:
   - Try different wordlist?
   - Use rules?
   - Export results?

## ARP Poisoner (Termux - needs root)
1. **Target Select** → Gateway + victim IP
2. **Configure** → Bidirectional / One-way
3. **Attack** → Swipe UP to start
4. **Monitor** → Traffic being intercepted
5. **Follow-ups**:
   - Start packet capture?
   - Extract credentials?
   - SSL strip?

## Bluetooth Scanner (Flipper)
1. **Connect** → Flipper
2. **Scan** → Swipe UP for BLE devices
3. **Results** → Device names, MACs, RSSI
4. **Follow-ups**:
   - Connect to device?
   - Capture advertisements?
   - Clone MAC?

---

**UI PATTERN FOR ALL:**
- **Swipe UP** = Start operation
- **Swipe DOWN** = Stop operation
- **Results panel** slides up with data
- **Action chips** show contextual next steps
- **Tabs** for different modes (READ/WRITE/SCAN)
