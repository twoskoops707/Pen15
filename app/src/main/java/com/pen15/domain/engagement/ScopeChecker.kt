package com.pen15.domain.engagement

/** Predicate suite for "is this target authorized by the active engagement?" */
class ScopeChecker(private val scope: Scope) {

    fun isInScope(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        return scope.ssids.any { it.equals(ssid, ignoreCase = true) }
    }

    fun isInScope(bssid: String): Boolean {
        val normalized = bssid.uppercase().replace("-", ":")
        return scope.bssidPrefixes.any { p ->
            val pp = p.uppercase().replace("-", ":")
            normalized.startsWith(pp)
        }
    }

    fun isInScope(domain: String): Boolean =
        scope.domains.any { d -> domain.equals(d, ignoreCase = true) || domain.endsWith(".$d", ignoreCase = true) }

    fun isInScope(phone: String): Boolean {
        val digits = phone.filter { it.isDigit() }
        return scope.phoneNumbers.any { p -> p.filter { c -> c.isDigit() } == digits }
    }
}
