package com.android.pen15.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.coroutines.coroutineContext

object WpaCracker {

    data class CrackResult(
        val found: Boolean,
        val password: String = "",
        val attempts: Long = 0,
        val rate: Double = 0.0
    )

    suspend fun crackPmkid(
        ssid: String,
        pmkid: ByteArray,
        clientMac: ByteArray,
        apMac: ByteArray,
        wordlist: List<String>,
        onProgress: (attempted: Long, total: Long, currentWord: String) -> Unit
    ): CrackResult = withContext(Dispatchers.Default) {
        val total = wordlist.size.toLong()
        var attempted = 0L
        val startTime = System.currentTimeMillis()

        for (word in wordlist) {
            if (!coroutineContext.isActive) break

            attempted++
            if (attempted % 100 == 0L) {
                onProgress(attempted, total, word)
            }

            val pmk = derivePmk(word, ssid)
            val computedPmkid = computePmkid(pmk, apMac, clientMac)

            if (computedPmkid.contentEquals(pmkid)) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val rate = if (elapsed > 0) attempted / elapsed else 0.0
                return@withContext CrackResult(true, word, attempted, rate)
            }
        }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if (elapsed > 0) attempted / elapsed else 0.0
        CrackResult(false, attempts = attempted, rate = rate)
    }

    private fun derivePmk(password: String, ssid: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), ssid.toByteArray(), 4096, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded
    }

    private fun computePmkid(pmk: ByteArray, apMac: ByteArray, clientMac: ByteArray): ByteArray {
        val data = "PMK Name".toByteArray() + apMac + clientMac
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(pmk, "HmacSHA1"))
        return mac.doFinal(data).copyOf(16)
    }
}
