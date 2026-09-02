# Деплой сервера ARDTT

Как ставится и как живёт self-hosted стек на VPS.  
Клиентская сторона деплоя — вкладка **Серверы** в режиме администратора Android.  
Состав сервисов и смысл Path A/B: [ARCHITECTURE.md](ARCHITECTURE.md), [LEGEND.md](LEGEND.md).

Версия **стека** (`DEPLOY_VERSION`, сейчас **1.0.16**) независима от `versionName` приложения. Её бампят только когда меняется то, что уезжает на VPS (Compose, `install.sh`, образы сервисов).

---

## Два способа поставить сервер

| Способ | Когда | Что происходит |
|--------|--------|----------------|
| **Из приложения** | Боевой путь. Админ с телефоном и SSH на чистый VPS | APK заливает `stack.tar.gz` + `install.sh` по SSH → Docker Compose на хосте |
| **Git + Compose** | Разработка, уже есть shell на машине | Клон репозитория, `docker compose up --build` в `server/` |

Оба способа поднимают **один и тот же** стек: `provision`, `direct`, `bypass`, `dns`, `warp`, `telemetry`.  
Пользователи и ключи живут в `users.json` и **переживают** повторный деплой.

```
Телефон (админ)
  SSH (пароль или PEM)
      │  upload  /opt/nonamevpn/stack.tar.gz
      │  upload  /opt/nonamevpn/install.sh
      │  env NVPN_PUBLIC_HOST=… bash install.sh
      ▼
VPS  /opt/nonamevpn/stack/     ← compose + исходники сервисов
     /opt/nonamevpn/stack/data ← users.json, ключи, warp state
     host network + TUN
      │
      ├─ :51820/udp  AmneziaWG (Path A)
      ├─ :56003/udp  RAW/WRAP  (Path B, после TURN на клиенте)
      ├─ :9100/tcp   provision (/health, пользователи, профили)
      └─ :9200/tcp   telemetry upload
```

---

## Что поднимается

Все сервисы — `network_mode: host`, `direct`/`bypass`/`warp` ещё `NET_ADMIN` и `/dev/net/tun`.

| Контейнер | Образ | Роль |
|-----------|--------|------|
| `nvpn-provision` | `server/provision` | Источник истины: `host_id`, AWG-ключи, пароли bypass, JSON-профиль, `GET /health` с `deployVersion` |
| `nvpn-direct` | `server/direct` | AmneziaWG 2.0 (`amneziawg-go` + `awg`) на `awg0`, подсеть `10.8.0.0/24` |
| `nvpn-bypass` | `server/bypass` | `wdtt-server -listen-raw` на `wdttraw0`, подсеть `10.9.0.0/24` |
| `nvpn-dns` | `server/dns` | dnsmasq на шлюзах `10.8.0.1` / `10.9.0.1`, upstream `1.1.1.1`/`1.0.0.1` **через main**, не через WARP |
| `nvpn-warp` | `server/warp` | wgcf → `warp0`; hideIp → table `51820`. DNS (:53) остаётся на main |
| `nvpn-telemetry` | `server/telemetry-upload` | `POST /api/upload-log` с телефона |

Один `host_id` (начиная с **2**, `.1` — шлюз) даёт клиенту оба адреса: `10.8.0.{id}` и `10.9.0.{id}`.  
WARP — не третий путь подключения, а **egress** выбранных пользователей.

---

## Путь 1 — деплой из Android

### UI

1. Настройки → разблокировать режим администратора (долгий тап / PIN).
2. Вкладка **Серверы** → «Добавить сервер» (первый раз) или карточка VPS → **Обновить деплой**.
3. SSH host, порт, user (`root` по умолчанию), пароль **или** PEM.
4. Публичный host (`NVPN_PUBLIC_HOST`) — то, что попадёт в endpoint профиля; обычно = IP VPS.
5. Порты Direct UDP / Bypass UDP (по умолчанию 51820 / 56003).
6. «Сохранить сервер» только пишет цель в encrypted prefs. Установка — **«Установить на VPS»**.

Креды лежат в `EncryptedSharedPreferences` (`nvpn_servers`), не в профиле VPN.

Список серверов зондрует `http://{publicHost}:9100/health`. Сравнение `deployVersion` с версией в APK:

