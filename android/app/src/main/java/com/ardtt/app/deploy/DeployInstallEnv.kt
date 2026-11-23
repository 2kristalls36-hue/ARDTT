package com.ardtt.app.deploy

/**
 * Deploy v2 (docs/adr/0006-deploy-v2.md): the APK uploads its own
 * `ardtt-deploy.sh` over SFTP and runs it with these environment variables.
 * Never includes SSH passwords.
 */
object DeployInstallEnv {
    const val CASCADE_DNS = "10.10.0.2"
    const val CASCADE_LISTEN_PORT = 51820
    /** APK asset (copy of server/deploy/ardtt-deploy.sh, kept in sync by Gradle). */
    const val DEPLOY_ASSET = "deploy/ardtt-deploy.sh"
    /**
     * SFTP target, relative to the SSH user's home (the login directory of the
     * exec channel too). The command copies it into a root-owned mktemp file
     * before running it, so another local user cannot swap it in between.
     */
    const val DEPLOY_SCRIPT_REMOTE = "ardtt-deploy.sh"

    /** Exit's cascade secret for the entry, SFTP'd (0600) next to the script; the installer deletes it. */
    const val CASCADE_SECRET_REMOTE = "ardtt-cascade.secret"

    private fun runScriptPrelude(scriptPath: String): String =
        "set -euo pipefail; SRC=${SshClient.shellQuote(scriptPath)}; " +
            "case \"\$SRC\" in /*) ;; *) SRC=\"\$PWD/\$SRC\" ;; esac; test -f \"\$SRC\"; " +
            "SCRIPT=\"\$(mktemp /tmp/ardtt-deploy.XXXXXX)\"; trap 'rm -f \"\$SCRIPT\"' EXIT; " +
            "cat \"\$SRC\" > \"\$SCRIPT\"; "

    fun installCommand(
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
        cascadePeerProvisionPort: Int = 9100,
        autoPorts: Boolean = true,
        provisionPort: Int = 9100,
        telemetryPort: Int = 9200,
        /** Host vCPU count (from `nproc`); clamps ARDTT_CPUS for 1-core VPS. */
        hostCpus: Int = 0,
        scriptPath: String = DEPLOY_SCRIPT_REMOTE,
        /** Relative to the SSH home; set when the exit's secret was uploaded there. */
        cascadeSecretFile: String = "",
    ): String = buildString {
        append(runScriptPrelude(scriptPath))
        append("ARDTT_ACTION=install ")
        append("ARDTT_PUBLIC_HOST="); append(SshClient.shellQuote(publicHost)); append(' ')
        append("ARDTT_DIRECT_PORT="); append(directPort); append(' ')
        append("ARDTT_BYPASS_PORT="); append(bypassPort); append(' ')
        append("ARDTT_PROVISION_PORT="); append(provisionPort); append(' ')
        append("ARDTT_TELEMETRY_PORT="); append(telemetryPort); append(' ')
        append("ARDTT_AUTO_PORTS="); append(if (autoPorts) "1" else "0"); append(' ')
        append("ARDTT_DEPLOY_VERSION="); append(SshClient.shellQuote(deployVersion)); append(' ')
        append("ARDTT_ROLE="); append(SshClient.shellQuote(role)); append(' ')
        append("ARDTT_CASCADE_ENABLED="); append(if (cascadeEnabled) "1" else "0"); append(' ')
        // 1-vCPU VPS: Docker rejects cpus above the host total.
        when {
            hostCpus >= 2 -> append("ARDTT_CPUS=2.0 ")
            hostCpus == 1 -> append("ARDTT_CPUS=1.0 ARDTT_MEM_LIMIT=512m ")
        }
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
            if (cascadePeerProvisionPort in 1..65535) {
                append("ARDTT_CASCADE_PEER_PROVISION_PORT=")
                append(cascadePeerProvisionPort)
                append(' ')
            }
            if (cascadeSecretFile.isNotBlank()) {
                append("ARDTT_CASCADE_SECRET_FILE=\"\$PWD\"/")
                append(SshClient.shellQuote(cascadeSecretFile))
                append(' ')
            }
        }
        append("bash \"\$SCRIPT\"")
    }

    fun uninstallCommand(purgeData: Boolean = true, scriptPath: String = DEPLOY_SCRIPT_REMOTE): String =
        runScriptPrelude(scriptPath) +
            "ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=${if (purgeData) "1" else "0"} bash \"\$SCRIPT\""

    fun peerEndpoint(host: String, listenPort: Int = CASCADE_LISTEN_PORT): String {
        val h = host.trim()
        if (h.isEmpty()) return ""
        return "$h:$listenPort"
    }

    fun publicKeyFromLine(line: String): String? {
        if (!line.startsWith("ARDTT_CASCADE_PUBLIC_KEY|")) return null
        return line.removePrefix("ARDTT_CASCADE_PUBLIC_KEY|").trim().takeIf { it.isNotEmpty() }
    }

    /** Key/value fields from an `ARDTT_DONE|a=1|b=2` protocol line. */
    fun doneFields(line: String): Map<String, String> {
        if (!line.startsWith("ARDTT_DONE|")) return emptyMap()
        return line.removePrefix("ARDTT_DONE|")
            .split('|')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) return@mapNotNull null
                part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()
    }

    fun intField(fields: Map<String, String>, key: String): Int? =
        fields[key]?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** Exit 0 without a terminal done payload is not a successful deploy. */
    fun missingDonePayload(exitCode: Int, doneFields: Map<String, String>, failed: String?): Boolean {
        if (failed != null) return false
        return exitCode == 0 && doneFields.isEmpty()
    }
}
