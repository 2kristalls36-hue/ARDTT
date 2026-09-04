package com.ardtt.app.deploy

/** Live cascade hop tracker for the deploy progress sheet. */
data class DeployHopTrack(
    val cascade: Boolean = false,
    val entryHost: String = "",
    val exitHost: String = "",
    val activeHost: String = "",
    val entryDone: Boolean = false,
    val exitDone: Boolean = false,
) {
    companion object {
        fun from(target: DeployTarget) = DeployHopTrack(
            cascade = target.cascadeEnabled,
            entryHost = target.host.trim(),
            exitHost = target.cascadeHost.trim(),
        )
    }
}

/** Step line and percent under the deploy progress bar. */
object DeployProgressCopy {
    fun percentLabel(fraction: Float): String = "${DeployShade.progressPercent(fraction)}%"

    fun slotLabel(track: DeployHopTrack, host: String): String? {
        if (!track.cascade) return null
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return null
        return when {
            DeployHop.same(trimmed, track.exitHost) -> "VPS 2"
            DeployHop.same(trimmed, track.entryHost) -> "VPS 1"
            else -> null
        }
    }

    fun step(track: DeployHopTrack, host: String, detail: String): String {
        val ip = DeployHop.host(host)?.takeIf { it.isNotBlank() } ?: host.trim()
        val slot = slotLabel(track, host)
        val cleaned = stripHostPrefix(detail.trim(), host, ip)
        return listOfNotNull(
            slot,
            ip.takeIf { it.isNotBlank() },
            cleaned.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    private fun stripHostPrefix(detail: String, host: String, ip: String): String {
        var text = detail
        for (token in listOf(host.trim(), ip).filter { it.isNotBlank() }.distinct()) {
            val dotted = "$token · "
            if (text.startsWith(dotted)) text = text.removePrefix(dotted)
        }
        return text.trim()
    }
}
