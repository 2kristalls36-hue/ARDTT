# nonameVPN — архитектура

Клиентский VPN с двумя путями до своего VPS:

1. **Прямой** — AmneziaWG 2.0 (основной режим).
2. **Обход** — при отсутствии прямой видимости до VPS трафик идёт через TURN-релей (принцип WDTT / qWDTT), в режиме **RAW**: `RAW IP → WRAP(RTP AEAD) → TURN → VPS`. **Без DTLS и без WireGuard.** WDTT для пользователя невидим.

---

## Зафиксированные решения

| # | Вопрос | Решение |
|---|--------|---------|
| 1 | Платформа | **Цель — Android.** База: форк `amneziawg-android` + встройка bypass-ядра. Go-транспорт можно отлаживать отдельно, но продукт — APK. |
| 2 | Кодовая база | **Форк Amnezia (клиент) + адаптация WDTT/qWDTT (bypass) + WARP userspace (`wireproxy`→tun2socks).** |
| 3 | Оптимизация Path B | **RAW как в qWDTT: WRAP + TURN, без DTLS и без AWG/WG.** |
| — | Деплой | **Три контейнера** (`direct`, `bypass`, `warp`) + общий `host_id` в трёх подсетях. |
| 4 | Имя / URI | Пока `nonameVPN` / `nvpn://` (плейсхолдер). |
| 5 | Форматы | **Только свой профиль/ссылка.** Без импорта `wdtt://`. Опционально: вход в VK-аккаунт для обхода белых списков. |

---

## Зачем так

| Режим | Когда | Плюсы | Минусы |
|-------|--------|-------|--------|
| **AWG 2.0 direct** | UDP до VPS проходит | Низкая задержка, полный AWG 2.0 | Падает, если IP/порт VPS режут |
| **TURN + RAW** | Нет LOS / whitelist | Маскировка под WebRTC, макс. скорость обхода | RTT выше; зависимость от TURN/VK |

### Стек Path B (как в qWDTT Raw) — проверено по исходникам

В `SpaceNeuroX/proxy-turn-vk-android`:

- `TurnParams.RawMode` **подразумевает `NoDTLS`** — сервер `-listen-raw` DTLS не принимает.
- Клиент: `obfsDirectConn` — «RTP-obfs AEAD прямо поверх TURN relay, **без DTLS**».
- Комментарий в коде: DTLS поверх WRAP был оверхедом (self-signed + `InsecureSkipVerify` не давал реальной защиты, удваивал AEAD и требовал handshake на каждый worker).

```
Apps → VpnService TUN (raw IP)
         ↓
      WRAP = RTP header + AEAD (ключ из пароля, HKDF)
         ↓
      TURN Allocate / ChannelData  (VK relay, UDP или TCP)
         ↓
      VPS -listen-raw → TUN + NAT → интернет
```

**Нет DTLS. Нет WireGuard/AWG внутри обхода.**

Классический WDTT (для сравнения, нам не нужен в MVP):

```
WG UDP → DTLS → WRAP/RTP → TURN → VPS -listen (DTLS) → внутренний WG
```

Есть ещё промежуточный `-listen-direct` / `NoDTLS` без RAW: WRAP→TURN, но внутри всё ещё WG. Мы берём именно **Raw**.

---

## Высокоуровневая схема

```
┌──────────────────────── Android-приложение ────────────────────┐
│  UI (форк AmneziaWG)                                            │
│    · серверы / подключение / опциональный вход VK               │
│    · пользователю не показывают «WDTT»                          │
│       │                                                         │
│  Connection Manager  ── probe / health / failover               │
│       │                                                         │
│  ┌────┴────┐                                                    │
│  │ Path A  │  AmneziaWG 2.0  ──────── UDP ────────►  VPS:AWG    │
│  │ Path B  │  VpnService TUN ◄─► bypass Go (RAW)                │
│  └────┬────┘         │                                          │
│       │              ▼                                          │
│       │     WRAP(RTP AEAD) → VK TURN → VPS -listen-raw          │
│       │     (без DTLS)                                          │
│       │     auth: анонимный звонок ИЛИ VK-аккаунт (whitelist)   │
└───────┴─────────────────────────────────────────────────────────┘
```

