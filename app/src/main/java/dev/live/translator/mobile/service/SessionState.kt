package dev.live.translator.mobile.service

enum class SessionPhase { IDLE, STARTING, RUNNING, STOPPING, ERROR }

data class SessionState(val phase: SessionPhase = SessionPhase.IDLE, val message: String = "Готово.")
