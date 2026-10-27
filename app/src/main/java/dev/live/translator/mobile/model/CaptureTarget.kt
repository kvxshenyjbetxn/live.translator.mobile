package dev.live.translator.mobile.model

data class CaptureTarget(
    val packageName: String,
    val label: String,
    val uid: Int,
    val available: Boolean = true,
)

data class ResolvedCaptureTarget(
    val packageName: String,
    val label: String,
    val uid: Int,
)
