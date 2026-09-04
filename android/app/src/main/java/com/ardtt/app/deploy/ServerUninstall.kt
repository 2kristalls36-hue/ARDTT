package com.ardtt.app.deploy

/**
 * Client-side SSH wipe of the ARDTT stack. Lives in the APK so the stack
 * `DEPLOY_VERSION` (Compose / install.sh) does not need a bump.
 *
 * Idempotent: missing Docker, leftover `nvpn-*` names, or an empty `/opt/ardtt`
 * still finish with [DONE_MARKER] and exit 0 — [SshClient] treats a non-zero
 * status as failure.
 *
 * Dedicated VPS (no foreign containers): also drops ARDTT images, Docker Engine,
 * `docker0`, the `/swapfile` install.sh created, ufw/firewalld port rules, and
 * leftover iptables comments. Shared VPS: only ARDTT-named resources.
 */
object ServerUninstall {
    const val DONE_MARKER = "ARDTT_UNINSTALLED"
    const val TIMEOUT_MS = 10 * 60_000L

    /**
     * Remote bash: compose down, containers, host dataplane, `/opt/ardtt`,
     * then Docker/swap when this host has no other containers.
     */
    fun remoteCommand(): String = "bash -c ${SshClient.shellQuote(SCRIPT)}"

    private val SCRIPT = """
        set +e
        echo "ARDTT_PROGRESS|0.08|Остановка Compose…"
        env_val() {
          local key="${'$'}1" file="${'$'}2" def="${'$'}3" v=""
          [ -f "${'$'}file" ] || { printf '%s' "${'$'}def"; return 0; }
          v="${'$'}(grep -E "^${'$'}{key}=" "${'$'}file" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\"' | tr -d "'" | tr -d '[:space:]')"
          printf '%s' "${'$'}{v:-${'$'}def}"
        }
        ENVF=""
        for f in /opt/ardtt/stack/.env /opt/nonamevpn/stack/.env; do
          [ -f "${'$'}f" ] && ENVF="${'$'}f" && break
        done
        DIRECT_PORT="${'$'}(env_val ARDTT_DIRECT_PORT "${'$'}ENVF" 51820)"
        BYPASS_PORT="${'$'}(env_val ARDTT_BYPASS_PORT "${'$'}ENVF" 56003)"
        CASCADE_PORT="${'$'}(env_val ARDTT_CASCADE_LISTEN_PORT "${'$'}ENVF" 51820)"
        TELEMETRY_PORT="${'$'}(env_val ARDTT_TELEMETRY_PORT "${'$'}ENVF" 9200)"
        PROVISION_PORT="${'$'}(env_val ARDTT_PROVISION_PORT "${'$'}ENVF" 9100)"
        COMPOSE_PROJECT="${'$'}(env_val COMPOSE_PROJECT_NAME "${'$'}ENVF" stack)"

        wipe_compose() {
          local dir="${'$'}1"
          if [ -f "${'$'}dir/docker-compose.yml" ]; then
            (cd "${'$'}dir" && COMPOSE_PROFILES=isolated,hostnet COMPOSE_PROJECT_NAME="${'$'}COMPOSE_PROJECT" docker compose down -v --remove-orphans) >/dev/null 2>&1
            (cd "${'$'}dir" && COMPOSE_PROJECT_NAME="${'$'}COMPOSE_PROJECT" docker-compose down -v --remove-orphans) >/dev/null 2>&1
          fi
        }
        wipe_compose /opt/ardtt/stack
        wipe_compose /opt/nonamevpn/stack
        wipe_compose /opt/ardtt
        wipe_compose /opt/nonamevpn

        echo "ARDTT_PROGRESS|0.22|Снятие контейнеров…"
        docker rm -f \
          ardtt ardtt-host \
          ardtt-provision ardtt-direct ardtt-bypass ardtt-dns ardtt-warp ardtt-telemetry ardtt-cascade \
          nvpn-provision nvpn-direct nvpn-bypass nvpn-dns nvpn-warp nvpn-telemetry nvpn-cascade \
          >/dev/null 2>&1
        if command -v docker >/dev/null 2>&1; then
          docker ps -aq --filter name=ardtt 2>/dev/null | while read -r id; do
            [ -n "${'$'}id" ] && docker rm -f "${'$'}id" >/dev/null 2>&1
          done
          docker ps -aq --filter name=nvpn- 2>/dev/null | while read -r id; do
            [ -n "${'$'}id" ] && docker rm -f "${'$'}id" >/dev/null 2>&1
          done
        fi

        foreign_docker_workloads() {
          command -v docker >/dev/null 2>&1 || return 1
          docker info >/dev/null 2>&1 || return 1
          local names
          names="${'$'}(docker ps -a --format '{{.Names}}' 2>/dev/null || true)"
          [ -n "${'$'}names" ] || return 1
          echo "${'$'}names" | grep -vE '^(ardtt|nvpn-|stack-|buildx_buildkit_)' | grep -q .
        }

        echo "ARDTT_PROGRESS|0.36|Образы, volume и сети ARDTT…"
        if command -v docker >/dev/null 2>&1; then
          docker rmi -f "${'$'}{COMPOSE_PROJECT}-ardtt:latest" stack-ardtt:latest >/dev/null 2>&1
          docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' 2>/dev/null | awk 'BEGIN{IGNORECASE=1} ${'$'}1 ~ /(ardtt|nvpn)/ {print ${'$'}2}' | sort -u | while read -r id; do
            [ -n "${'$'}id" ] && docker rmi -f "${'$'}id" >/dev/null 2>&1
          done
          docker volume ls -q 2>/dev/null | grep -Ei 'ardtt|nvpn|bypass-config' | while read -r vol; do
            [ -n "${'$'}vol" ] && docker volume rm -f "${'$'}vol" >/dev/null 2>&1
          done
          docker network ls --format '{{.Name}}' 2>/dev/null | grep -Ei '^(stack_default|ardtt|nvpn)' | while read -r net; do
            [ -n "${'$'}net" ] && docker network rm "${'$'}net" >/dev/null 2>&1
          done
        fi

        echo "ARDTT_PROGRESS|0.48|Очистка leftover TUN/ip-rule/iptables…"
        for iface in awg0 wdttraw0 warp0 cascade0; do
          ip link del "${'$'}iface" >/dev/null 2>&1
        done
        rm -f /etc/wireguard/warp0.conf
        while IFS= read -r fr; do
          [ -n "${'$'}fr" ] || continue
          echo "${'$'}fr" | grep -Eq 'from 10\.(8|9)\.|from 10\.10\.0\.|from 10\.99\.99\.|iif (awg0|wdttraw0|warp0|cascade0)' || continue
          pref="${'$'}(echo "${'$'}fr" | cut -d: -f1 | tr -d "[:space:]")"
          [ -n "${'$'}pref" ] && ip rule del pref "${'$'}pref" 2>/dev/null || true
        done <<< "${'$'}(ip rule show 2>/dev/null | grep "lookup 51820" || true)"
        for iface in awg0 wdttraw0 cascade0; do
          for proto in udp tcp; do
            ip rule del iif "${'$'}iface" ipproto "${'$'}proto" dport 53 lookup main 2>/dev/null || true
          done
        done
        for net in 10.8.0.0/24 10.9.0.0/24 10.10.0.0/30 10.99.99.0/24 127.0.0.0/8; do
          ip rule del to "${'$'}net" lookup main 2>/dev/null || true
        done
        if command -v iptables >/dev/null 2>&1; then
          for comment in AWG_DIRECT_MANAGED ARDTT_BYPASS_MANAGED ARDTT_WARP_MANAGED ARDTT_WARP_DNS_MAIN ARDTT_CASCADE_WAN_MASQ ARDTT_CASCADE_MANAGED; do
            for table in filter nat mangle; do
              for chain in INPUT FORWARD POSTROUTING PREROUTING OUTPUT; do
                while true; do
                  n="${'$'}(iptables -t "${'$'}table" -L "${'$'}chain" --line-numbers -n 2>/dev/null | grep -F "${'$'}comment" | awk '{print ${'$'}1}' | tail -1 || true)"
                  [ -n "${'$'}n" ] || break
                  iptables -t "${'$'}table" -D "${'$'}chain" "${'$'}n" 2>/dev/null || break
                done
              done
            done
          done
        fi

        echo "ARDTT_PROGRESS|0.58|Снятие портов firewall…"
        host_drop_port() {
          local proto="${'$'}1" port="${'$'}2"
          [ -n "${'$'}port" ] || return 0
          if command -v ufw >/dev/null 2>&1; then
            local guard=0
            while ufw status 2>/dev/null | grep -Eq "(^|[[:space:]])${'$'}{port}/${'$'}{proto}([[:space:]]|${'$'})"; do
              ufw --force delete allow "${'$'}{port}/${'$'}{proto}" >/dev/null 2>&1 || break
              guard="${'$'}((guard + 1))"
              [ "${'$'}guard" -gt 16 ] && break
            done
          fi
          if command -v firewall-cmd >/dev/null 2>&1; then
            firewall-cmd --remove-port="${'$'}{port}/${'$'}{proto}" --permanent >/dev/null 2>&1
          fi
          if command -v iptables >/dev/null 2>&1; then
            while iptables -D INPUT -p "${'$'}proto" --dport "${'$'}port" -j ACCEPT 2>/dev/null; do :; done
          fi
        }
        host_drop_port udp "${'$'}DIRECT_PORT"
        host_drop_port udp "${'$'}BYPASS_PORT"
        host_drop_port udp "${'$'}CASCADE_PORT"
        host_drop_port tcp "${'$'}PROVISION_PORT"
        host_drop_port tcp "${'$'}TELEMETRY_PORT"
        if command -v firewall-cmd >/dev/null 2>&1; then
          firewall-cmd --reload >/dev/null 2>&1
        fi

        echo "ARDTT_PROGRESS|0.68|Удаление /opt/ardtt и логов…"
        rm -rf /opt/ardtt /opt/nonamevpn
        rm -f /tmp/ardtt-* /tmp/ardtt-install*.log /var/log/ardtt-*.log /var/log/ardtt-install.log
        rm -rf /tmp/ardtt-data-bak

        if foreign_docker_workloads; then
          echo "ARDTT_PROGRESS|0.90|Чужие контейнеры есть — Docker и swap оставляем"
        else
          echo "ARDTT_PROGRESS|0.78|Снятие Docker (чужих контейнеров нет)…"
          if command -v docker >/dev/null 2>&1; then
            docker ps -aq 2>/dev/null | while read -r id; do
              [ -n "${'$'}id" ] && docker rm -f "${'$'}id" >/dev/null 2>&1
            done
            docker image prune -af >/dev/null 2>&1
            docker builder prune -af >/dev/null 2>&1
            docker volume prune -f >/dev/null 2>&1
            docker network prune -f >/dev/null 2>&1
          fi
          if command -v systemctl >/dev/null 2>&1; then
            systemctl stop docker docker.socket containerd >/dev/null 2>&1
            systemctl disable docker docker.socket containerd >/dev/null 2>&1
          else
            service docker stop >/dev/null 2>&1
          fi
          wait_apt_lock() {
            local n=0
            while fuser /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/lib/apt/lists/lock /var/cache/apt/archives/lock >/dev/null 2>&1; do
              n="${'$'}((n + 1))"
              [ "${'$'}n" -gt 90 ] && break
              sleep 2
            done
          }
          export DEBIAN_FRONTEND=noninteractive
          if command -v apt-get >/dev/null 2>&1; then
            wait_apt_lock
            apt-get purge -y \
              docker-ce docker-ce-cli docker-ce-rootless-extras docker-compose-plugin \
              docker-buildx-plugin docker-compose docker-model-plugin containerd.io \
              docker.io docker-doc docker-registry >/tmp/ardtt-apt-purge.log 2>&1
            wait_apt_lock
            apt-get autoremove -y --purge >/tmp/ardtt-apt-autoremove.log 2>&1
            if dpkg -l 2>/dev/null | grep -E '^ii' | grep -Eq 'docker-ce|containerd.io'; then
              wait_apt_lock
              dpkg --purge docker-ce docker-ce-cli docker-ce-rootless-extras docker-compose-plugin docker-buildx-plugin docker-model-plugin containerd.io >/dev/null 2>&1
            fi
          fi
          if command -v dnf >/dev/null 2>&1; then
            dnf -y remove docker docker-ce docker-ce-cli docker-compose-plugin docker-buildx-plugin containerd.io >/dev/null 2>&1
          fi
          rm -rf /var/lib/docker /var/lib/containerd /etc/docker /var/lib/docker-engine
          rm -f /etc/apt/sources.list.d/docker.list /etc/apt/sources.list.d/docker.sources
          rm -f /etc/apt/keyrings/docker.asc /etc/apt/keyrings/docker.gpg /etc/apt/keyrings/docker.gpg.asc
          ip link del docker0 >/dev/null 2>&1
          ip -o link show 2>/dev/null | awk -F': ' '{print ${'$'}2}' | cut -d'@' -f1 | grep -E '^(br-|docker)' | while read -r br; do
            ip link del "${'$'}br" >/dev/null 2>&1
          done
          if command -v iptables >/dev/null 2>&1; then
            iptables -t filter -D FORWARD -j DOCKER-USER >/dev/null 2>&1
            iptables -t filter -D FORWARD -j DOCKER-FORWARD >/dev/null 2>&1
            iptables -t nat -D PREROUTING -m addrtype --dst-type LOCAL -j DOCKER >/dev/null 2>&1
            iptables -t nat -D OUTPUT ! -d 127.0.0.0/8 -m addrtype --dst-type LOCAL -j DOCKER >/dev/null 2>&1
            iptables -t nat -S POSTROUTING 2>/dev/null | grep docker0 | sed 's/^-A POSTROUTING //' | while read -r spec; do
              [ -n "${'$'}spec" ] || continue
              eval "iptables -t nat -D POSTROUTING ${'$'}spec" >/dev/null 2>&1
            done
            for chain in DOCKER DOCKER-BRIDGE DOCKER-CT DOCKER-FORWARD DOCKER-INTERNAL DOCKER-USER DOCKER-ISOLATION-STAGE-1 DOCKER-ISOLATION-STAGE-2 DOCKER-INGRESS; do
              iptables -t filter -F "${'$'}chain" >/dev/null 2>&1
              iptables -t nat -F "${'$'}chain" >/dev/null 2>&1
            done
            for chain in DOCKER-INGRESS DOCKER-ISOLATION-STAGE-2 DOCKER-ISOLATION-STAGE-1 DOCKER-USER DOCKER-INTERNAL DOCKER-FORWARD DOCKER-CT DOCKER-BRIDGE DOCKER; do
              iptables -t filter -X "${'$'}chain" >/dev/null 2>&1
              iptables -t nat -X "${'$'}chain" >/dev/null 2>&1
            done
          fi
          echo "ARDTT_PROGRESS|0.88|Снятие swapfile установщика…"
          swapoff /swapfile >/dev/null 2>&1
          rm -f /swapfile
          if [ -f /etc/fstab ]; then
            sed -i '\#^/swapfile[[:space:]]#d' /etc/fstab
          fi
        fi

        echo "ARDTT_PROGRESS|1|Готово"
        echo "$DONE_MARKER"
        exit 0
    """.trimIndent()
}
