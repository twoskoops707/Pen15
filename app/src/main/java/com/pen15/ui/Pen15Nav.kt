package com.pen15.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pen15.ui.crack.HandshakeCrackScreen
import com.pen15.ui.crack.HashCrackScreen
import com.pen15.ui.crack.CrackHomeScreen
import com.pen15.ui.engagement.EngagementListScreen
import com.pen15.ui.engagement.EngagementWizardScreen
import com.pen15.ui.flipper.FlipperHomeScreen
import com.pen15.ui.flipper.RfidScreen
import com.pen15.ui.flipper.NfcScreen
import com.pen15.ui.flipper.SubGhzScreen
import com.pen15.ui.flipper.IrScreen
import com.pen15.ui.flipper.IButtonScreen
import com.pen15.ui.flipper.BadUsbScreen
import com.pen15.ui.flipper.GpioScreen
import com.pen15.ui.flipper.BluetoothScreen
import com.pen15.ui.home.HomeScreen
import com.pen15.ui.recon.ReconHomeScreen
import com.pen15.ui.wifi.WifiHomeScreen
import com.pen15.ui.wifi.WifiScanScreen
import com.pen15.ui.wifi.DeauthScreen
import com.pen15.ui.wifi.PmkidScreen
import com.pen15.ui.wifi.EvilPortalScreen
import com.pen15.ui.wifi.BeaconSpamScreen
import com.pen15.ui.wifi.KarmaScreen
import com.pen15.ui.wifi.PacketCaptureScreen
import com.pen15.ui.wifi.MitmScreen
import com.pen15.ui.wifi.BleSpamScreen

object Routes {
    const val HOME = "home"
    const val ENGAGEMENT_LIST = "engagement/list"
    const val ENGAGEMENT_NEW  = "engagement/new"

    const val FLIPPER = "flipper"
    const val FLIPPER_RFID = "flipper/rfid"
    const val FLIPPER_NFC  = "flipper/nfc"
    const val FLIPPER_SUBGHZ = "flipper/subghz"
    const val FLIPPER_IR = "flipper/ir"
    const val FLIPPER_IBUTTON = "flipper/ibutton"
    const val FLIPPER_BADUSB = "flipper/badusb"
    const val FLIPPER_GPIO = "flipper/gpio"
    const val FLIPPER_BT = "flipper/bt"

    const val WIFI = "wifi"
    const val WIFI_SCAN = "wifi/scan"
    const val WIFI_DEAUTH = "wifi/deauth"
    const val WIFI_PMKID = "wifi/pmkid"
    const val WIFI_EVIL = "wifi/evil"
    const val WIFI_BEACON = "wifi/beacon"
    const val WIFI_KARMA = "wifi/karma"
    const val WIFI_PCAP = "wifi/pcap"
    const val WIFI_MITM = "wifi/mitm"
    const val WIFI_BLE_SPAM = "wifi/blespam"

    const val CRACK = "crack"
    const val CRACK_HANDSHAKE = "crack/handshake"
    const val CRACK_HASH = "crack/hash"

    const val RECON = "recon"
}

@Composable
fun Pen15Nav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }

        composable(Routes.ENGAGEMENT_LIST) { EngagementListScreen(nav) }
        composable(Routes.ENGAGEMENT_NEW)  { EngagementWizardScreen(nav) }

        composable(Routes.FLIPPER)         { FlipperHomeScreen(nav) }
        composable(Routes.FLIPPER_RFID)    { RfidScreen(nav) }
        composable(Routes.FLIPPER_NFC)     { NfcScreen(nav) }
        composable(Routes.FLIPPER_SUBGHZ)  { SubGhzScreen(nav) }
        composable(Routes.FLIPPER_IR)      { IrScreen(nav) }
        composable(Routes.FLIPPER_IBUTTON) { IButtonScreen(nav) }
        composable(Routes.FLIPPER_BADUSB)  { BadUsbScreen(nav) }
        composable(Routes.FLIPPER_GPIO)    { GpioScreen(nav) }
        composable(Routes.FLIPPER_BT)      { BluetoothScreen(nav) }

        composable(Routes.WIFI)            { WifiHomeScreen(nav) }
        composable(Routes.WIFI_SCAN)       { WifiScanScreen(nav) }
        composable(Routes.WIFI_DEAUTH)     { DeauthScreen(nav) }
        composable(Routes.WIFI_PMKID)      { PmkidScreen(nav) }
        composable(Routes.WIFI_EVIL)       { EvilPortalScreen(nav) }
        composable(Routes.WIFI_BEACON)     { BeaconSpamScreen(nav) }
        composable(Routes.WIFI_KARMA)      { KarmaScreen(nav) }
        composable(Routes.WIFI_PCAP)       { PacketCaptureScreen(nav) }
        composable(Routes.WIFI_MITM)       { MitmScreen(nav) }
        composable(Routes.WIFI_BLE_SPAM)   { BleSpamScreen(nav) }

        composable(Routes.CRACK)           { CrackHomeScreen(nav) }
        composable(Routes.CRACK_HANDSHAKE) { HandshakeCrackScreen(nav) }
        composable(Routes.CRACK_HASH)      { HashCrackScreen(nav) }

        composable(Routes.RECON)           { ReconHomeScreen(nav) }
    }
}
