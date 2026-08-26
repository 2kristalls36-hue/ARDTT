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
| — | Failover на RAW | После fail AWG: probe VPS → bigtech:443 → yandex.ru; RAW авто только на классе Whitelist; на открытой сети — предупреждение как в qWDTT 1.0.5–1.3.2. |
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

### Состояния

```
Idle
  → ProbingNetwork          (классификация сети)
  → ProbingDirect           (AWG handshake)
       ├→ ConnectedDirect
       └→ EvaluatingBypass  (перед RAW — проверка БС / предупреждение)
            ├→ ConnectingBypass → ConnectedBypass
            ├→ BypassBlockedWarning  (диалог как в qWDTT 1.0.5–1.3.2)
            └→ NoNetwork / Abort
Connected* → Reconnecting → …
```

По умолчанию `prefer=direct`. `prefer=bypass` / «только обход» — сразу Path B (после лёгкого NoNetwork-gate). Один активный VpnService-туннель.

---

### Network probe (перед failover на RAW)

Порядок **обязательный**:

1. **VPS** — доступность своего сервера.
2. **Bigtech :443** — `google.com`, `amazon.com`, `apple.com`, `microsoft.com` (как в qWDTT).
3. **Yandex :443** — `yandex.ru` (на БС оператора обычно доступен; отличает «нет интернета» от «белые списки»).

#### 1) Probe VPS

Цель: понять, есть ли смысл в Path A / жив ли хост.

| Проверка | Зачем |
|----------|--------|
| TCP connect к `bypass.peer` (host:port raw, напр. `:56003`), timeout ~2–3 с | Порт RAW слушает UDP, но TCP SYN часто даёт «host alive / filtered»; если RST/accept — хост точно жив |
| Параллельно: короткий **AWG handshake attempt** на `direct.endpoint` (уже Path A probe) | Реальная проверка UDP-пути AWG |

Практично в connmgr:

- `vpsReachable = awgHandshakeOk || tcpHintOk(bypass.peer)`  
  (AWG-успех ⇒ сразу Path A, TCP-hint только для классификации, когда AWG уже провалился.)

Не полагаться на ICMP (часто режут).

#### 2) Probe bigtech

Параллельно TCP `:443`, timeout ~4 с на хост (как qWDTT `isNetworkBlocked`):

```
bigtechOk = any(connect google|amazon|apple|microsoft :443)
```

В терминах qWDTT: `isNetworkBlocked ≈ !bigtechOk` (ни один bigtech не открылся).

#### 3) Probe yandex

```
yandexOk = connect yandex.ru:443  (timeout ~4 с)
```

На типичных БС `yandex.ru` открыт; при полном offline — нет.

---

### Классификация сети

| # | VPS¹ | bigtech | yandex | Класс | Действие |
|---|------|---------|--------|-------|----------|
| 1 | — | ✗ | ✗ | **NoNetwork** | Не поднимать RAW/AWG. UI: «Нет сети» |
| 2 | ✓ (AWG ok) | * | * | **DirectOk** | Path A, RAW не нужен |
| 3 | ✗ | ✗ | ✓ | **Whitelist** | Path B (RAW) — штатный failover |
| 4 | ✗ | ✓ | * | **OpenNoVps** | Открытый интернет, VPS/UDP недоступен. **Не авто-RAW** — диалог-предупреждение (как qWDTT) |
| 5 | ✗ | ✗ | ✗ уже в #1 | — | — |

¹ После неудачи Path A: «VPS ✓» здесь почти не бывает; строка 2 — успешный direct до failover.

Дополнительно: если AWG handshake failed, но `tcpHintOk(VPS)` и `bigtechOk` — скорее UDP/DPI режет AWG при живом хосте → всё равно класс **OpenNoVps** (предупреждение перед RAW) или отдельный подкласс **DpiSuspected** с тем же UX (предупреждение + «всё равно обход»).

---

### Поток `prefer=direct`

```
start()
  → try Path A (AWG handshake, 3–8 с)
       success → ConnectedDirect
       fail    → run NetworkProbe (VPS hint + bigtech + yandex)
                   │
                   ├─ NoNetwork          → abort, тост/экран «Нет сети»
                   ├─ Whitelist          → ConnectingBypass (RAW) без диалога
                   └─ OpenNoVps          → BypassBlockedWarning
                         │
                         ├─ «Подключить обход» / forceBypass → ConnectingBypass
                         ├─ «Отмена» → Idle
                         └─ «Больше не показывать» → hideBypassWarning=true,
                            дальше авто-RAW как Whitelist
```

Текст диалога (ориентир qWDTT):

> Не используйте обход, если белые списки не включены — это ухудшает устойчивость способа.  
> Кнопки: «Всё равно подключить обход» / «Отмена» + чекбокс «Больше не показывать».

---

### Поток `prefer=bypass` / force bypass

1. Быстрый gate: если `!yandexOk && !bigtechOk` → NoNetwork, не жечь TURN/капчу.
2. Иначе сразу ConnectingBypass (без bigtech-диалога — пользователь явно выбрал обход).

---

### Зачем такой порядок

| Шаг | Зачем раньше |
|-----|----------------|
| Сначала VPS/AWG | Не гонять bigtech, если direct уже поднялся |
| Потом bigtech | Отличить открытую сеть от БС (логика qWDTT) |
| Yandex последним (или параллельно с bigtech) | Если bigtech ✗ — отличить БС от offline |

Yandex и bigtech можно гонять **параллельно** после fail Path A; «порядок» логический: решение сначала смотрит VPS/AWG, затем пару (bigtech, yandex).

---

### Ложные срабатывания и смягчения

| Риск | Митигация |
|------|-----------|
| DNS ломает resolve bigtech | DoH/кэш / IP-fallback опционально позже; MVP — системный DNS |
| Корп. файрвол режет google, но не БС | Диалог + force; «не показывать» |
| VPS down при открытом интернете | RAW через TURN всё ещё может поднять туннель до того же IP — forceBypass допустим |
| IPv6-only / broken v4 | Probes dual-stack по возможности |
| Долгий probe | Parallel + общий budget ~4–5 с |

---

### Псевдокод

```kotlin
suspend fun choosePathAfterDirectFail(profile, forceBypass: Boolean): PathDecision {
  val vpsHint = tcpConnect(profile.bypass.peer, timeout = 2.5s) // optional
  val bigtech = anyTcp443(listOf("google.com","amazon.com","apple.com","microsoft.com"), 4s)
  val yandex  = tcp443("yandex.ru", 4s)

  if (!bigtech && !yandex) return PathDecision.NoNetwork
  if (!bigtech && yandex)  return PathDecision.UseBypass     // Whitelist
  // bigtech == true → открытая сеть
  if (forceBypass || settings.hideBypassWarning) return PathDecision.UseBypass
  return PathDecision.WarnOpenNetwork(vpsHint = vpsHint)
}
```

---

### Связь с VK-логином

Класс **Whitelist** → Path B. Если анонимный TURN нестабилен — UI предлагает «Войти через VK» (отдельная настройка, не часть probe).

Healthcheck после connect: при отвале Path A — снова probe перед авто-failover на RAW (тот же классификатор).
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
