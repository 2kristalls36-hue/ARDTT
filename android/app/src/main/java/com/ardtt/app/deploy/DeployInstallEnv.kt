package com.ardtt.app.deploy

/** Shell env + installer invocation uploaded to the VPS. Never includes SSH passwords. */
object DeployInstallEnv {
    const val CASCADE_DNS = "10.10.0.2"
    const val CASCADE_LISTEN_PORT = 51820

    fun command(
        publicHost: String,
        directPort: Int,
        bypassPort: Int,
        deployVersion: String,
        role: String,
        cascadeEnabled: Boolean = false,
        cascadeListenPort: Int = CASCADE_LISTEN_PORT,
        cascadePeerEndpoint: String = "",
        cascadePeerPublicKey: String = "",
        cascadeDns: String = CASCADE_DNS,
    ): String = buildString {
        append("ARDTT_PUBLIC_HOST="); append(SshClient.shellQuote(publicHost)); append(' ')
        append("ARDTT_DIRECT_PORT="); append(directPort); append(' ')
        append("ARDTT_BYPASS_PORT="); append(bypassPort); append(' ')
        append("ARDTT_DEPLOY_VERSION="); append(SshClient.shellQuote(deployVersion)); append(' ')
        append("ARDTT_ROLE="); append(SshClient.shellQuote(role)); append(' ')
        append("ARDTT_CASCADE_ENABLED="); append(if (cascadeEnabled) "1" else "0"); append(' ')
        if (cascadeEnabled) {
            append("ARDTT_CASCADE_LISTEN_PORT="); append(cascadeListenPort); append(' ')
            append("ARDTT_CASCADE_DNS="); append(SshClient.shellQuote(cascadeDns)); append(' ')
            if (cascadePeerEndpoint.isNotBlank()) {
                append("ARDTT_CASCADE_PEER_ENDPOINT=")
                append(SshClient.shellQuote(cascadePeerEndpoint))
                append(' ')
            }
            if (cascadePeerPublicKey.isNotBlank()) {
                append("ARDTT_CASCADE_PEER_PUBLIC_KEY=")
                append(SshClient.shellQuote(cascadePeerPublicKey))
                append(' ')
            }
        }
        append("bash /opt/ardtt/install.sh")
    }

    fun peerEndpoint(host: String, listenPort: Int = CASCADE_LISTEN_PORT): String {
        val h = host.trim()
        if (h.isEmpty()) return ""
        return "$h:$listenPort"
    }

    fun publicKeyFromLine(line: String): String? {
        if (!line.startsWith("ARDTT_CASCADE_PUBLIC_KEY|")) return null
        return line.removePrefix("ARDTT_CASCADE_PUBLIC_KEY|").trim().takeIf { it.isNotEmpty() }
    }
}