- зелёный — установленный стек = стек в этом приложении;
- оранжевый — VPS онлайн, но версия старая → «Обновить деплой»;
- нет связи — «Не установлен / нет связи».

### Что делает `DeployEngine`

Класс: `android/.../deploy/DeployEngine.kt`. Таймаут установщика — 45 минут.

1. SSH (JSch). Не-root: команда через `sudo -S` с паролем.
2. `mkdir -p /opt/nonamevpn`
3. Загрузка архива стека из assets (см. [Бандл в APK](#бандл-в-apk)).
4. Загрузка `install.sh` + `DEPLOY_VERSION`.
5. Запуск:

```bash
NVPN_PUBLIC_HOST='…' NVPN_DIRECT_PORT=51820 NVPN_BYPASS_PORT=56003 \
NVPN_DEPLOY_VERSION='1.0.16' bash /opt/nonamevpn/install.sh
```

6. Разбор stdout построчно (UTF-8, без ANSI):
   - `NVPN_PROGRESS|<0..1>|<шаг>` — полоса и подпись в UI;
   - `NVPN_ERROR|<текст>` — ошибка даже при exit 0;
   - `NVPN_DONE|…` — успех;
   - `NVPN_WARN|…` — в лог, деплой продолжается.
7. После успеха: prune образов на хосте, `lastDeployedAtMs` в prefs.
8. После ошибки: `tail` удалённого `/opt/nonamevpn/install.log` в телеметрию (если включена запись).

Отмена = `session.disconnect()`.

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
docker-compose.yml  .env.example  DEPLOY_VERSION  README.md  install.sh  scripts/
provision/  direct/  bypass/  dns/  warp/  telemetry-upload/
```

`server/data/` **не** входит — на VPS каталог `data/` сохраняется отдельно (см. ниже).

Проверка согласованности (без Docker):

```bash
./scripts/check-deploy-bundle.sh
./scripts/test-install-unpack.sh   # распаковка, сохранение data/, повтор без tar
```

### Обновление vs первая установка

`install.sh` перед распаковкой копирует `stack/data` в `/tmp/nvpn-data-bak` и возвращает после `tar`. Сохраняются:

- `users.json` / `config.json` / ключи;
- состояние WARP (`data/warp/`, учётка wgcf);
- счётчики трафика bypass.

Пересобираются образы и контейнеры. Неуправляемые контейнеры с именами `nvpn-*` (без compose-label) снимаются, чтобы не конфликтовать с `container_name`.

Если предыдущий запуск **стёр** `stack.tar.gz` (скрипт удаляет архив и при ошибке), повторный запуск **без** новой заливки идёт по уже распакованному `stack/docker-compose.yml` — так можно добить упавшую сборку Docker.

Место: нужно ≥ **1800 МБ** свободно на `/`, иначе установщик выходит с понятной ошибкой.

---

## Путь 2 — git + Compose

Нужны Docker, `NET_ADMIN`, `/dev/net/tun`. Сборка тянет `amneziawg-go` / `amneziawg-tools` и RAW-сервер Path B.

```bash
cd server
cp .env.example .env          # NVPN_PUBLIC_HOST=IP_VPS
echo 1.0.16 > DEPLOY_VERSION   # или оставить как в репо
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice   # JSON профиля в stdout
```

`NVPN_DEPLOY_VERSION` подхватывается из `.env` / `DEPLOY_VERSION` и отдаётся в `GET /health`.

Тот же `server/install.sh` можно прогнать вручную, если положить дерево в `/opt/nonamevpn/stack` (или залить tar) и вызвать от root:

```bash
export NVPN_PUBLIC_HOST=1.2.3.4 NVPN_DEPLOY_VERSION=1.0.16
bash /opt/nonamevpn/install.sh
```

---

## Что делает `install.sh` на VPS

Канонический файл: [`server/install.sh`](../server/install.sh). Каталог установки: `/opt/nonamevpn` (`NVPN_INSTALL_DIR`).

| Progress | Шаг |
|----------|-----|
| 0.05 | root |
| 0.10 | каталог |
| 0.15 | распаковка tar **или** уже лежащий `stack/` |
| 0.22 | в архиве есть все build-контексты compose |
| 0.25 | Docker + compose plugin, если их не было (`get.docker.com`) |
| 0.40 | `.env`, `DEPLOY_VERSION` на хосте и в `stack/data/` |
| 0.45 | prune + проверка места |
| 0.50 | `compose build` (parallel limit 1, plain logs) → `compose up -d` |
| 0.80 | снос build-cache и apt-архивов |
| 0.85 | `curl http://127.0.0.1:9100/health` |
| 0.96 | ufw / firewalld: Direct UDP, Bypass UDP, 9100/tcp, 9200/tcp |
| 1.00 | `NVPN_DONE` |

Лог при ошибке: `/opt/nonamevpn/install.log`. При успехе временный лог и tar удаляются.

---

## Раскладка на диске

```
/opt/nonamevpn/
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
```

### Direct (Path A)

UDP `NVPN_DIRECT_PORT`. Клиенты — peers AmneziaWG с `AllowedIPs=10.8.0.{hostId}/32`.  
FORWARD + MASQUERADE в WAN. Смена пользователей → `awg syncconf` без рестарта контейнера.

### Bypass (Path B)

Клиент доходит до VPS **уже после TURN** (звонок на телефоне). На сервере слушается RAW UDP `NVPN_BYPASS_PORT`, без DTLS. Пароль и `10.9.0.{hostId}` берутся из того же `users.json`. Трафик в админке — из `bypass-traffic.json`.

Hash звонка на сервер **не** кладётся.

### DNS

Клиентский DNS в профиле — шлюз туннеля (`10.8.0.1` / `10.9.0.1`). dnsmasq резолвит через Cloudflare по **main**.  
Если Hide IP включён, `ip rule` prio 100 (`iif awg0|wdttraw0 dport 53 → main`) не пускает :53 в `warp0`.

### Hide IP / WARP

`POST /v1/hide-ip` → `users.json.hideIp`. warp-entrypoint дебаунсит ~1 с и вешает `from 10.8.0.{id}/32` и `from 10.9.0.{id}/32` в table 51820. DNS и подсети туннелей остаются на main. Контейнер **не** перезапускают по OOM (`GOMEMLIMIT` ~400 MiB).

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
| SSH (22) | TCP | деплой из приложения | да, для админ-деплоя |
| 51820 | UDP | direct | да, Path A |
| 56003 | UDP | bypass RAW | да, Path B (после TURN) |
| 9100 | TCP | provision | да, health / профили / hide-ip |
| 9200 | TCP | telemetry | если нужен приём логов с телефонов |

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
cd /opt/nonamevpn/stack
docker compose down          # контейнеры; data/ остаётся
# полный снос (ключи тоже):
# rm -rf /opt/nonamevpn
```

Удаление карточки сервера в приложении **не** трогает VPS.

---

## Чек-лист после установки

```bash
docker compose -f /opt/nonamevpn/stack/docker-compose.yml ps
curl -s http://127.0.0.1:9100/health
# ожидается: "ok": true, "deployVersion": "1.0.16"

ss -ulnp | grep -E '51820|56003'
ss -tlnp | grep -E '9100|9200'
```

С телефона: карточка VPS «Онлайн · деплой 1.0.16 · актуален», создание клиента, импорт профиля, Connect.

---

## Типичные отказы

| Симптом | Что проверить |
|---------|----------------|
| «В APK нет deploy/stack.tar.gz» | Собрать APK с Gradle (`packDeployAssets`) или вручную `scripts/pack-deploy-assets.sh` |
| `install.sh exit=…` + «Мало места» | Диск VPS < 1.8 ГБ; `docker system prune -af` |
| SSH timeout / permission | user/порт/ключ; для не-root нужен sudo-пароль |
| `/health` не отвечает после DONE | `docker compose logs provision`; `NVPN_PUBLIC_HOST` и слушатель `:9100` |
| Карточка «нужно обновить» | APK новее стека — «Обновить деплой»; или рассинхрон `DEPLOY_VERSION` |
| Hide IP: ping есть, HTTPS нет | MSS clamp на warp0 (уже в entrypoint); DNS не через WARP |
| Повторный деплой «нет tar» | С 1.0.12 установщик продолжает с уже распакованного `stack/` |

Телеметрия категории `deploy` (старт, SSH, прогресс, хвост `install.log`) — [TELEMETRY.md](TELEMETRY.md).
