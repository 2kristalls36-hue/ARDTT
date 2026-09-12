# Деплой сервера ARDTT

> [!CAUTION]
> **Ранняя бета.** Деплой-часть не обещана рабочей. Автор **не несёт ответственности** за результат. Ставите **на свой страх и риск**.

Как ставится и как живёт self-hosted стек **ARDTT** на VPS.  
Клиентская сторона — вкладка **Серверы** в режиме администратора Android.  
Состав сервисов: [ARCHITECTURE.md](ARCHITECTURE.md), [LEGEND.md](LEGEND.md).

Каталог по умолчанию — `/opt/ardtt` (`ARDTT_INSTALL_DIR`). Старый `/opt/nonamevpn` при обновлении переносится сюда.

Версия **стека** (`DEPLOY_VERSION`, сейчас **1.0.53**) независима от `versionName` приложения. Её бампят, когда меняется то, что уезжает на VPS (образ, Compose, `install.sh`, Engine).

> [!IMPORTANT]
> Стек **1.0.53** — тот же самодостаточный архив `ardtt-server-<версия>-linux-<amd64|arm64>.tar.gz` (gzip-слои образа + Engine) **плюс** покомпонентные ассеты релиза и индекс `ardtt-server-<версия>-linux-<arch>.index.json`: VPS качает только то, чего у него нет ([частичный деплой](#частичный-деплой)).  
> APK **старше** этой линейки ждут `ardtt-stack-*.tar.gz` и запас с `main` — они **не** поставят 1.0.53. Нужен клиент с этой версии.

---

## Что нужно на VPS

Чистый Linux amd64/arm64 с python3, systemd и `/dev/net/tun`; `curl` желателен, но не обязателен (без него `fetch-and-install.sh` качает через python3 urllib). iptables больше не обязателен заранее: минимальные образы Debian 13 / Ubuntu 26.04 идут без него, и если Docker Engine нужно ставить из пакета, установщик сам берёт пакет `iptables` из репозитория дистрибутива (apt/dnf/yum/apk/zypper) — единственное, что он вообще ставит из репозиториев. `ARDTT_INSTALL_IPTABLES=0` это запрещает: тогда при отсутствующем iptables и Docker из пакета — `IPTABLES_MISSING` ещё до загрузки. **Docker Engine входит в архив** (`vendor/docker.tgz`) и ставится, если `docker info` не проходит.

| Есть | Нет |
|------|-----|
| Linux amd64 или arm64 | Сборка образа на VPS |
| python3, systemd (curl — опционально; iptables ставится из репозитория, если его нет) | `get.docker.com`, apt/dnf установка Docker |
| исходящий HTTPS к GitHub Releases | `docker pull` / GHCR / Docker Hub на установке |
| `/dev/net/tun` | `git clone` исходников |
| Compose v2 *или* `bin/docker-compose` из архива | Хостовый hostnet |

Если Engine уже работает, установщик его не обновляет и не перезапускает. Если CLI есть, а демон мёртв — отказ (чужой Engine не подменяем). Недостающий Compose берётся из того же архива в `/opt/ardtt/bin`.

Клиент перед установкой проверяет оба узла тем же SSH-маршрутом: сначала выход (VPS2 через VPS1), затем вход. Отсутствие Docker **не** ошибка: установка распакует Engine из архива. По-прежнему отказ: `DOCKER_NOT_RUNNING` (чужой демон мёртв), `DOCKER_ACCESS_DENIED`, `UNSUPPORTED_RUNTIME` (podman/kubelet/containerd без Docker), `PYTHON_MISSING`, `SSH_FAILED`, а также `IPTABLES_MISSING` (автоматическая установка iptables не удалась либо запрещена `ARDTT_INSTALL_IPTABLES=0`) и `GITHUB_UNREACHABLE`, если VPS не может скачать пакет с Releases. `CURL_MISSING` больше не возникает: без curl работает загрузчик на python3.

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
fetch-and-install.sh   # VPS сам качает релиз с GitHub
ready.sh               # overlay в контейнер
docker-compose.yml     # production: image + pull_policy: never, без build:
docker-compose.exit.yml
.env.example
images/layout.json     # ardtt-image-layers-v1 — манифест gzip-слоёв
images/config.json
images/layers/*.tar.gz # слои образа (вместо одного images/ardtt.tar)
bin/docker-compose     # закреплённый Compose CLI этой arch
vendor/docker.tgz       # статический Docker Engine (ставится, если docker info не проходит)
third-party.lock.json
scripts/assemble-docker-save.py
scripts/layer-cache.py # кэш слоёв: plan / seed / store / link / adopt / prune
scripts/safe-extract-package.py
```

Ассеты релиза на каждую arch (`<ver>` = `DEPLOY_VERSION`, `<arch>` = `amd64|arm64`):

| Актив | Что это | Когда нужен |
|-------|---------|-------------|
| `ardtt-server-<ver>-linux-<arch>.tar.gz` (+ `.sha256`) | монолитный архив выше | полная установка, старые APK-bootstrap'ы, `ARDTT_FETCH_MODE=full` |
| `ardtt-server-<ver>-linux-<arch>.index.json` (+ `.sha256`) | формат `ardtt-server-index-v1`: все компоненты с SHA-256 и размером | единственный корень доверия частичной загрузки |
| `ardtt-server-<ver>-linux-<arch>-hostfiles.tar.gz` | `install.sh`, `fetch-and-install.sh`, `install-lib/`, `scripts/` (`assemble-docker-save.py`, `layer-cache.py`, `safe-extract-package.py`), `docker-compose*.yml`, `.env.example`, `manifest.json`, `images/layout.json` + `images/config.json`, `third-party.lock.json`, `DEPLOY_VERSION`, README — **без** слоёв, Engine и Compose (≈100 КБ) | всегда |
| `ardtt-server-<ver>-linux-<arch>-layer-NN-<16hex>.tar.gz` | один gzip на слой образа; адресуется **diff ID** слоя (SHA-256 распакованного tar слоя, `<16hex>` — его префикс) | только слои, которых нет в кэше VPS |
| `ardtt-docker-engine-<engver>-linux-<arch>.tgz` | закреплённый статический Docker Engine побайтно (те же байты, что `vendor/docker.tgz`; сумма в `third-party.lock.json`). Engine 29.7.2: 85,7 МБ amd64 / 77,3 МБ arm64 | только если на VPS нет `docker` |
| `ardtt-docker-compose-<cver>-linux-<arch>` | закреплённый Compose CLI побайтно (= `bin/docker-compose`). Compose 2.32.4: 64,7 МБ amd64 / 62,9 МБ arm64 | только если Compose на VPS нет |
| `SHA256SUMS-server.txt` | суммы всех серверных ассетов релиза | fallback доверия, если у актива нет `digest` |

Индекс перечисляет host-файлы (по SHA-256 каждого), слои (gzip-SHA-256 + diff ID + размеры), Engine и Compose, а также полный архив. Слои в индексе привязаны к diff ID, поэтому одинаковые слои двух версий стека — один и тот же файл в кэше VPS.

Доверие к архиву — **внешняя SHA-256** актива GitHub Release (`digest` или `SHA256SUMS` того же релиза), не суммы внутри tar. Image ID в манифесте пакета — не registry RepoDigest. Установщик собирает слои в поток и делает `docker load` **без** записи второго полного `ardtt.tar` на диск; если локальный образ уже совпадает по config+слоям — load пропускается. Порог места по умолчанию **1600 МБ** (раньше 2500); для layered-пакета считается динамически.

Сборка в GitHub Actions: [`.github/workflows/server-package.yml`](../.github/workflows/server-package.yml) (`scripts/build-server-image.sh` + `scripts/pack-server-package.sh` + `scripts/build-server-index.py`, проверка индекса — `scripts/verify-server-index.sh`). `server/Dockerfile` закрепляет базовые образы по digest (`golang:1.22-alpine`, `golang:1.25-bookworm`, `debian:bookworm-slim`; digest'ы в `server/third-party.lock.json` → `baseImages.digests`, сверяет `scripts/check-deploy-bundle.sh`), ставит режимы через `COPY --chmod` (хвостового `RUN chmod`, который переписывал все бинарники одним слоем, больше нет), кладёт по слою на каждый Go-бинарник и сводит entrypoint'ы/helpers/шаблоны в один маленький overlay-слой. `scripts/build-server-image.sh` идёт через `docker buildx build --load` с опциональными `ARDTT_BUILD_CACHE_FROM/TO`; в CI это BuildKit-кэш `type=gha` на каждую arch, поэтому неизменившиеся шаги дают побайтно те же слои между сборками — и частичное обновление реально качает только изменившееся. Push в `main` собирает пакеты в Artifacts (30 дней) и **не** прикрепляет их к GitHub Release. Публикация ассетов — только с явного тега `v*` (`scripts/attach-server-packages-to-release.sh --tag … --from-dir …`, без `--clobber`). Android-сборка APK серверные пакеты не трогает. Сторонние исходники (Debian, pinned GitHub tarball'ы) допустимы **в CI**; на VPS пользователь получает только этот архив.

---

## Два способа поставить сервер

| Способ | Когда | Что происходит |
|--------|--------|----------------|
| **Из приложения** | Админ с телефоном | SSH → на VPS запускается `fetch-and-install.sh` → **VPS** качает индекс релиза и только недостающие компоненты (или монолитный архив, если индекса нет) → Engine из релиза, если Docker нет → `docker load` + compose `--no-build --pull never` |
| **Архив с Releases** | На VPS есть shell | Скачать актив, сверить SHA-256 с релизом, `install.sh` (или тот же `fetch-and-install.sh`) |

Телефон **не** скачивает и **не** SFTP'ит multi‑MB пакет. Нужен только SSH к VPS; исходящий HTTPS к `api.github.com` / `github.com` — **на сервере**. Версия стека для UI опрашивается при запуске APK (`DeployVersionCatalog`); карточка подписана на этот каталог, а не на одноразовый снимок fallback из APK.

Сборка из исходников (`docker-compose.dev.yml`) — путь **разработчика**, не продуктовая установка.

Оба продуктовых способа поднимают один контейнер: provision + direct + bypass + dns + warp + cascade + telemetry. Пользователи и ключи живут в `/opt/ardtt/data` и переживают обновление.

```
Телефон (админ)
  SSH  «запусти fetch-and-install»
      │
      ▼
    VPS  HTTPS  GitHub Releases  ardtt-server-<ver>-linux-<arch>.index.json
         SHA-256 индекса с digest / SHA256SUMS релиза
         hostfiles (≈100 КБ) + отсутствующие слои образа
         iptables из репозитория дистрибутива — только если нет Docker и нет iptables
         Engine/Compose — только если их нет на хосте
         (без индекса — монолитный ardtt-server-<ver>-linux-<arch>.tar.gz)
         сверка каждого файла с индексом + bash staging/install.sh
         /opt/ardtt/cache/layers/ ← gzip-слои по diff ID, переиспользуются
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

## Частичный деплой

Со стека **1.0.52** `fetch-and-install.sh` качает не монолитный архив, а компоненты релиза по индексу. Протокол с телефоном не изменился (`ARDTT_PROGRESS|` / `ARDTT_WARN|` / `ARDTT_ERROR|` / `ARDTT_DONE|`, те же переменные с телефона).

| Компонент | Когда качается | Размер |
|-----------|----------------|--------|
| Индекс `*.index.json` | всегда | JSON с перечнем компонентов |
| `*-hostfiles.tar.gz` | всегда | ≈100 КБ |
| Пакет `iptables` (репозиторий дистрибутива) | только если Docker ставится из пакета и iptables нет на хосте; `ARDTT_INSTALL_IPTABLES=0` запрещает | пакетный менеджер хоста (apt/dnf/yum/apk/zypper) |
| Слои образа `*-layer-NN-<16hex>.tar.gz` | только те diff ID, которых нет в `/opt/ardtt/cache/layers` | 78,3 МБ gzip на весь образ 1.0.51 (23 слоя; два базовых, Debian и apt, ≈57 МБ) |
| `ardtt-docker-engine-*.tgz` | только если на хосте вообще нет `docker` | 85,7 МБ amd64 / 77,3 МБ arm64 |
| `ardtt-docker-compose-*` | только если нет ни плагина `docker compose`, ни `docker-compose`, а `/opt/ardtt/bin/docker-compose` отсутствует или не совпадает с закреплённой SHA-256 | 64,7 МБ amd64 / 62,9 МБ arm64 |
| Монолитный `*.tar.gz` | `ARDTT_FETCH_MODE=full` или релиз без индекса | 182,3 МБ amd64 / 169,8 МБ arm64 (1.0.51 на релизе `v0.5.263`) |

Полный архив (182,3 / 169,8 МБ) — это Engine 85,7 МБ, Compose 64,7 МБ (в архиве сжат сильнее) и gzip-слои образа 78,3 МБ. Поэтому обновление на VPS, где Docker уже работает, тянет не больше слоёв (≤78 МБ при пустом кэше и без загруженного образа), а при тёплом кэше — только изменившиеся (обычно маленькие слои со скриптами и provision); базовые слои Debian и apt (≈57 МБ) меняются только при бампе базового образа. На тестовых VPS повторное обновление 1.0.52 — три запроса: список релизов, индекс (5 КБ), host-файлы (56 КБ), ноль слоёв.

**Кэш слоёв.** `/opt/ardtt/cache/layers/<diffId>.tar.gz` (`scripts/layer-cache.py`). Перед загрузкой кэш «засевается» из уже загруженного образа через `docker save` (`layer-cache.py seed`), затем качаются только слои, чей diff ID всё ещё отсутствует (по слою — строка прогресса со счётчиком МБ). Готовые слои линкуются (hardlink) в staging. После успешной установки кэш прунится до слоёв текущего образа (≈78 МБ на диске). `ARDTT_LAYER_CACHE=0` отключает постоянный кэш: слои живут только в staging и удаляются после `docker load`.

**Цепочка доверия.** Корень — внешняя SHA-256 **индекса**: `digest` актива GitHub, иначе `SHA256SUMS-server.txt` того же релиза, иначе сосед `*.index.json.sha256`. Дальше всё сверяется по индексу: host-файлы по SHA-256 (лишний файл в `hostfiles` тоже отказ), каждый слой — по gzip-SHA-256 **и** по diff ID, Engine и Compose — по закреплённым суммам. `install.sh` повторяет проверку индекса и всего staging **до** того, как трогает живой стек. Суммы внутри архива по-прежнему источником доверия не считаются.

**Переменные.**

| Переменная | Значение |
|------------|----------|
| `ARDTT_FETCH_MODE` | `auto` (по умолчанию: индекс, если он есть в релизе, иначе монолитный архив) · `partial` (без индекса — ошибка `INDEX_MISSING`) · `full` (всегда монолитный архив) |
| `ARDTT_LAYER_CACHE` | `1` (по умолчанию) · `0` — без постоянного кэша слоёв |
| `ARDTT_INSTALL_IPTABLES` | `1` (по умолчанию) — при отсутствии iptables и необходимости ставить Docker из пакета взять пакет iptables из репозитория дистрибутива · `0` — запретить (ошибка `IPTABLES_MISSING` до загрузки) |
| `ARDTT_GITHUB_TOKEN` | необязательный токен, снимает анонимный лимит GitHub API |
| `ARDTT_DEPLOY_VERSION` | закрепить версию стека; пусто — самая новая опубликованная |

**Выбор релиза.** `releases?per_page=40` через GitHub API, берётся **наибольшая** версия стека по semver среди релизов, которые не черновики (раньше — первый подходящий актив в списке). Если API недоступен или упёрся в лимит (403/429), скрипт печатает `ARDTT_WARN|GitHub API недоступен …` и читает `SHA256SUMS-server.txt` **последнего** релиза через `https://github.com/<repo>/releases/latest/download/…` (без API) — дальше установка идёт от него.

**Загрузка.** Через curl, а без него — через python3 urllib (те же Range-возобновление и повторы). Возобновляемая (`curl -C -`, `.partial` в `/opt/ardtt/incoming`), с защитой от «залипания» (меньше 1 КБ/с в течение 90 с — обрыв) и повторами. Битая загрузка при `SHA256_MISMATCH` удаляется, чтобы повтор скачал заново. Один запуск на каталог установки: `/opt/ardtt/fetch.lock` (иначе `ARDTT_ERROR|BUSY|…`). После успеха индекс, Engine и Compose из `incoming/` удаляются.

---

## Путь 1 — деплой из Android

Нужен APK этой линейки (стек **1.0.53**). APK **0.5.257** ещё останавливает каскад на preflight, если Docker на VPS нет. Старые APK с `ardtt-stack-*.tar.gz` и fallback на `main` этот пакет не ставят.

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
2. На VPS: если есть `/opt/ardtt/current/fetch-and-install.sh` — запускает его; иначе SFTP **только** маленький bootstrap `fetch-and-install.sh` из assets APK. До 1.0.52 копии на VPS не было вообще: `pack-server-package.sh` не включал `fetch-and-install.sh` в список файлов tar, поэтому `/opt/ardtt/current/fetch-and-install.sh` не появлялся и телефон каждый раз заливал bootstrap из APK. Теперь скрипт есть и в архиве, и в `hostfiles`, и после первой установки 1.0.52 работает копия с VPS. Путь обновления отвязан от версии APK: старые bootstrap'ы (≤0.5.263) продолжают работать — они качают монолитный архив, который по-прежнему публикуется, — а каждое следующее обновление идёт частичным деплоем из копии на VPS.
3. `fetch-and-install.sh` на VPS: HTTPS к GitHub Releases → SHA-256 → safe extract → `install.sh`. Пакет на телефон не качается. Со стека 1.0.52 — [частичный деплой](#частичный-деплой) по индексу.
4. Протокол: `ARDTT_PROGRESS`, `ARDTT_WARN`, `ARDTT_ERROR`, `ARDTT_DONE`, `ARDTT_CASCADE_PUBLIC_KEY`. Фактические `provision_port` / `telemetry_port` пишутся в карточку.
5. При каждом запуске APK `DeployVersionCatalog` опрашивает Releases и обновляет «ожидаемую» версию стека (сравнение с `/health`). Карточка сервера читает этот каталог как StateFlow, а не запоминает fallback на первом кадре.

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
`GET /health` отдаёт `latestDeployVersion` (кэш Releases) и `cascadePublicKey`. `latestDeployVersion` — наибольшая версия стека по всем релизам-нечерновикам; считаются и `.tar.gz`, и `.index.json`, ассеты hostfiles / слоёв / Engine / Compose игнорируются.

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
| Индексный режим | с `ARDTT_PACKAGE_INDEX` / `ARDTT_PACKAGE_INDEX_SHA256` внешняя сумма монолитного архива не проверяется (его и нет): сверяются SHA-256 индекса и каждый файл staging — host-файлы по SHA-256, слои по gzip-SHA-256 или diff ID, Engine/Compose по закреплённым суммам. Всё это **до** остановки живого стека |
| Кэш слоёв | после `docker load` gzip-слои не удаляются, а переезжают в `/opt/ardtt/cache/layers` (и для монолитного пути — поэтому первое частичное обновление после полной установки уже переиспользует слои), затем кэш прунится до слоёв текущего образа |
| Compose из пакета | `/opt/ardtt/bin/docker-compose` (наша копия) обновляется, если пакет несёт другую закреплённую сборку. Системный плагин не трогается |
| Safe extract | запрет `..`, абсолютных путей, symlink/hardlink |
| Preflight | Docker, python3, TUN, arch пакета, диск (install + DockerRootDir), RAM, порты TCP/UDP + Docker PortBindings, подсеть bridge. Если Engine ставится из архива (`ensure_docker_engine`) и iptables на хосте нет — сначала подтягивается пакет iptables из репозитория дистрибутива. **Старый ARDTT ещё работает** |
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
  incoming/              # загруженный архив или компоненты релиза
  staging/               # распаковка текущей попытки
  cache/layers/          # gzip-слои образа по diff ID (частичный деплой)
  current/               # активные compose + .env + install.sh + fetch-and-install.sh
  current/images/layout.json   # манифест слоёв текущего образа (+ config.json), без блобов
  previous/              # прошлое до следующего успеха
  releases/<version>/
  data/                  # users, ключи, warp; не в tar
  logs/                  # telemetry, не /var/logs/app хоста
  instance.json          # instanceId, compose project, container, subnet, image id
  bin/docker-compose     # если взят из пакета
  install.lock
  fetch.lock             # одна загрузка/установка на каталог
```

`ARDTT_ACTION=uninstall` убирает и `cache/`, и `fetch.lock`.

Миграция со стека ≤1.0.44: `stack/data` → `data/`, confirmed telemetry logs, остановка только подтверждённого контейнера. Старый hostnet: если контейнер не найден, установщик **останавливается** и просит ручное решение leftover-интерфейсов. Универсальная очистка хоста отключена.

---

## Как стек работает после деплоя

Источник истины — **provision** (`users.json`). Остальные процессы ждут файл и следят за mtime.

### Direct / Bypass / DNS / Hide IP

Как раньше: AmneziaWG UDP, RAW UDP после TURN на клиенте, dnsmasq на шлюзах, WARP только как egress выбранных `/32`. DNS `:53` не через WARP и не на `host:53`.

На каскаде вход — passthrough; выход опрашивает `GET /v1/hide-ip-prefixes` у `10.10.0.1:9100` (overlay). WAN-опрос provision выхода идёт на **опубликованный** порт (`ARDTT_CASCADE_PEER_PROVISION_PORT`).

### Provision API

Порт снаружи — `ARDTT_PROVISION_PORT` (в профиле поле `provisionPort`). Внутри контейнера слушает 9100.

`GET /health` отдаёт `deployVersion`, `role`, `cascade`, `directPort`, `bypassPort`, `provisionPort`, `telemetryPort`, а также объект `host` (CPU/RAM/диск для карточки сервера в APK).  
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
3. CI (`.github/workflows/server-package.yml`) собирает `ardtt-server-<версия>-linux-*.tar.gz` и рядом покомпонентные ассеты частичного деплоя: `*.index.json` (+ `.sha256`), `*-hostfiles.tar.gz`, `*-layer-NN-*.tar.gz`, `ardtt-docker-engine-*`, `ardtt-docker-compose-*`, обновлённый `SHA256SUMS-server.txt`. Индекс проверяется `scripts/verify-server-index.sh`, а `scripts/attach-server-packages-to-release.sh` отказывается публиковать индекс, у которого компоненты отсутствуют или не совпали по SHA-256. Публикация в GitHub Release — **только с тега** `v*` после `contract` и `package`, без `--clobber` уже лежащих ассетов. Push в `main` и открытый PR **не** прикрепляют пакеты к релизу. PR загружает host-file артефакт Actions (без образа). Полная пересборка образа идёт на `main`/тег; ассеты тега не смешивают новый установщик со старым образом под тем же именем. Пока релиза нет — только Artifacts (30 дней). Старый `ardtt-stack-*.tar.gz` в релиз не кладётся.

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
| `BUSY` | На этом каталоге уже идёт загрузка/установка (`/opt/ardtt/fetch.lock`). Дождитесь окончания или снимите зависший процесс |
| `INDEX_MISSING` | `ARDTT_FETCH_MODE=partial`, а в релизе нет индекса этой arch. Монолитный архив — `ARDTT_FETCH_MODE=full` |
| `BAD_FETCH_MODE` | `ARDTT_FETCH_MODE` не `auto` / `partial` / `full` |
| `SHA256SUM_MISSING` | На VPS нет `sha256sum` (coreutils) |
| `IPTABLES_MISSING` | Docker нужно ставить из пакета, а `iptables` на хосте нет (минимальные Debian 13 / Ubuntu 26.04), и автоматическая установка из репозитория дистрибутива не удалась (нет доступа к репозиториям) или запрещена `ARDTT_INSTALL_IPTABLES=0`. Поставьте вручную (`apt install iptables` / `dnf install iptables-nft` / `apk add iptables`) и повторите; при `ARDTT_INSTALL_IPTABLES=1` (по умолчанию) установщик обычно ставит iptables сам |
| `GITHUB_UNREACHABLE` | Ни GitHub API, ни `SHA256SUMS-server.txt` последнего релиза не получены. Нужен исходящий HTTPS к `api.github.com` / `github.com`; при лимите API помогает `ARDTT_GITHUB_TOKEN` |
| Docker Engine не найден на чистом VPS | Норма для 1.0.46: пакет ставит Engine из `vendor/docker.tgz`. Если отказ — смотрите `DOCKER_MISSING` (нет tarball/SHA) или `DOCKER_NOT_RUNNING` (чужой демон / нет systemd). Нужен APK 0.5.258: 0.5.257 ещё обрывает preflight |
| Мало места | Код `DISK_FULL`. Порог с запасом на load + слои + `previous/`. Без флага глобальная очистка **не** выполняется; в ошибке предлагается повтор с `ARDTT_DISK_CLEANUP=1` (логи Docker, apt-кэш, лишние headers, хвосты ARDTT). В приложении — кнопка «Очистить место и повторить». |
| `ARDTT_ERROR` без `ARDTT_DONE` | Новый стек не прошёл readiness; смотрите `previous/` и `/opt/ardtt/install.log` |
| Чужие контейнеры/VPN отвалились | Так быть не должно. Сообщите labels/имена; не включайте hostnet |
| UDP Direct мёртв, TCP provision жив | Docker UDP DNAT. Hostnet-fallback нет — смотрите published ports и return path |
| Карточка «нужно обновить» | APK новее стека — «Обновить деплой» |
| «Удалить» не снимает карточку | SSH не прошёл — карточка остаётся специально |

Телеметрия категории `deploy` — [TELEMETRY.md](TELEMETRY.md).
