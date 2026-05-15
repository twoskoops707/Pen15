package com.pen15.domain.engagement

import kotlinx.serialization.Serializable

@Serializable
data class Scope(
    val ssids: List<String> = emptyList(),
    val bssidPrefixes: List<String> = emptyList(),
    val ipRanges: List<String> = emptyList(),       // CIDR
    val phoneNumbers: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val physicalAddresses: List<String> = emptyList(),
)

@Serializable
data class Engagement(
    val id: String,
    val clientName: String,
    val operatorName: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val scope: Scope,
    val notes: String,
    val authorizationConfirmed: Boolean,
    val signaturePngPath: String?,
    val active: Boolean,
)
