package com.ardtt.app.deploy

/**
 * Client-side SSH wipe of the ARDTT stack. Lives in the APK so the stack
 * `DEPLOY_VERSION` (Compose / install.sh) does not need a bump.
 *
 * Idempotent: missing Docker, leftover `nvpn-*` names, or an empty `/opt/ardtt`
 * still finish with [DONE_MARKER] and exit 0 — [SshClient] treats a non-zero
 * status as failure.
 */
object ServerUninstall {
    const val DONE_MARKER = "ARDTT_UNINSTALLED"
    const val TIMEOUT_MS = 10 * 60_000L

    /**
     * Remote bash: compose down (current + legacy paths), force-remove known
     * containers, then `rm -rf /opt/ardtt /opt/nonamevpn`.
     */
    fun remoteCommand(): String = "bash -c ${SshClient.shellQuote(SCRIPT)}"

    private val SCRIPT = """
        set +e
        echo "ARDTT_PROGRESS|0.10|Остановка Compose…"
        wipe_compose() {
          local dir="${'$'}1"
          if [ -f "${'$'}dir/docker-compose.yml" ]; then
            (cd "${'$'}dir" && COMPOSE_PROJECT_NAME=stack docker compose down -v --remove-orphans) >/dev/null 2>&1
            (cd "${'$'}dir" && COMPOSE_PROJECT_NAME=stack docker-compose down -v --remove-orphans) >/dev/null 2>&1
          fi
        }
        wipe_compose /opt/ardtt/stack
        wipe_compose /opt/nonamevpn/stack
        wipe_compose /opt/ardtt
        wipe_compose /opt/nonamevpn
        echo "ARDTT_PROGRESS|0.45|Снятие контейнеров…"
        docker rm -f \
          ardtt-provision ardtt-direct ardtt-bypass ardtt-dns ardtt-warp ardtt-telemetry ardtt-cascade \
          nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade \
          >/dev/null 2>&1
        if command -v docker >/dev/null 2>&1; then
          docker ps -aq --filter name=ardtt- 2>/dev/null | while read -r id; do
            [ -n "${'$'}id" ] && docker rm -f "${'$'}id" >/dev/null 2>&1
          done
          docker ps -aq --filter name=nvpn- 2>/dev/null | while read -r id; do
            [ -n "${'$'}id" ] && docker rm -f "${'$'}id" >/dev/null 2>&1
          done
        fi
        echo "ARDTT_PROGRESS|0.75|Удаление /opt/ardtt…"
        rm -rf /opt/ardtt /opt/nonamevpn
        echo "ARDTT_PROGRESS|1|Готово"
        echo "$DONE_MARKER"
        exit 0
    """.trimIndent()
}
