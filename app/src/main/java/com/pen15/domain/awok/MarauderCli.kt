package com.pen15.domain.awok

import com.pen15.domain.connection.DataRouter
import com.pen15.domain.connection.UsbPort
import com.pen15.domain.flipper.FapClient
import kotlinx.coroutines.flow.Flow

/**
 * Routes ESP32 Marauder commands either:
 *   - directly over the AWOK [UsbPort], or
 *   - through the Flipper [FapClient]'s UART bridge.
 *
 * Audit §7 + §8 fix: the routing decision lives here, not scattered
 * across activities. Consumers ask for `output` and get a single
 * SharedFlow regardless of which transport is in use.
 */
class MarauderCli private constructor(
    private val send: (String) -> Unit,
    val output: Flow<String>,
) {

    fun raw(cmd: String) {
        val terminated = if (cmd.endsWith("\r\n")) cmd else "$cmd\r\n"
        send(terminated)
    }

    // High-level shortcuts; all commands documented in CLAUDE.md
    fun scanAp()          = raw("scanap")
    fun stopScan()         = raw("stopscan")
    fun selectAp(idx: Int) = raw("select -a $idx")
    fun deauth()           = raw("attack -t deauth")
    fun pmkid()            = raw("sniffpmkid")
    fun beaconSpam(list: String) = raw("attack -t beacon -l $list")
    fun karma()            = raw("attack -t karma")
    fun evilPortal()       = raw("evilportal")
    fun probeReqSniff()    = raw("sniffraw")
    fun bleScan()          = raw("blescan")
    fun bleSpam()          = raw("blespam")
    fun help()             = raw("help")
    fun reboot()           = raw("reboot")
    fun setChannel(ch: Int) = raw("channel $ch")

    companion object {
        /** Build a CLI bound to the AWOK direct USB port. */
        fun direct(port: UsbPort): MarauderCli =
            MarauderCli(send = { port.writeText(it) }, output = port.router.rawLines)

        /**
         * Build a CLI bound to the Flipper FAP bridge.
         * Caller must ensure `client.uartInit()` has been called first.
         */
        fun viaFlipper(client: FapClient): MarauderCli =
            MarauderCli(
                send = { s -> client.port.writeText(s) },
                output = client.rawFrames,
            ).also { client.setRouterMode(DataRouter.Mode.Bridge) }
    }
}
