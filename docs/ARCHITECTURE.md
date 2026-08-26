# nonameVPN — архитектура

Клиентский VPN с двумя путями до своего VPS:

1. **Прямой** — AmneziaWG 2.0 (основной режим).
2. **Обход** — при отсутствии прямой видимости до VPS трафик идёт через TURN-релей (принцип WDTT / qWDTT), в режиме **RAW**: `RAW IP → WRAP(RTP AEAD) → TURN → VPS`. **Без DTLS и без WireGuard.** WDTT для пользователя невидим.

---

## Зафиксированные решения

| # | Вопрос | Решение |
|---|--------|---------|
| 1 | Платформа | **Цель — Android.** База: форк `amneziawg-android` + встройка bypass-ядра. Go-транспорт можно отлаживать отдельно, но продукт — APK. |
| 2 | Кодовая база | **Да: форк Amnezia (клиент AWG) + форк/адаптация WDTT/qWDTT (сервер + bypass-клиент).** |
| 3 | Оптимизация Path B | **RAW как в qWDTT: WRAP + TURN, без DTLS и без AWG/WG.** DTLS в classic WDTT — лишний оверхед (self-signed + второй AEAD). |
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

Два процесса (или один compose):

1. **AmneziaWG 2.0** — прямой UDP (из amneziawg installer / своих пакетов).
2. **bypass-server** — форк `wdtt-server` с **`-listen-raw`**: WRAP поверх UDP, TUN+NAT, **без DTLS и без WG**.

Один install/deploy выдаёт клиенту **свой** профиль сразу с обоими path.

### Лицензии (важно)

- `amneziawg-android` — обычно **Apache-2.0** → форк клиента ок при сохранении NOTICE/copyright.
- WDTT/qWDTT часто **GPL-3.0** → код bypass, слинкованный в приложение, тянет GPL на соответствующую часть (или на всё, если линковка жёсткая). Нужно: сохранить тексты лицензий, не смешивать «тихо», в README явно указать происхождение.

Практически: форк допустим и обычен; юридически — соблюсти лицензии исходников.

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
  "prefer": "direct",
  "direct": {
    "endpoint": "x.x.x.x:51820",
    "privateKey": "...",
    "peerPublicKey": "...",
    "presharedKey": "...",
    "address": "10.8.0.2/32",
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
    "password": "...",
    "hashes": ["..."],
    "workers": 9,
    "transport": "tcp",
    "mode": "raw"
  }
}
```

`peer` — адрес VPS для `-listen-raw` (после TURN CreatePermission/ChannelBind). Отдельный DTLS-порт не нужен.

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
| amneziawg 2.0 | прямой path |
| bypass-server (`-listen-raw`) | обход WRAP+TURN+RAW |
| provision | один профиль на оба path |

---

## MVP

1. Форк amneziawg-android: свой package id, AWG 2.0 direct как сейчас.
2. Встройка bypass RAW + авто-failover.
3. VPS: AWG 2.0 + bypass-server RAW, один deploy → свой профиль.
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

1. Каркас: форк amneziawg-android в монорепо (или submodule) + каталог `server/` под bypass.
2. Поднять VPS-часть: AWG 2.0 + wdtt RAW.
3. Встроить bypass `.so` и `connmgr` failover.
4. Свой профиль + экран опционального VK-логина.