**Path A (direct):**

```
Apps → VpnService / AWG GoBackend → UDP → VPS amneziawg 2.0
```

**Path B (bypass / RAW):**

```
Apps → VpnService TUN → raw IP → WRAP(RTP AEAD) → TURN →
  → VPS bypass-server (-listen-raw) → TUN/NAT → интернет
```

---

## Стратегия форков

### Клиент Android

База: **[amnezia-vpn/amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android)** (Apache-2.0).

Добавляем:

- `connmgr` — probe AWG → failover на bypass;
- native Go-модуль bypass (из qWDTT / proxy-turn-vk-android): TURN, WRAP, RAW;
- экран/настройка: «Войти через VK» (для whitelist), без упоминания WDTT;
- свой формат профиля / `nvpn://` share.

Свой `applicationId`, чтобы ставить рядом с официальной AmneziaWG.

### Сервер VPS

**Три контейнера data-plane + общий provision** в одном Compose:

| Сервис (внутр. имя) | Роль | Порты / iface (пример) |
|---------------------|------|------------------------|
| `direct` | AmneziaWG 2.0 | UDP `51820`, `awg0` |
| `bypass` | fork wdtt `-listen-raw` (WRAP+TURN) | UDP `56003`, `raw0` |
| `warp` | WARP egress: `wireproxy` SOCKS → `tun2socks` / `tun-warp` | `warp0` (без публичного listen) |
| `provision` | реестр users / `host_id` / один `nvpn://` | localhost/API |

Пользователю в UI: «Установить сервер» → один `compose up`. Имена AWG / WDTT / WARP снаружи не обязательны.

