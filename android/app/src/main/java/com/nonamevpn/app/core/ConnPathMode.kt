package com.nonamevpn.app.core

/**
 * User-selected tunnel strategy (Settings → Режим подключения).
 * Independent from Path B TURN dial (vkcalls / legacy).
 */
enum class ConnPathMode {
    /** Probe chooses Direct (AWG) when healthy, else Bypass (WDTT). */
    Auto,

    /** Always Path A — AmneziaWG. */
    Direct,

    /** Always Path B — WDTT / звонок. */
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
