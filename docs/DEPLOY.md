# Деплой сервера ARDTT

Как ставится и как живёт self-hosted стек **ARDTT** на VPS.  
Клиентская сторона деплоя — вкладка **Серверы** в режиме администратора Android.  
Состав сервисов и смысл Path A/B: [ARCHITECTURE.md](ARCHITECTURE.md), [LEGEND.md](LEGEND.md).

Каталог на диске по умолчанию — `/opt/ardtt` (`ARDTT_INSTALL_DIR`). При обновлении старый `/opt/nonamevpn` переносится сюда.

Версия **стека** (`DEPLOY_VERSION`, сейчас **1.0.35**) независима от `versionName` приложения. Её бампят только когда меняется то, что уезжает на VPS (Compose, `install.sh`, образы сервисов).

---

## Два способа поставить сервер

| Способ | Когда | Что происходит |
|--------|--------|----------------|
| **Из приложения** | Админ с телефоном, VPS без GitHub | APK заливает свой `stack.tar.gz` + `install.sh` по SSH → Compose на хосте |
| **Git + Compose** | На VPS есть shell и доступ к репозиторию | Клон **тега релиза**, `install.sh` или `docker compose` в `server/` |

Оба способа поднимают **один и тот же** стек: единый контейнер `ardtt` (provision + direct + bypass + dns + warp + cascade + telemetry).  
Пользователи и ключи живут в `users.json` и **переживают** повторный деплой.

```
Телефон (админ)
  SSH (пароль или PEM)
      │  upload  /opt/ardtt/stack.tar.gz
      │  upload  /opt/ardtt/install.sh
      │  env ARDTT_PUBLIC_HOST=… bash install.sh
      ▼
VPS  /opt/ardtt/stack/     ← compose + исходники + Dockerfile
     /opt/ardtt/stack/data ← users.json, ключи, warp state
     контейнер ardtt (своя netns, не host)
      │
      ├─ :51820/udp  AmneziaWG (Path A)
      ├─ :56003/udp  RAW/WRAP  (Path B, после TURN на клиенте)
      ├─ :9100/tcp   provision (/health, пользователи, профили)
      └─ :9200/tcp   telemetry upload
```

---

## Что поднимается

По умолчанию — **один контейнер** `ardtt` в своей netns (`COMPOSE_PROFILES=isolated`).  
TUN, iptables и `ip rule` живут **внутри** контейнера (`NET_ADMIN` + `/dev/net/tun`), а на хост публикуются только порты ARDTT. Так деплой не перехватывает FORWARD/sysctl/ip-rule чужих сервисов на уже работающем VPS.

Запасной режим `ARDTT_NETWORK_MODE=hostnet` — прежний host network, если UDP через Docker DNAT на этой машине не проходит.

| Процесс в `ardtt` | Образ | Роль |
|-----------|--------|------|
| `provision` | `server/Dockerfile` (stage) | Источник истины: `host_id`, AWG-ключи, пароли bypass, JSON-профиль, `GET /health` с `deployVersion` |
| `direct` | то же | AmneziaWG 2.0 (`amneziawg-go` + `awg`) на `awg0`, подсеть `10.8.0.0/24` |
| `bypass` | то же | `wdtt-server -listen-raw` на `wdttraw0`, подсеть `10.9.0.0/24` |
| `dns` | то же | dnsmasq на шлюзах `10.8.0.1` / `10.9.0.1`, upstream `1.1.1.1`/`1.0.0.1` **через main**, не через WARP |
| `warp` | то же | wgcf → wireproxy → tun2socks `warp0`; hideIp → table `51820`. DNS (:53) остаётся на main |
| `telemetry` | то же | `POST /api/upload-log` с телефона |

Один `host_id` (начиная с **2**, `.1` — шлюз) даёт клиенту оба адреса: `10.8.0.{id}` и `10.9.0.{id}`.  
WARP — не третий путь подключения, а **egress** выбранных пользователей.

