package com.android.pen15.domain

data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val category: ToolCategory,
    val requiresFlipperConnection: Boolean = false,
    val isAvailable: Boolean = true
)

enum class ToolCategory {
    FLIPPER,
    NETWORK,
    CRYPTO,
    UTILITIES
}

enum class ToolStatus {
    READY,
    RUNNING,
    REQUIRES_CONNECTION,
    REQUIRES_INSTALL,
    ERROR
}
