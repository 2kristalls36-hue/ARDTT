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

**Рекомендация: два контейнера в одном Compose**, не монолит.

| Сервис (внутр. имя) | Роль | Порты (пример) |
|---------------------|------|----------------|
| `direct` | AmneziaWG 2.0 | UDP `51820` (или свой) |
| `bypass` | fork wdtt `-listen-raw` (WRAP+TURN peer) | UDP `56003` |
| `provision` (опц.) | один профиль `nvpn://` из обоих | только localhost/API |

Пользователю в UI деплоя: «Установить сервер» → один `docker compose up`, без слов AWG/qWDTT/WDTT. Внутри compose — два (или три) сервиса.

Подробнее: [Деплой: один контейнер vs два](#деплой-один-контейнер-vs-два).

### Лицензии (важно)

- `amneziawg-android` — обычно **Apache-2.0** → форк клиента ок при сохранении NOTICE/copyright.
- WDTT/qWDTT часто **GPL-3.0** → код bypass, слинкованный в приложение, тянет GPL на соответствующую часть (или на всё, если линковка жёсткая). Нужно: сохранить тексты лицензий, не смешивать «тихо», в README явно указать происхождение.

Практически: форк допустим и обычен; юридически — соблюсти лицензии исходников.

---

## Деплой: один контейнер vs два

### Вариант A — всё в одном контейнере

```
┌──────────── container: server ────────────┐
│  amneziawg-go  +  bypass-raw  +  scripts  │
│  awg0 + raw0 + общий iptables/nft         │
└───────────────────────────────────────────┘
```

Плюсы: один образ, один `docker run`, проще «нажал — работает».  
Минусы: общий PID/crash domain; обновление AWG 2.0 тянет пересборку bypass (и наоборот); два TUN + два NAT в одном init — хрупко; смешение лицензий/зависимостей в одном образе; сложнее логи и healthcheck «какой path жив».

### Вариант B — два контейнера, один Compose (рекомендуем)

```
docker-compose.yml
┌─ direct (AWG 2.0) ──┐   ┌─ bypass (RAW/WRAP) ─┐
│ network_mode: host  │   │ network_mode: host  │
│ /dev/net/tun        │   │ /dev/net/tun        │
│ awg0 + NAT          │   │ raw0 + NAT          │
└─────────────────────┘   └─────────────────────┘
         │ shared volume: /data (keys, passwords, issued profile)
         └─ optional provision: собирает nvpn:// из обоих
```

Плюсы:

- независимый restart/update (`direct` не падает при баге в bypass);
- разные образы и теги версий;
- проще healthcheck и лимиты CPU/RAM;
- NAT/MSS-правила изолированы по контейнеру (при `host` сети — по цепочкам/комментариям с префиксом);
- лицензии GPL (bypass) и остальное не склеены в один fat-image без нужды.

Минусы: чуть сложнее compose; оба обычно хотят `network_mode: host` + `NET_ADMIN` + `/dev/net/tun` (для VPN на VPS это норма).

### Вариант C — два контейнера в bridge + port-publish

Хуже для VPN: DNAT/hairpin, MTU, UDP performance. Имеет смысл только если host-network запрещён. **Не для MVP.**

### Почему не «голый» AWG installer + отдельно qWDTT вручную

Два независимых установщика = два пароля, два QR, пользователь сам склеивает профиль. Нам нужен **один** клиентский профиль с `direct` + `bypass`. Значит UX деплоя единый, а процессы внутри — разделённые.

### Именование (не светить qWDTT)

| Внутри репо / compose | Наружу (UI, docs для юзера) |
|------------------------|-----------------------------|
| сервис `direct` | «Прямое подключение» / просто часть сервера |
| сервис `bypass` | «Обход» / невидимый fallback |
| образ `…/direct`, `…/bypass` | «Установка сервера nonameVPN» |
| не писать WDTT/qWDTT в UI | ок в NOTICE/LICENSE для GPL |

### Практическая схема MVP

1. `docker compose up -d` на VPS (host network).
2. `direct` поднимает AWG 2.0, пишет peer keys в `/data`.
3. `bypass` поднимает `-listen-raw`, тот же password/device space из `/data`.
4. Скрипт/сервис `provision` один раз (или API из Android SSH-deploy, как у qWDTT) отдаёт **один** `nvpn://` с обоими блоками.
5. Firewall: открыть только UDP AWG + UDP raw-listen; TURN снаружи на VPS не слушает — клиент ходит на VK, VK уже на VPS.

### Итог

**Два контейнера в одном Compose + общий volume профилей.** Один контейнер — только если сознательно жертвуем изоляцией ради ультра-простого одного бинарного образа; для продукта с двумя path это хуже сопровождается.

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
| compose service `direct` | AmneziaWG 2.0 |
| compose service `bypass` | `-listen-raw` WRAP+TURN+RAW |
| `provision` / shared `/data` | один `nvpn://` профиль |

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
