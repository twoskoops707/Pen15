package com.pen15.domain.awok

/**
 * Parses ESP32 Marauder `scanap` output incrementally.
 * The Marauder UART output is line-based but irregular: each AP shows up
 * roughly as `<idx> <ssid> <bssid> <channel> <rssi>` with variable
 * whitespace.
 *
 * Pulled out of `WiFiDeauthActivity` so it can be unit-tested.
 */
object ScanResultParser {

    private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    data class Ap(val idx: Int, val ssid: String, val bssid: String, val channel: Int, val rssi: Int)

    fun parse(buffer: String): List<Ap> {
        val seen = LinkedHashMap<String, Ap>()
        for (raw in buffer.lineSequence()) {
            val line = raw.replace('|', ' ').trim()
            if (line.isEmpty()) continue
            val mac = MAC.find(line)?.value ?: continue
            // Index = leading integer, if any.
            val idx = Regex("^\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            // RSSI = the lowest negative integer in the line.
            val rssi = Regex("-\\d+").findAll(line).mapNotNull { it.value.toIntOrNull() }
                .filter { it < 0 && it > -120 }.minOrNull() ?: -99
            // Channel = small int 1..165 not equal to rssi.
            val channel = Regex("\\b(\\d{1,3})\\b").findAll(line)
                .mapNotNull { it.value.toIntOrNull() }
                .firstOrNull { it in 1..165 && it != idx } ?: 0
            // SSID = the longest token between idx and the MAC, dropping numbers.
            val before = line.substringBefore(mac).trim()
            val ssid = before
                .removePrefix(idx.toString())
                .trim()
                .ifEmpty { "<hidden>" }
                .take(32)
            seen[mac] = Ap(idx, ssid, mac, channel, rssi)
        }
        return seen.values.toList()
    }
}
