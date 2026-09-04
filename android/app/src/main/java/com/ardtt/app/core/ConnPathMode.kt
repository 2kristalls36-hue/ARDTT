package com.ardtt.app.core

/**
 * User-selected tunnel strategy (Settings → Режим подключения).
 * Independent from Path B TURN dial (vkcalls / legacy).
 */
enum class ConnPathMode {
    /** Probe chooses Direct (AWG) when healthy, else Bypass (RAW via TURN). Wi‑Fi Auto always Direct. */
    Auto,

    /** Always Path A — AmneziaWG. */
    Direct,

    /** Always Path B — RAW Dial via TURN / звонок. */
    Bypass,
    ;

    companion object {
        fun fromSetting(raw: String?): ConnPathMode = when (raw?.lowercase()?.trim()) {
            "direct", "awg" -> Direct
            "bypass", "wdtt" -> Bypass
            else -> Auto
        }

        fun toSetting(mode: ConnPathMode): String = when (mode) {
            Auto -> "auto"
            Direct -> "direct"
            Bypass -> "bypass"
        }
    }
}
