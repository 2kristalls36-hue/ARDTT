# Деплой сервера ARDTT

> [!CAUTION]
> **Ранняя бета.** Деплой-часть не обещана рабочей. Автор **не несёт ответственности** за результат. Ставите **на свой страх и риск**.

Как ставится и как живёт self-hosted стек **ARDTT** на VPS.  
Клиентская сторона — вкладка **Серверы** в режиме администратора Android.  
Состав сервисов: [ARCHITECTURE.md](ARCHITECTURE.md), [LEGEND.md](LEGEND.md).

Каталог по умолчанию — `/opt/ardtt` (`ARDTT_INSTALL_DIR`). Старый `/opt/nonamevpn` при обновлении переносится сюда.

Версия **стека** (`DEPLOY_VERSION`, сейчас **1.0.48**) независима от `versionName` приложения. Её бампят, когда меняется то, что уезжает на VPS (образ, Compose, `install.sh`, Engine).

> [!IMPORTANT]
> Стек **1.0.48** — самодостаточный архив `ardtt-server-<версия>-linux-<amd64|arm64>.tar.gz` (gzip-слои образа + Engine).  
> APK **старше** этой линейки ждут `ardtt-stack-*.tar.gz` и запас с `main` — они **не** поставят 1.0.48. Нужен клиент с этой версии.

---

## Что нужно на VPS

Чистый Linux amd64/arm64 с python3, iptables и `/dev/net/tun`. **Docker Engine входит в архив** (`vendor/docker.tgz`) и ставится, если `docker info` не проходит.

| Есть | Нет |
|------|-----|
| Linux amd64 или arm64 | Сборка образа на VPS |
| python3, curl, iptables, systemd | `get.docker.com`, apt/dnf установка Docker |
| исходящий HTTPS к GitHub Releases | `docker pull` / GHCR / Docker Hub на установке |
| `/dev/net/tun` | `git clone` исходников |
| Compose v2 *или* `bin/docker-compose` из архива | Хостовый hostnet |

Если Engine уже работает, установщик его не обновляет и не перезапускает. Если CLI есть, а демон мёртв — отказ (чужой Engine не подменяем). Недостающий Compose берётся из того же архива в `/opt/ardtt/bin`.

Клиент перед установкой проверяет оба узла тем же SSH-маршрутом: сначала выход (VPS2 через VPS1), затем вход. Отсутствие Docker **не** ошибка: установка распакует Engine из архива. По-прежнему отказ: `DOCKER_NOT_RUNNING` (чужой демон мёртв), `DOCKER_ACCESS_DENIED`, `UNSUPPORTED_RUNTIME` (podman/kubelet/containerd без Docker), `PYTHON_MISSING`, `SSH_FAILED`, а также `CURL_MISSING` / `GITHUB_UNREACHABLE` если VPS не может скачать пакет с Releases.

Стек — **один контейнер** в своей netns и своей Docker bridge-сети. Hostnet из старой `.env` не восстанавливается. Привилегированный режим, host PID/IPC, `docker.sock` внутри контейнера и nsenter в хост не используются. Compose: `cap_drop: ALL`, затем `NET_ADMIN`, `NET_RAW`, `SETUID` и `SETGID` (иначе dnsmasq `setgid(dip)` падает с Operation not permitted). Подсеть bridge подбирается так, чтобы не пересечься с маршрутами хоста и сетями Docker: сначала `172.28.x.0/24` / `172.30.x.0/24`, а если хост анонсирует `172.16.0.0/12` (часто на облачных VPS) — `10.112.x.0/24` или `10.210.x.0/24`. Не задаётся одна жёсткая подсеть для всех машин.

---

## Формат пакета

Имя: `ardtt-server-<DEPLOY_VERSION>-linux-<amd64|arm64>.tar.gz`.  
Entry и exit используют один образ своей архитектуры, различаясь `.env` / `docker-compose.exit.yml`.