---

## Путь 1 — деплой из Android

### UI

1. Настройки → разблокировать режим администратора (долгий тап / PIN).
2. Вкладка **Серверы** → «Добавить сервер» (первый раз) или карточка VPS → **Обновить деплой** / **Установить деплой**, если стека ещё нет.
3. SSH host, порт, user (`root` по умолчанию), пароль **или** PEM. Каскад: те же поля для второго VPS.
4. Публичный host (`ARDTT_PUBLIC_HOST`) — то, что попадёт в endpoint профиля; обычно = IP VPS.
5. Порты: по умолчанию включён **автовыбор** свободных UDP Direct / Bypass на VPS. Выключите переключатель «Автовыбор портов», чтобы задать 51820 / 56003 (или свои) вручную.
6. «Сохранить сервер» только пишет цель в encrypted prefs. Установка — **«Установить на VPS»**.
7. **«Удалить»** на карточке не снимает её сразу. Плашка предупреждает, что стек будет стёрт на VPS (контейнеры, `/opt/ardtt`, профили клиентов). После подтверждения `DeployEngine` по SSH делает uninstall (каскад: сначала выход, потом вход). Карточка во вкладке **Сервера** пропадает только если удалённый wipe завершился успешно. Если SSH не проходит, карточка остаётся.

Креды лежат в `EncryptedSharedPreferences` (`ardtt_servers`), не в профиле VPN.

Список серверов зондрует `http://{publicHost}:9100/health`. Если `/health` молчит, карточка пробует SSH с сохранёнными кредами. Сравнение `deployVersion` с версией в APK:

- зелёный — установленный стек = стек в этом приложении;
- оранжевый — VPS онлайн, но версия старая → «Обновить деплой»;
- нет связи — «Нет связи»;
- SSH проходит, а `/health` нет — «Не установлено» и кнопка «Установить деплой».
- каскад — на строке IP `вход → выход`, обычный сервер — один адрес.

### Что делает `DeployEngine`

Класс: `android/.../deploy/DeployEngine.kt`. Таймаут установщика — 45 минут.

