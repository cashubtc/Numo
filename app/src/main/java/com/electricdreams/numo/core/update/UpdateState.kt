package com.electricdreams.numo.core.update

enum class UpdatePhase {
    IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val versionName: String = "",
    val progress: Int = 0,
    val releaseNotes: String = "",
    val message: Int? = null,
    val canCancelInstall: Boolean = false,
)
