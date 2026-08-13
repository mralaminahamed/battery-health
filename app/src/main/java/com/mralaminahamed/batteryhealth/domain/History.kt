package com.mralaminahamed.batteryhealth.domain

data class LevelPoint(val timestampMs: Long, val levelPct: Int)

enum class HistoryRange(val windowMs: Long, val label: String) {
    Day(24L * 60 * 60 * 1000, "24 hours"),
    Week(7L * 24 * 60 * 60 * 1000, "7 days"),
    Month(30L * 24 * 60 * 60 * 1000, "30 days"),
}