Подробнее: [Деплой](#деплой-три-контейнера--общая-маршрутизация).

### Лицензии (важно)

- `amneziawg-android` — обычно **Apache-2.0** → форк клиента ок при сохранении NOTICE/copyright.
- WDTT/qWDTT часто **GPL-3.0** → код bypass, слинкованный в приложение, тянет GPL на соответствующую часть (или на всё, если линковка жёсткая). Нужно: сохранить тексты лицензий, не смешивать «тихо», в README явно указать происхождение.

Практически: форк допустим и обычен; юридически — соблюсти лицензии исходников.

---

## Деплой: три контейнера + общая маршрутизация

### Схема Compose

```
docker-compose.yml  (network_mode: host, NET_ADMIN, /dev/net/tun)
┌─ direct ────────┐  ┌─ bypass ─────────┐  ┌─ warp ─────────────────────┐
│ AmneziaWG 2.0   │  │ WRAP+RAW listen  │  │ wgcf/WARP conf             │
│ awg0            │  │ raw0             │  │   → wireproxy :SOCKS       │
│ 10.8.0.0/24     │  │ 10.9.0.0/24      │  │   → tun2socks → warp0      │
└────────┬────────┘  └────────┬─────────┘  │ 10.10.0.0/24               │
         │                    │            └────────────┬────────────────┘
         └────────────────────┼─────────────────────────┘
                              ▼
                       /data + provision
                       host_id → один octet во всех трёх подсетях
```

Монолит в один контейнер — нет. Три независимых процесса, одна логика учёта.

### Общая логика IP (`host_id`)

Один реестр пользователей. При создании клиента — один `host_id`, адреса **параллельно** в трёх подсетях:

| Path | Подсеть | Адрес клиента | iface |
|------|---------|---------------|--------|
| direct | `10.8.0.0/24` | `10.8.0.{id}/32` | `awg0` |
| bypass | `10.9.0.0/24` | `10.9.0.{id}/32` | `raw0` |
| warp | `10.10.0.0/24` | `10.10.0.{id}/32` | `warp0` |

```
create user "alice"
  → host_id=5
  → direct: 10.8.0.5
  → bypass: 10.9.0.5
  → warp:   10.10.0.5
  → nvpn:// со всеми блоками
  → reload direct + bypass + warp
```

Аллокация только в **provision**; контейнеры только применяют lease из `/data`.

### Контейнер `warp`: wireproxy → tun2socks

Стек (userspace, без `warp-svc`):

```
Cloudflare WARP (WireGuard conf, wgcf / аналог)
        ↓
   wireproxy  →  локальный SOCKS5
        ↓
   tun2socks / tun-warp  →  iface warp0
        ↓
   NAT / policy: трафик 10.10.0.0/24 (и опц. exit=warp) уходит в WARP
```

Зачем отдельно: выход с «облачного» IP Cloudflare; не смешивать с AWG/bypass listen; независимый restart при деградации WARP.

Роли трафика (уточняемо в реализации):

1. **Параллельный path для клиента** — в профиле блок `warp` с `address: 10.10.0.{id}`; connmgr может выбрать exit через WARP, когда нужен такой выход.
2. **И/или общий egress** — traffic с `awg0`/`raw0` по policy route на `warp0` (если в профиле `exit=warp`).

MVP: поднять iface + третью подсеть + учёт `host_id`; policy exit можно вторым шагом.

### Память WARP (зафиксированный фикс)

Официальный `warp-svc` (и тяжёлые WARP-стеки) часто течёт по RAM до OOM.

**Решение (из практики / чат `3545ac4e-…`):**

1. Userspace-стек: `wireproxy` → SOCKS → `tun2socks` / `tun-warp` (не держать голый `warp-svc` как единственный процесс без ограничений).
2. На сервис/контейнер `warp`:
   - **`MemoryMax≈512M`** (systemd) или `mem_limit: 512m` (Docker);
   - **`Restart=always`** / `restart: always` — при упоре в лимит OOM → авторестарт, RAM сбрасывается.

```ini
# пример systemd override
[Service]
MemoryMax=512M
Restart=always
RestartSec=3
```

```yaml
# пример compose
warp:
  mem_limit: 512m
  restart: always
```

Опционально позже: healthcheck по SOCKS/egress и recycle по таймеру; для MVP достаточно MemoryMax + restart.

### Именование

| Compose | Наружу |
|---------|--------|
| `direct` | прямое |
| `bypass` | обход |
| `warp` | «защищённый выход» / скрыто в failover |
| не светить WDTT/qWDTT/wireproxy в UI | ok в NOTICE |

### Практическая схема MVP

1. `docker compose up -d` (host network).
2. `provision` — три подсети + пул `host_id`.
3. Подъём `awg0` / `raw0` / `warp0` + NAT.
4. Create-user → три адреса с одним octet → один `nvpn://`.
5. Firewall: UDP AWG + UDP raw-listen; WARP — исходящий к Cloudflare.

### Итог

**Три контейнера + общий provision:** зеркальный `host_id` в `10.8` / `10.9` / `10.10`; WARP через wireproxy→tun2socks без `warp-svc`.

---

## Connection Manager

1. По умолчанию `prefer=direct`: probe AWG handshake / check через туннель (3–8 с) → успех Path A, иначе Path B.
2. `prefer=bypass` / «только обход» — сразу Path B (whitelist-сети).
3. Healthcheck + reconnect; при Path B — refresh TURN-кредов, смена hash, `transport` UDP↔TCP (TCP по умолчанию стабильнее).
4. Один активный VpnService-туннель.

```
Idle → ProbingDirect → ConnectedDirect
                   └→ ConnectingBypass → ConnectedBypass
Connected* → Reconnecting → …
```

---

## VK-авторизация (whitelist)

Пользователь **не** настраивает WDTT. В UI максимум:

- переключатель / кнопка **«Войти через VK»** — для сетей с белыми списками, где анонимные TURN-креды режут, а «как звонок с аккаунта» проходит;
- по умолчанию — анонимный поток (как в qWDTT), без обязательного логина.

Внутри: кэш TURN ~9 мин, WebView для логина/капчи при необходимости. Снаружи это просто «режим обхода» / «вход VK».

---

## Профиль (только свой формат)

```json
{
  "name": "home-vps",
  "deviceId": "...",
  "hostId": 5,
  "prefer": "direct",
  "direct": {
    "endpoint": "x.x.x.x:51820",
    "privateKey": "...",
    "peerPublicKey": "...",
    "presharedKey": "...",
    "address": "10.8.0.5/32",
    "dns": ["1.1.1.1"],
    "mtu": 1280,
    "awg": {
      "Jc": 4, "Jmin": 40, "Jmax": 70,
      "S1": 0, "S2": 0, "S3": 0, "S4": 0,
      "H1": "1-100", "H2": "101-200", "H3": "201-300", "H4": "301-400",
      "I1": "<cps...>", "I2": "", "I3": "", "I4": "", "I5": ""
    }
  },
  "bypass": {
    "peer": "x.x.x.x:56003",
    "address": "10.9.0.5/32",
    "password": "...",
    "hashes": ["..."],
    "workers": 9,
    "transport": "tcp",
    "mode": "raw"
  },
  "warp": {
    "address": "10.10.0.5/32",
    "exit": true
  }
}
```

`hostId` общий; три `address` с одним octet в `10.8` / `10.9` / `10.10`. `warp` на клиенте — адресация/политика выхода; сам WARP-туннель живёт на VPS.

Шаринг: `nvpn://...` / QR. Импорта `wdtt://` нет. Серверный deploy сам кладёт hash/password в профиль.

---

## Слои Path B

Снаружи → внутрь:

1. **TURN** — транспорт через VK relay (UDP или TCP до relay).
2. **WRAP** — RTP + AEAD (единственное шифрование обхода; ключ из пароля).
3. **RAW IP** — пакеты с TUN клиента.

DTLS в Path B нет. Path A — только AWG 2.0.

---

## Компоненты

### Клиент

| Модуль | Роль | Источник |
|--------|------|----------|
| UI + VpnService AWG | Path A, экраны | форк amneziawg-android |
| `bypass` (Go `.so`) | TURN + WRAP + RAW (NoDTLS) | адаптация qWDTT client |
| `connmgr` | probe / failover | новый |
| `profile` | свой JSON / `nvpn://` | новый |
| VK login (опц.) | whitelist TURN | из qWDTT WebView-потока |

### Сервер

| Модуль | Роль |
|--------|------|
| compose service `direct` | AmneziaWG 2.0, `10.8.0.0/24` |
| compose service `bypass` | `-listen-raw`, `10.9.0.0/24` |
| compose service `warp` | wireproxy→tun2socks, `10.10.0.0/24` |
| `provision` + `/data` | `host_id` → IP×3, один `nvpn://` |

---

## MVP

1. Форк amneziawg-android: свой package id, AWG 2.0 direct как сейчас.
2. Встройка bypass RAW + авто-failover.
3. VPS: compose `direct` + `bypass` + `warp`, один deploy → профиль с тремя адресами.
4. Опциональный «Войти через VK» в настройках обхода.
5. Без публичного WDTT-брендинга и без `wdtt://`.

**Не в MVP:** Linux CLI как продукт, SOCKS-only, свой TURN, паритет всего UI Amnezia VPN (полного клиента с OpenVPN/Cloak и т.д.) — только AWG-линия.

---

## Риски

| Риск | Митигация |
|------|-----------|
| VK API/TURN ломается | Изолированный `bypass`-модуль; несколько hash; кэш кредов |
| Captcha | Auto → WebView; опциональный VK-логин |
| MTU на RAW | MTU ≤ 1280, MSS clamp на сервере |
| UDP до TURN режут | `transport=tcp` по умолчанию |
| GPL (WDTT) × Apache (Amnezia) | NOTICE, LICENSE, не скрывать происхождение кода |
| Сложность merge upstream Amnezia | Держать bypass тонким слоем; периодический rebase |

---

## Следующий шаг

1. Каркас: форк amneziawg-android + `server/` compose (`direct`, `bypass`, `warp`).
2. VPS: AWG 2.0 + bypass RAW + wireproxy/tun2socks.
3. `provision` с `host_id` на три подсети.
4. Bypass `.so` + `connmgr` failover; опциональный VK-логин.
5. На `warp`: `MemoryMax≈512M` + `restart: always` (уже в архитектуре).
