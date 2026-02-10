package com.android.pen15.tools

object SubGhzBruteForce {

    data class Protocol(
        val name: String,
        val bits: Int,
        val frequencies: List<Long>,
        val teValue: Int = 350,
        val preset: String = "FuriHalSubGhzPresetOok650Async"
    )

    val PROTOCOLS = listOf(
        Protocol("CAME", 12, listOf(433920000L, 868350000L), teValue = 320),
        Protocol("NICE", 12, listOf(433920000L, 868350000L), teValue = 700),
        Protocol("Linear", 10, listOf(300000000L, 310000000L), teValue = 500),
        Protocol("Chamberlain", 9, listOf(300000000L, 315000000L), teValue = 1000),
        Protocol("Holtek", 12, listOf(433920000L, 868350000L), teValue = 430),
        Protocol("Ansonic", 12, listOf(433920000L, 434075000L), teValue = 555)
    )

    fun generateSubFile(protocol: Protocol, frequency: Long): String {
        val sb = StringBuilder()
        sb.appendLine("Filetype: Flipper SubGhz RAW File")
        sb.appendLine("Version: 1")
        sb.appendLine("Frequency: $frequency")
        sb.appendLine("Preset: ${protocol.preset}")
        sb.appendLine("Protocol: RAW")

        val totalCodes = 1 shl protocol.bits
        val te = protocol.teValue

        for (code in 0 until totalCodes) {
            val rawLine = StringBuilder("RAW_Data: ")
            rawLine.append("${-te * 36} ") // preamble gap

            for (bit in (protocol.bits - 1) downTo 0) {
                val bitVal = (code shr bit) and 1
                if (bitVal == 1) {
                    rawLine.append("${te * 3} ${-te} ")
                } else {
                    rawLine.append("$te ${-te * 3} ")
                }
            }

            rawLine.append("${-te * 36}") // inter-code gap
            sb.appendLine(rawLine.toString().trim())
        }

        return sb.toString()
    }
}
