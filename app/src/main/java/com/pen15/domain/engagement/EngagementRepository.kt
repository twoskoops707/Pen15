package com.pen15.domain.engagement

import android.content.Context
import com.pen15.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-backed engagement store. Each engagement is a JSON file under
 * `{externalFilesDir}/engagements/{id}/manifest.json`.
 *
 * Audit §11 fix: every disruptive action goes through the active
 * engagement so we can write provenance into `audit.jsonl`.
 */
object EngagementRepository {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _all = MutableStateFlow<List<Engagement>>(emptyList())
    val all: StateFlow<List<Engagement>> = _all.asStateFlow()

    private val _active = MutableStateFlow<Engagement?>(null)
    val active: StateFlow<Engagement?> = _active.asStateFlow()

    fun init(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        loadAll()
    }

    fun create(
        clientName: String,
        operatorName: String,
        scope: Scope,
        notes: String,
        durationDays: Int,
    ): Engagement {
        val now = System.currentTimeMillis()
        val e = Engagement(
            id = UUID.randomUUID().toString().take(8),
            clientName = clientName,
            operatorName = operatorName,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + durationDays.toLong() * 24L * 3600L * 1000L,
            scope = scope,
            notes = notes,
            authorizationConfirmed = true,
            signaturePngPath = null,
            active = true,
        )
        save(e)
        loadAll()
        _active.value = e
        return e
    }

    fun setActive(id: String?) {
        _active.value = id?.let { _all.value.firstOrNull { e -> e.id == id } }
    }

    fun endActive() {
        val curr = _active.value ?: return
        save(curr.copy(active = false))
        loadAll()
        _active.value = null
    }

    private fun save(e: Engagement) {
        val dir = StorageManager.engagement(e.id)
        File(dir, "manifest.json").writeText(json.encodeToString(e))
    }

    private fun loadAll() {
        val dir = StorageManager.engagements()
        val list = (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { sub ->
                runCatching {
                    json.decodeFromString<Engagement>(File(sub, "manifest.json").readText())
                }.getOrNull()
            }
            .sortedByDescending { it.createdAtEpochMs }
        _all.value = list
        if (_active.value == null) _active.value = list.firstOrNull { it.active }
    }
}