1. SSH (JSch). Не-root: команда через `sudo -S` с паролем.
2. `mkdir -p /opt/ardtt`
3. Загрузка архива стека из assets (см. [Бандл в APK](#бандл-в-apk)).
4. Загрузка `install.sh` + `DEPLOY_VERSION`.
5. Запуск:

```bash
ARDTT_PUBLIC_HOST='…' ARDTT_DIRECT_PORT=51820 ARDTT_BYPASS_PORT=56003 ARDTT_AUTO_PORTS=1 \
ARDTT_DEPLOY_VERSION='1.0.35' bash /opt/ardtt/install.sh
```

При `ARDTT_AUTO_PORTS=1` установщик оставляет предпочтительные порты, если они свободны; иначе подбирает ближайшие свободные UDP и пишет фактические значения в `ARDTT_DONE|…|direct_port=…|bypass_port=…`. Приложение сохраняет их в карточке сервера. `ARDTT_AUTO_PORTS=0` — прежнее поведение: занятый порт = ошибка установки.

Каскад (два VPS): телефон **отдельно** SSH на выход, потом на вход. Пароль второго сервера в `.env` входа не пишется.

```bash
# 1) выход (DNS + WARP)
ARDTT_ROLE=exit ARDTT_PUBLIC_HOST='2.26.125.160' ARDTT_DEPLOY_VERSION='1.0.35' \
  ARDTT_AUTO_PORTS=1 bash /opt/ardtt/install.sh
# stdout: ARDTT_CASCADE_PUBLIC_KEY|<base64>

# 2) вход (клиенты), пир = ключ выхода
ARDTT_ROLE=entry ARDTT_CASCADE_ENABLED=1 \
  ARDTT_CASCADE_PEER_ENDPOINT='2.26.125.160:51820' \
  ARDTT_CASCADE_PEER_PUBLIC_KEY='…' \
  ARDTT_PUBLIC_HOST='45.129.2.3' ARDTT_DEPLOY_VERSION='1.0.35' \
  ARDTT_AUTO_PORTS=1 bash /opt/ardtt/install.sh

# 3) ключ входа → /opt/ardtt/stack/data/cascade.peer.pub на выходе
```

Повторный «Обновить деплой» **входа** без `ARDTT_CASCADE_ENABLED=1` больше не сбрасывает живой каскад: `install.sh` копирует флаги из `stack/.env` и `data/cascade.peer.endpoint`. Снять каскад явно: `ARDTT_CASCADE_FORCE_DISABLE=1`.

На standalone-входе `ARDTT_CASCADE_DNS` в `.env` должен быть пустым. Иначе provision отдаёт профилям `10.10.0.2`, хотя `ardtt-cascade` не запущен и DNS на `10.8.0.1` / `10.9.0.1`.

Порядок обновления каскада: сначала **выход** до этого стека, потом вход. Если обновить только вход и включить hop, Hide-IP-выкл уедет в старый blanket WARP на выходе — 2ip.ru снова покажет Cloudflare. Не поднимайте hop, пока `/health` выхода не покажет тот же `deployVersion`, что и вход.

6. Разбор stdout построчно (UTF-8, без ANSI):
   - `ARDTT_PROGRESS|<0..1>|<шаг>` — полоса, процент и подпись в UI (каскад: два ряда VPS 1 / VPS 2 с IP);
   - `ARDTT_ERROR|<текст>` — ошибка даже при exit 0;
   - `ARDTT_DONE|…` — успех (`direct_port` / `bypass_port` / `cascade_listen_port` — фактические UDP);
   - `ARDTT_WARN|…` — в лог, деплой продолжается.
7. После успеха: prune образов на хосте, `lastDeployedAtMs` в prefs.
8. После ошибки: `tail` удалённого `/opt/ardtt/install.log` в телеметрию (если включена запись).

Отмена = `session.disconnect()`.

### Удаление сервера из приложения

Класс: `android/.../deploy/ServerUninstall.kt` (команда в APK, **без** бампа `DEPLOY_VERSION`).

1. Плашка «Удалить сервер?» — предупреждение, что стек снимется с VPS.
2. `docker compose down -v` в `/opt/ardtt/stack` (и legacy `/opt/nonamevpn`), `docker rm -f` контейнеров `ardtt` / `ardtt-*` / `nvpn-*`, снятие leftover TUN/`ip rule`/iptables с комментариями ARDTT, правила ufw/firewalld на порты стека, `rm -rf /opt/ardtt /opt/nonamevpn`. Образы `stack-ardtt`. Если на хосте **нет чужих контейнеров** — `apt-get purge` Docker, `docker0`, `/var/lib/docker`, `/swapfile` установщика и docker.list. Чужой стек на shared VPS не трогается.
3. Каскад: сначала выходной VPS, затем вход. Ошибка на любом хосте оставляет карточку, чтобы можно было повторить (команда идемпотентна).
4. Маркер stdout `ARDTT_UNINSTALLED` и exit 0. Только после этого `ServersRepository.delete`.
5. Отмена = обрыв SSH; карточка не удаляется.

### Бандл в APK

Исходники стека **не** коммитятся как `.tar.gz`. Их собирает `scripts/pack-deploy-assets.sh`. Gradle-задача `packDeployAssets` висит на `preBuild` и пересобирает архив, если изменился `server/`.

```
android/app/src/main/assets/deploy/
  install.sh          ← копия server/install.sh (в git)
  DEPLOY_VERSION      ← копия server/DEPLOY_VERSION (в git)
  stack.tar.gz.bin    ← gzip-тар server/ без data/ (генерируется)
```

Почему `.bin`: aapt распаковывает `*.gz` и может оставить `stack.tar`. Движок пробует имена в порядке `.bin` → `.gz` → `.tar` (последний сам сжимает обратно).

В архиве:

```
docker-compose.yml  Dockerfile  entrypoint.sh  .env.example  DEPLOY_VERSION  README.md  install.sh  scripts/
provision/  direct/  bypass/  dns/  warp/  telemetry-upload/
```

`server/data/` **не** входит — на VPS каталог `data/` сохраняется отдельно (см. ниже).

Проверка согласованности (без Docker):

```bash
./scripts/check-deploy-bundle.sh
./scripts/test-install-unpack.sh   # распаковка, сохранение data/, повтор без tar
```

### Обновление vs первая установка

`install.sh` перед распаковкой копирует `stack/data` в `/tmp/ardtt-data-bak` и возвращает после `tar`. Сохраняются:

- `users.json` / `config.json` / ключи;
- состояние WARP (`data/warp/`, учётка wgcf);
- счётчики трафика bypass.

Пересобираются образы и контейнеры. Неуправляемые контейнеры с именами `ardtt` / `ardtt-*` (без compose-label) снимаются, чтобы не конфликтовать с `container_name`. Имена `nvpn-*` с прошлых установок тоже удаляются. После остановки старого host-network стека установщик снимает leftover `awg0` / `wdttraw0` / `warp0` / `cascade0` и **свои** `ip rule` lookup 51820 (подсети 10.8/10.9/10.10/10.99) **с хоста**, иначе они продолжают ломать nginx и чужой Docker. Чужой WireGuard с таблицей 51820 не трогается.

Если предыдущий запуск **стёр** `stack.tar.gz` (скрипт удаляет архив и при ошибке), повторный запуск **без** новой заливки идёт по уже распакованному `stack/docker-compose.yml` — так можно добить упавшую сборку Docker.

Место: нужно ≥ **1800 МБ** свободно на `/`, иначе установщик выходит с понятной ошибкой.

---

## Путь 2 — git + Compose

Тот же стек, что в APK, но исходники берутся с GitHub. Нужны Docker, `NET_ADMIN`, `/dev/net/tun`. Сборка тянет `amneziawg-go` / `amneziawg-tools` и RAW-сервер Path B.

Клонируйте **тег релиза** (`v0.5.238` = клиент 0.5.238 и стек 1.0.35), не обязательно `main`. Пока репозиторий приватный — HTTPS clone с VPS нужен PAT либо SSH-ключ с правом `repo`. Публичный репозиторий клонируется без секретов.

```bash
TAG=v0.5.238
git clone --depth 1 --branch "$TAG" \
  https://github.com/2kristalls36-hue/ARDTT.git /tmp/ardtt

# Вариант A — тот же install.sh, что из приложения (/opt/ardtt, data/ сохраняется)
install -d -m 755 /opt/ardtt
cp /tmp/ardtt/server/install.sh /opt/ardtt/install.sh
tar -C /tmp/ardtt/server --exclude=data --exclude='*.tmp' --exclude='__pycache__' \
  -czf /opt/ardtt/stack.tar.gz .
export ARDTT_PUBLIC_HOST=IP_ЭТОГО_VPS ARDTT_DEPLOY_VERSION=1.0.35
bash /opt/ardtt/install.sh

# Вариант B — compose прямо в клоне (без /opt/ardtt)
cd /tmp/ardtt/server
cp .env.example .env          # ARDTT_PUBLIC_HOST=IP_VPS; COMPOSE_PROFILES=isolated
docker compose --profile isolated up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice
```

Host network (если UDP через Docker DNAT не работает): `ARDTT_NETWORK_MODE=hostnet` в `.env` и `docker compose --profile hostnet up -d --build`.

`ARDTT_DEPLOY_VERSION` подхватывается из `.env` / `DEPLOY_VERSION` и отдаётся в `GET /health`.

Приложение **по-прежнему** может залить стек из APK: так VPS не зависит от GitHub. Когда репозиторий публичный, путь git достаточен, и держать копию стека в APK для установки не обязательно (клиент всё равно нужен для туннеля и админки).

---

## Что делает `install.sh` на VPS

Канонический файл: [`server/install.sh`](../server/install.sh). Каталог установки: `/opt/ardtt` (`ARDTT_INSTALL_DIR`).

| Progress | Шаг |
|----------|-----|
| 0.05 | root |
| 0.10 | каталог |
| 0.15 | распаковка tar **или** уже лежащий `stack/` |
| 0.22 | в архиве есть все build-контексты compose |
| 0.25 | Docker + compose plugin, если их не было (`get.docker.com`). Если на хосте уже есть чужие контейнеры — **не** останавливаем dockerd и **не** делаем `builder prune -af` |
| 0.28 | очистка мусора Docker/apt (`image prune -f`, не builder prune между сервисами), swap если RAM < 1.8 ГБ (существующий swapfile на shared VPS не сжимаем) |
| 0.40 | `.env`, `DEPLOY_VERSION` на хосте и в `stack/data/` (`COMPOSE_PROFILES=isolated`) |
| 0.45 | проверка места |
| 0.48 | на VPS < 1.8 ГБ RAM: `compose down` нашего стека; сброс BuildKit (stop docker, umount executor rootfs, `rm` без abort при busy) **только если нет чужих контейнеров** |
| 0.50 | `compose build ardtt` (один образ; повтор `--no-cache` после сброса BuildKit) → `compose up -d` |
| 0.74 | leftover TUN/iptables/ip-rule с хоста |
| 0.80 | снос build-cache и apt-архивов |
| 0.85 | `curl` provision `:9100` и telemetry на `ARDTT_TELEMETRY_PORT` (по умолчанию 9200) |
| 0.96 | ufw / firewalld: Direct UDP, Bypass UDP, 9100/tcp, telemetry TCP. `iptables -I INPUT` — только в `hostnet` |
| 1.00 | `ARDTT_DONE` |

Лог при ошибке: `/opt/ardtt/install.log`. При успехе временный лог и tar удаляются.

---

## Раскладка на диске

```
/opt/ardtt/
  install.sh              # последняя заливка из APK
  DEPLOY_VERSION          # то же число, что в /health
  install.log             # только если последний прогон упал
  stack/
    docker-compose.yml
    .env
    DEPLOY_VERSION
    provision/ direct/ bypass/ dns/ warp/ telemetry-upload/
    data/                 # volume; не из tar
      users.json          # config + users (ключи, hideIp, presence)
      config.json
      server_public.key
      DEPLOY_VERSION
      bypass-traffic.json # снимок счётчиков RAW
      warp/               # wgcf-account.toml, профиль warp0
```

После успешного деплоя не должно оставаться `stack.staging`, `stack.old`, `stack.tar.gz`, `install-live.log`, `install-run.log`. На 8–10 ГБ VPS установщик держит `/swapfile` ≈ 1 ГБ (не 2 ГБ) и не делает `docker image prune -af` — иначе пропадают неиспользуемые `stack-*` образы. `/opt/ardtt-distribution` — раздача APK, не мусор стека.

Не коммитить `server/data/` с боевыми секретами (уже в `.gitignore`).

---

## Как стек работает после деплоя

Источник истины — **provision** (`users.json`). Остальные сервисы **ждут** появления файла (до ~2 мин) и дальше следят за mtime.

```
provision ──writes──► /data/users.json
    │
    ├─ direct-sync  → awg0.conf  → amneziawg-go   (каждые 5 с при изменении)
    ├─ bypass-sync  → passwords.json + raw_ip     (SIGHUP wdtt-server)
    └─ warp         → ip rule from 10.8/10.9.{id} → table 51820, если hideIp
                      (на каскаде правило ставит выход, не вход)
```

### Direct (Path A)

UDP `ARDTT_DIRECT_PORT`. Клиенты — peers AmneziaWG с `AllowedIPs=10.8.0.{hostId}/32`.  
FORWARD + MASQUERADE в WAN. Смена пользователей → `awg syncconf` без рестарта контейнера.

### Bypass (Path B)

Клиент доходит до VPS **уже после TURN** (звонок на телефоне). На сервере слушается RAW UDP `ARDTT_BYPASS_PORT`, без DTLS. Пароль и `10.9.0.{hostId}` берутся из того же `users.json`. Трафик в админке — из `bypass-traffic.json`.

Hash звонка на сервер **не** кладётся.

### DNS

Клиентский DNS в профиле — шлюз туннеля (`10.8.0.1` / `10.9.0.1`). dnsmasq резолвит через Cloudflare по **main**.  
Если Hide IP включён, `ip rule` prio 100 (`iif awg0|wdttraw0 dport 53 → main`) не пускает :53 в `warp0`.

### Hide IP / WARP

`POST /v1/hide-ip` → `users.json.hideIp`. На standalone вход warp-entrypoint дебаунсит ~1 с и вешает `from 10.8.0.{id}/32`. На каскаде вход в `passthrough`, а выход опрашивает `GET /v1/hide-ip-prefixes` у `10.10.0.1` и вешает те же `/32` на своём `warp0`. DNS и подсети туннелей остаются на main. Клиент **не** рестартует TUN.

### Provision API (телефон бьёт в `:9100`)

| Метод | Путь | Зачем |
|-------|------|--------|
| GET | `/health` | `{ ok, service, deployVersion }` — статус карточки сервера |
| GET/POST | `/v1/users` | список / создать (тело создания = JSON профиля) |
| POST | `/v1/users/update` | срок, устройства, деактивация, лимит трафика |
| POST | `/v1/users/unbind-device` | снять deviceId |
| POST | `/v1/users/delete` | удалить пользователя |
| GET | `/v1/profile/{name}` | тот же JSON, что импортирует клиент |
| POST | `/v1/presence` | online в админке |
| POST | `/v1/hide-ip` | WARP egress |
| GET/POST | `/v1/egress-ip` | публичный IP (через `warp0`, если hideIp) |

Авторизации на provision нет: это ваш VPS. Порт 9100 должен быть доступен с телефона админа (и с клиента для presence/hide-ip).

Профиль, который выдаёт provision:

```json
{
  "name": "alice",
  "hostId": 2,
  "direct": { "endpoint": "VPS:51820", "address": "10.8.0.2/32", "…": "…" },
  "bypass": { "peer": "VPS:56003", "address": "10.9.0.2/32", "password": "…", "mode": "raw" }
}
```

Клиенты создаются из приложения (Сервер → Клиенты) или `./scripts/create-user.sh`.

---

## Порты

| Порт | Протокол | Кто | Обязательно снаружи |
|------|----------|-----|---------------------|
| SSH (22) | TCP | деплой из приложения | да, для админ-деплоя; git-путь можно с локальной машины |
| 51820 | UDP | direct (предпочтительно) | да, Path A; при автовыборе может стать другим свободным UDP |
| 56003 | UDP | bypass RAW (предпочтительно) | да, Path B; то же для автовыбора |
| 9100 | TCP | provision | да, health / профили / hide-ip |
| 9200 | TCP | telemetry | если нужен приём логов с телефонов |

`ARDTT_AUTO_PORTS=1` (кнопка в форме деплоя по умолчанию): если 51820/56003 заняты другим сервисом, установщик берёт следующие свободные UDP и синхронизирует их в `.env`, `users.json` (через provision из env) и `ARDTT_DONE`. При обновлении с автовыбором сначала пробуются порты прошлого деплоя. `ARDTT_AUTO_PORTS=0` + занятый порт → ошибка, как раньше.

TURN VK — на стороне **клиента**, на VPS отдельного TURN нет.

---

## Когда бампать `DEPLOY_VERSION`

Файл: `server/DEPLOY_VERSION`. Синхронно обновить:

1. `android/app/src/main/assets/deploy/DEPLOY_VERSION`
2. `DeployBundle.FALLBACK_VERSION`
3. `./scripts/pack-deploy-assets.sh` (или просто собрать APK — Gradle сам упакует)

Бамп нужен, если изменились `docker-compose.yml`, Dockerfiles, entrypoint'ы, `install.sh` или vendored bypass. Правка только UI телефона — нет.

Формат — semver стека (`1.0.x`), не версия APK.

---

## Снятие и данные

```bash
cd /opt/ardtt/stack
docker compose down          # контейнеры; data/ остаётся
# полный снос (ключи тоже):
# rm -rf /opt/ardtt
```

Удаление карточки сервера в приложении **не** трогает VPS.

---

## Чек-лист после установки

```bash
cd /opt/ardtt/stack
COMPOSE_PROFILES=isolated docker compose ps
curl -s http://127.0.0.1:9100/health
# ожидается: "ok": true, "deployVersion": "1.0.35"

ss -ulnp | grep -E '51820|56003'
ss -tlnp | grep -E '9100|9200'
docker exec ardtt provision -cmd create-user -name smoke -data /data
```

С телефона: карточка VPS — ОС справа, «Онлайн» под ней, деплой слева (или «Требуется обновление · …»), создание клиента, импорт профиля, Connect.

---

## Типичные отказы

| Симптом | Что проверить |
|---------|----------------|
| «В APK нет deploy/stack.tar.gz» | Собрать APK с Gradle (`packDeployAssets`) или вручную `scripts/pack-deploy-assets.sh`. Либо поставьте стек клоном тега — [Путь 2](#путь-2--git--compose) |
| `git clone`: Authentication failed | Репозиторий ещё приватный: PAT/SSH с правом `repo`, либо ставьте из приложения |
| `install.sh` + «Мало места» | На 8–10 ГБ VPS порог обновления ~500–1100 МБ; установщик сожмёт 2 ГБ swap до 1 ГБ и не удаляет неиспользуемые `stack-*` образы. Не делайте `docker image prune -af` вручную. |
| `install.sh exit=1`, `Device or resource busy` в `/var/lib/docker/buildkit/.../rootfs` | Стек ≥**1.0.34**: umount + повтор, установка не падает. На 1 ГБ VPS старый `rm -rf` после `stop docker` обрывал каскад. Обновите APK и снова «Установить». |
| SSH timeout / permission | user/порт/ключ; для не-root нужен sudo-пароль |
| `/health` не отвечает после DONE | `docker compose --profile isolated logs`; `ARDTT_PUBLIC_HOST` и публикация `:9100` |
| telemetry не принимает логи, `:9200` занят | Порт занят другим процессом. `ARDTT_TELEMETRY_PORT=9210` или освободите 9200; установщик не стартует gunicorn внутри `ardtt` |
| Чужие сайты/контейнеры на VPS отвалились после деплоя | Нужен стек ≥1.0.32 (isolated). Обновите деплой из приложения. Запасной `ARDTT_NETWORK_MODE=hostnet` снова шарит host netns |
| UDP Direct не коннектится, TCP :9100 жив | Docker UDP DNAT. Попробуйте `ARDTT_NETWORK_MODE=hostnet` |
| Карточка «нужно обновить» | APK новее стека — «Обновить деплой»; или рассинхрон `DEPLOY_VERSION` |
| «Удалить» не снимает карточку | SSH до VPS не прошёл или uninstall оборвался — карточка специально остаётся. Повторите или поправьте креды |
| Hide IP: ping есть, HTTPS нет | MSS clamp на warp0 (уже в entrypoint); DNS не через WARP |
| Повторный деплой «нет tar» | С 1.0.12 установщик продолжает с уже распакованного `stack/` |

Телеметрия категории `deploy` (старт, SSH, прогресс, хвост `install.log`) — [TELEMETRY.md](TELEMETRY.md).
