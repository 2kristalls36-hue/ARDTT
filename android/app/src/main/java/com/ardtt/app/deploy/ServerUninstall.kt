package com.ardtt.app.deploy

/**
 * Client-side SSH wipe of one ARDTT instance. Prefers the package installer
 * (`ARDTT_ACTION=uninstall`). Fallback only removes this install dir and
 * Docker objects labeled com.ardtt.owner=ardtt. Never purges Docker Engine,
 * containerd, docker0, swap, or foreign firewall rules.
 */
object ServerUninstall {
    const val DONE_MARKER = "ARDTT_UNINSTALLED"
    const val TIMEOUT_MS = 10 * 60_000L

    fun remoteCommand(): String = "bash -c ${SshClient.shellQuote(DeployInstallEnv.uninstallCommand(purgeData = true))}"

    /**
     * Used only when no install.sh remains on the VPS. Instance-scoped: labels
     * and /opt/ardtt, not name prefixes or the Docker engine.
     */
    val FALLBACK_SCRIPT = """
        set +e
        echo "ARDTT_PROGRESS|0.2|Снятие экземпляра по labels…"
        INST=""
        if [ -f /opt/ardtt/instance.json ]; then
          INST="${'$'}(python3 -c 'import json,sys; print(json.load(open("/opt/ardtt/instance.json")).get("instanceId",""))' 2>/dev/null)"
        fi
        if [ -f /opt/ardtt/current/docker-compose.yml ]; then
          (cd /opt/ardtt/current && docker compose down --remove-orphans) >/dev/null 2>&1
        fi
        if [ -f /opt/ardtt/stack/docker-compose.yml ]; then
          (cd /opt/ardtt/stack && docker compose down --remove-orphans) >/dev/null 2>&1
        fi
        if [ -n "${'$'}INST" ] && command -v docker >/dev/null 2>&1; then
          docker ps -aq --filter label=com.ardtt.owner=ardtt --filter label=com.ardtt.instance="${'$'}INST" | while read -r id; do
            [ -n "${'$'}id" ] && docker rm -f "${'$'}id" >/dev/null 2>&1
          done
          docker network ls -q --filter label=com.ardtt.owner=ardtt --filter label=com.ardtt.instance="${'$'}INST" | while read -r id; do
            [ -n "${'$'}id" ] && docker network rm "${'$'}id" >/dev/null 2>&1
          done
        fi
        echo "ARDTT_PROGRESS|0.7|Удаление /opt/ardtt…"
        rm -rf /opt/ardtt /opt/nonamevpn
        echo "ARDTT_PROGRESS|1|Готово"
        echo "ARDTT_UNINSTALLED"
        exit 0
    """.trimIndent()
}