Содержимое:

```
manifest.json          # format ardtt-server-v1, version, arch, image tag/id
SHA256SUMS             # суммы файлов внутри (не источник доверия)
install.sh + install-lib/
ready.sh               # overlay в контейнер (пока образ 1.0.45 со старым скриптом)
docker-compose.yml     # production: image + pull_policy: never, без build:
docker-compose.exit.yml
.env.example
images/ardtt.tar       # docker save
bin/docker-compose      # закреплённый Compose CLI этой arch
vendor/docker.tgz       # статический Docker Engine (ставится, если docker info не проходит)
third-party.lock.json
```

Доверие к архиву — **внешняя SHA-256** актива GitHub Release (`digest` или `SHA256SUMS` того же релиза), не суммы внутри tar. Image ID в манифесте пакета — не registry RepoDigest: на классическом Docker это config blob, на containerd — digest манифеста. После `docker load` установщик сверяет **слои** `images/ardtt.tar` с загруженным образом.

Сборка в GitHub Actions: [`.github/workflows/server-package.yml`](../.github/workflows/server-package.yml) (`scripts/build-server-image.sh` + `scripts/pack-server-package.sh`). Push в `main` собирает пакеты в Artifacts (30 дней) и **не** прикрепляет их к GitHub Release. Публикация ассетов — только с явного тега `v*` (`scripts/attach-server-packages-to-release.sh --tag … --from-dir …`, без `--clobber`). Android-сборка APK серверные пакеты не трогает. Сторонние исходники (Debian, pinned GitHub tarball'ы) допустимы **в CI**; на VPS пользователь получает только этот архив.

---

## Два способа поставить сервер

| Способ | Когда | Что происходит |
|--------|--------|----------------|
| **Из приложения** | Админ с телефоном | SSH → на VPS запускается `fetch-and-install.sh` → **VPS** качает актив с GitHub Releases → Engine из архива → `docker load` + compose `--no-build --pull never` |
| **Архив с Releases** | На VPS есть shell | Скачать актив, сверить SHA-256 с релизом, `install.sh` (или тот же `fetch-and-install.sh`) |

Телефон **не** скачивает и **не** SFTP'ит multi‑MB пакет. Нужен только SSH к VPS; исходящий HTTPS к `api.github.com` / `github.com` — **на сервере**. Версия стека для UI опрашивается при запуске APK (`DeployVersionCatalog`); в APK зашит лишь offline fallback.

Сборка из исходников (`docker-compose.dev.yml`) — путь **разработчика**, не продуктовая установка.

Оба продуктовых способа поднимают один контейнер: provision + direct + bypass + dns + warp + cascade + telemetry. Пользователи и ключи живут в `/opt/ardtt/data` и переживают обновление.

```
Телефон (админ)
  SSH  «запусти fetch-and-install»
      │
      ▼
    VPS  HTTPS  GitHub Releases  ardtt-server-<ver>-linux-<arch>.tar.gz
         SHA-256 с digest / SHA256SUMS релиза
         extract + bash staging/install.sh
         /opt/ardtt/current/     ← compose + .env этой версии
         /opt/ardtt/previous/    ← прошлое, пока новая не прошла readiness
         /opt/ardtt/data         ← users.json, ключи, warp
         /opt/ardtt/logs
         контейнер с labels com.ardtt.owner / com.ardtt.instance
      │
      ├─ host UDP Direct  → контейнер 51820
      ├─ host UDP Bypass  → контейнер 56003
      ├─ host TCP provision → контейнер 9100
      └─ host TCP telemetry → контейнер 9200
```

Внутренние listen-порты стабильны. Настраиваются **host published** Direct / Bypass / cascade / provision / telemetry. `TELEMETRY_LISTEN` внутри контейнера всегда `0.0.0.0:9200`.

---

## Путь 1 — деплой из Android

Нужен APK этой линейки (стек **1.0.48**). APK **0.5.257** ещё останавливает каскад на preflight, если Docker на VPS нет. Старые APK с `ardtt-stack-*.tar.gz` и fallback на `main` этот пакет не ставят.

### Что ещё привязано к телефону

| Остаётся на телефоне | Можно ли отвязать дальше |
|----------------------|---------------------------|
| SSH как канал «запусти скрипт» | Да — webhook/agent на VPS, телефон только UI |
| Preflight по SSH (опциональная кнопка) | Да — install.sh сам падает с `ARDTT_ERROR`; «Повторить установку» больше не ждёт отдельную проверку |
| Оркестрация порядка каскада exit→entry | Частично снято: ключ входа VPS1→VPS2 через `POST /v1/cascade/peer` |
| Карточка сервера / SSH-секреты | Да, если появится отдельный admin API с токеном |
| Опрос Releases при старте APK | Частично снято: `/health.latestDeployVersion` с VPS |
| APK self-update | Отдельно от деплоя VPS |

### UI

1. Настройки → режим администратора.
2. **Серверы** → добавить или **Обновить деплой**.
3. SSH (пароль или PEM). Каскад: те же поля для второго VPS (arch узлов могут различаться).
4. Публичный host (`ARDTT_PUBLIC_HOST`) — endpoint профиля.
5. Автовыбор портов по умолчанию: свободные host TCP/UDP, результат в `.env` и `ARDTT_DONE`.
6. **«Удалить»** снимает **этот** экземпляр ARDTT (контейнер, сети, каталоги). Docker Engine и чужие контейнеры остаются. Карточка исчезает только после `ARDTT_UNINSTALLED`.

Список серверов зондрует `http://{publicHost}:{provisionPort}/health` (порт с прошлого `ARDTT_DONE`, иначе 9100).

### Что делает `DeployEngine`

1. SSH: `uname -m` / preflight **на каждом** хопе. Каскад: сначала SSH на вход, затем `direct-tcpip` с входа на SSH выхода (телефон VPS 2 не набирает).
2. На VPS: если есть `/opt/ardtt/current/fetch-and-install.sh` — запускает его; иначе SFTP **только** маленький bootstrap `fetch-and-install.sh` из assets APK.
3. `fetch-and-install.sh` на VPS: HTTPS к GitHub Releases → SHA-256 → safe extract → `install.sh`. Пакет на телефон не качается.
4. Протокол: `ARDTT_PROGRESS`, `ARDTT_WARN`, `ARDTT_ERROR`, `ARDTT_DONE`, `ARDTT_CASCADE_PUBLIC_KEY`. Фактические `provision_port` / `telemetry_port` пишутся в карточку.
5. При каждом запуске APK `DeployVersionCatalog` опрашивает Releases и обновляет «ожидаемую» версию стека (сравнение с `/health`).

Каскад: SSH к выходу всегда через вход (direct-tcpip / ProxyJump с телефона на VPS 1, дальше TCP до SSH VPS 2). Сначала выходной стек, потом входной. Общая установка не считается успешной, если один узел не готов. Пароль второго сервера в `.env` входа не пишется. На карточке укажите адрес выхода **как его видит VPS 1** (публичный IP или внутренний). `sshd` входа должен разрешать `AllowTcpForwarding`.

Повторный «Обновить деплой» входа без флага каскада не сбрасывает живой hop (`preserve_live_cascade`). Снять: `ARDTT_CASCADE_FORCE_DISABLE=1`.

Отмена = `session.disconnect()` (и jump-сессия входа, если каскад). Docker `builder prune` / `image prune` с телефона **не** вызываются.

> [!NOTE]
> Ошибка **«Контейнер ardtt не подтверждён как ARDTT — не останавливаем»** — защита установщика: чужой контейнер с именем `ardtt` не трогаем. Если контейнер уже снят `compose down`, повторная проверка больше не падает (считается «уже снят»). Если на хосте реально чужой `ardtt` без labels/`/opt/ardtt/data` — переименуйте/уберите его или дайте ARDTT имя `ardtt-<instanceId>`.

### Удаление

`ARDTT_ACTION=uninstall` у `/opt/ardtt/current/install.sh`. Fallback — только объекты с `com.ardtt.owner=ardtt` и `/opt/ardtt`. Docker/containerd, `docker0`, swap, чужой firewall **не** трогаются, даже если других контейнеров нет.

`ARDTT_PURGE_DATA=1` стирает `data/`; без флага данные остаются.

---

## Путь 2 — архив с GitHub Releases

Docker на VPS не обязателен: `install.sh` распакует Engine из `vendor/docker.tgz`. Рабочий Engine не трогает. Не вызывайте сетевой установщик Engine.

```bash
VER=1.0.46
ARCH=amd64   # или arm64; uname -m: x86_64→amd64, aarch64→arm64
TAG=v0.5.258
ASSET=ardtt-server-${VER}-linux-${ARCH}.tar.gz
install -d -m 755 /opt/ardtt/incoming
# Скачайте актив с https://github.com/2kristalls36-hue/ARDTT/releases
# Сверьте SHA-256 с digest актива или SHA256SUMS этого релиза (не с самоподписанной суммой tar).
export ARDTT_PUBLIC_HOST=IP_ЭТОГО_VPS
export ARDTT_PACKAGE=/opt/ardtt/incoming/${ASSET}
export ARDTT_PACKAGE_SHA256=...   # с релиза
# install.sh внутри архива сам распакует в staging, если не задан ARDTT_PKG_DIR
python3 -c 'import tarfile'  # нужен python3
tar -tzf "$ARDTT_PACKAGE" >/dev/null
# После сверки SHA-256:
#   python3 scripts/safe-extract-package.py "$ARDTT_PACKAGE" /opt/ardtt/staging
#   ARDTT_PKG_DIR=/opt/ardtt/staging bash /opt/ardtt/staging/install.sh
# Или одним шагом, если пакет уже в incoming:
bash -c '… extract then install …'
```

Практичный вариант после ручной распаковки проверенного архива:

```bash
export ARDTT_PUBLIC_HOST=IP_ЭТОГО_VPS
export ARDTT_PACKAGE=/opt/ardtt/incoming/ardtt-server-1.0.46-linux-amd64.tar.gz
export ARDTT_PACKAGE_SHA256='…из GitHub Release…'
export ARDTT_AUTO_PORTS=1
bash /opt/ardtt/staging/install.sh   # после safe-extract в staging
```

Обновление — тот же архив новой версии. Откат: `ARDTT_ACTION=rollback bash /opt/ardtt/current/install.sh`.  
Удаление: `ARDTT_ACTION=uninstall bash /opt/ardtt/current/install.sh` (данные) или с `ARDTT_PURGE_DATA=1`.

Каскад:

```bash
# выход
ARDTT_ROLE=exit ARDTT_PUBLIC_HOST=IP_ВЫХОДА … bash install.sh
# stdout: ARDTT_CASCADE_PUBLIC_KEY|…  и provision_port=

# вход
ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=1 \
  ARDTT_CASCADE_PEER_ENDPOINT='IP_ВЫХОДА:51820' \
  ARDTT_CASCADE_PEER_PUBLIC_KEY='…' \
  ARDTT_CASCADE_PEER_PROVISION_PORT=9100 \
  ARDTT_PUBLIC_HOST=IP_ВХОДА … bash install.sh
```

Порядок обновления каскада: сначала выход до этого `deployVersion`, потом вход.
Вход после readiness сам `POST /v1/cascade/peer` на provision выхода (ключ входа).
Телефону третий SSH-хоп для записи `cascade.peer.pub` не нужен.
`GET /health` отдаёт `latestDeployVersion` (кэш Releases) и `cascadePublicKey`.

---

## Путь разработчика — сборка из исходников

Не для VPS-установки. Нужны Docker, сеть до Debian/GitHub, `NET_ADMIN`, `/dev/net/tun`.

```bash
cd server
cp .env.example .env   # ARDTT_PUBLIC_HOST, ARDTT_INSTANCE_ID, ARDTT_BRIDGE_SUBNET
docker compose -f docker-compose.dev.yml up -d --build
curl -s http://127.0.0.1:9100/health
```

Production `docker-compose.yml` **без** `build:`. Образ собирает CI.

---

## Что делает `install.sh`

Канонический файл: [`server/install.sh`](../server/install.sh). Маркеры `ARDTT_PROGRESS|` / `ARDTT_DONE|` остаются в первых 400 символах (старые APK).

| Шаг | Поведение |
|------|-----------|
| Lock | `/opt/ardtt/install.lock` — один установщик на экземпляр |
| SHA-256 | до любых изменений стека |
| Safe extract | запрет `..`, абсолютных путей, symlink/hardlink |
| Preflight | Docker, python3, TUN, arch пакета, диск (install + DockerRootDir), RAM, порты TCP/UDP + Docker PortBindings, подсеть bridge. **Старый ARDTT ещё работает** |
| `docker load` | проверка image ID и arch |
| Switch | только этот экземпляр по labels; `previous/` до readiness |
| Up | `docker compose up -d --no-build --pull never` |
| Readiness | пакетный `ready.sh` через `bash` (процессы/интерфейсы роли), не один `/health` |
| Ошибка | откат на `previous`, `ARDTT_ERROR`, код ≠ 0, **нет** `ARDTT_DONE` |

Не делается: stop Docker/containerd, prune кэшей, wipe `/var/lib/docker`, swap/fstab, host DNS/sysctl, ufw/firewalld по номеру порта, `cleanup_host_dataplane()`.

Чужие контейнеры `stack-nginx-1` и имена вроде `ardtt` не считаются нашими без labels / подтверждённых mounts.

---

## Раскладка на диске

```
/opt/ardtt/
  incoming/              # загруженный архив (телефон)
  staging/               # распаковка текущей попытки
  current/               # активные compose + .env + install.sh
  previous/              # прошлое до следующего успеха
  releases/<version>/
  data/                  # users, ключи, warp; не в tar
  logs/                  # telemetry, не /var/logs/app хоста
  instance.json          # instanceId, compose project, container, subnet, image id
  bin/docker-compose     # если взят из пакета
  install.lock
```

Миграция со стека ≤1.0.44: `stack/data` → `data/`, confirmed telemetry logs, остановка только подтверждённого контейнера. Старый hostnet: если контейнер не найден, установщик **останавливается** и просит ручное решение leftover-интерфейсов. Универсальная очистка хоста отключена.

---

## Как стек работает после деплоя

Источник истины — **provision** (`users.json`). Остальные процессы ждут файл и следят за mtime.

### Direct / Bypass / DNS / Hide IP

Как раньше: AmneziaWG UDP, RAW UDP после TURN на клиенте, dnsmasq на шлюзах, WARP только как egress выбранных `/32`. DNS `:53` не через WARP и не на `host:53`.

На каскаде вход — passthrough; выход опрашивает `GET /v1/hide-ip-prefixes` у `10.10.0.1:9100` (overlay). WAN-опрос provision выхода идёт на **опубликованный** порт (`ARDTT_CASCADE_PEER_PROVISION_PORT`).

### Provision API

Порт снаружи — `ARDTT_PROVISION_PORT` (в профиле поле `provisionPort`). Внутри контейнера слушает 9100.

`GET /health` отдаёт `deployVersion`, `role`, `cascade`, `directPort`, `bypassPort`, `provisionPort`, `telemetryPort`.  
`GET /ready` — тот же `ready.sh`.

Авторизации нет: это ваш VPS.

---

## Порты

| Предпочтительно | Протокол | Кто | Снаружи |
|------|----------|-----|---------|
| SSH | TCP | деплой из приложения | да, для админ-деплоя |
| 51820 | UDP | Direct / cascade listen | да; автовыбор может сменить host-порт |
| 56003 | UDP | Bypass RAW | да (не на exit) |
| 9100 | TCP | provision | да |
| 9200 | TCP | telemetry | если нужны логи с телефонов |

`ARDTT_AUTO_PORTS=1`: занятый **чужой** порт → следующий свободный; свои опубликованные при обновлении не считаются конфликтом. Занятый порт чужой службой не убивается. Произвольный listener на 9200 не считается «настроенным nginx».

---

## Когда бампать `DEPLOY_VERSION`

Файл: `server/DEPLOY_VERSION`. Синхронно:

1. `android/app/src/main/assets/deploy/DEPLOY_VERSION`
2. `DeployBundle.FALLBACK_VERSION`
3. CI (`.github/workflows/server-package.yml`) собирает `ardtt-server-<версия>-linux-*.tar.gz`. Публикация в GitHub Release — **только с тега** `v*` после `contract` и `package`, без `--clobber` уже лежащих ассетов. Push в `main` и открытый PR **не** прикрепляют пакеты к релизу. PR загружает host-file артефакт Actions (без образа). Полная пересборка образа идёт на `main`/тег; ассеты тега не смешивают новый установщик со старым образом под тем же именем. Пока релиза нет — только Artifacts (30 дней). Старый `ardtt-stack-*.tar.gz` в релиз не кладётся.

Бамп: образ, Compose, entrypoint'ы, `install.sh`. Только UI телефона — нет.

---

## Чек-лист после установки

```bash
cd /opt/ardtt/current
docker compose ps
curl -s http://127.0.0.1:9100/health
curl -s http://127.0.0.1:9100/ready
ss -ulnp | grep -E '51820|56003' || true
ss -tlnp | grep -E '9100|9200'
NAME=$(python3 -c 'import json; print(json.load(open("/opt/ardtt/instance.json"))["containerName"])')
docker exec "$NAME" provision -cmd create-user -name smoke -data /data
```

---

## Типичные отказы

| Симптом | Что проверить |
|---------|----------------|
| «Не удалось скачать пакет … из GitHub» | Сеть телефона до github.com. Старый APK ждёт `ardtt-stack-*` — обновите приложение. Новый APK ищет архив на своём теге, иначе на другом релизе с тем же стеком |
| Нет SHA-256 у актива | Релиз без `digest` / `SHA256SUMS` — установка откажется до изменений на VPS |
| Docker Engine не найден на чистом VPS | Норма для 1.0.46: пакет ставит Engine из `vendor/docker.tgz`. Если отказ — смотрите `DOCKER_MISSING` (нет tarball/SHA) или `DOCKER_NOT_RUNNING` (чужой демон / нет systemd). Нужен APK 0.5.258: 0.5.257 ещё обрывает preflight |
| Мало места | Порог с запасом на load + слои + `previous/`. Глобальная очистка сервера не выполняется |
| `ARDTT_ERROR` без `ARDTT_DONE` | Новый стек не прошёл readiness; смотрите `previous/` и `/opt/ardtt/install.log` |
| Чужие контейнеры/VPN отвалились | Так быть не должно. Сообщите labels/имена; не включайте hostnet |
| UDP Direct мёртв, TCP provision жив | Docker UDP DNAT. Hostnet-fallback нет — смотрите published ports и return path |
| Карточка «нужно обновить» | APK новее стека — «Обновить деплой» |
| «Удалить» не снимает карточку | SSH не прошёл — карточка остаётся специально |

Телеметрия категории `deploy` — [TELEMETRY.md](TELEMETRY.md).
