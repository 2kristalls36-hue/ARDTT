# nonameVPN — архитектура

Клиентский VPN с двумя путями до своего VPS:

1. **Прямой** — AmneziaWG 2.0 (основной режим).
2. **Обход** — при отсутствии прямой видимости до VPS трафик идёт через TURN-релей (принцип WDTT / proxy-turn-vk-android), в режиме **RAW** из qWDTT (IP-пакеты без вложенного WireGuard).

---

## Зачем так

| Режим | Когда | Плюсы | Минусы |
|-------|--------|-------|--------|
| **AWG 2.0 direct** | UDP до VPS проходит (DPI «мягкий» / IP не в ЧС) | Низкая задержка, полный AWG 2.0 (CPS, H/S ranges, junk) | Падает, если IP/порт VPS режут |
| **TURN + RAW** | Нет LOS до VPS, whitelist-only сети | Маскировка под WebRTC-звонок, выше скорость, чем WG-over-TURN | Зависимость от TURN-кредов, выше RTT, шифрование на слое WRAP/DTLS |

Классический WDTT (WG поверх TURN) оставляем как опциональный третий путь или не берём в MVP: двойное шифрование режет скорость; qWDTT Raw как раз ушёл от этого.

---

## Высокоуровневая схема

```
┌──────────────────────────── Клиент ────────────────────────────┐
│  UI / CLI                                                       │
│       │                                                         │
│  Connection Manager  ── probe / health / failover               │
│       │                                                         │
│  ┌────┴────┐                                                    │
│  │ Path A  │  AmneziaWG 2.0  ──────── UDP ────────►  VPS:AWG    │
│  │ Path B  │  RAW TUN ◄─► TURN client                           │
│  └────┬────┘         │                                          │
│       │              ▼                                          │
│       │     WRAP(RTP)+DTLS → VK TURN relay → VPS:bypass-server  │
└───────┴─────────────────────────────────────────────────────────┘
```

**Path A (direct):**

```
Apps → TUN (awg0) → amneziawg-go / kernel AWG 2.0 → UDP → VPS awg
```

**Path B (bypass / RAW):**

```
Apps → TUN (raw0) → IP frames → WRAP/RTP AEAD → DTLS → TURN →
  → VPS bypass-server (порт raw, напр. 56003) → NAT → интернет
```

На VPS оба пути могут жить рядом: `awg` слушает публичный UDP; `bypass-server` принимает DTLS/WRAP и в RAW-режиме поднимает TUN + NAT без внутреннего WG.

---

## Connection Manager

Один оркестратор решает, какой path активен.

### Политика выбора (MVP)

1. Если в профиле задан `prefer=direct` (по умолчанию):
   - короткий probe: AWG handshake / ICMP внутри туннеля / внешний check через туннель;
   - timeout ~3–8 с;
   - успех → Path A;
   - провал → Path B (TURN+RAW).
2. Если `prefer=bypass` или `force=bypass` — сразу Path B (полезно в whitelist-сетях).
3. Фоновый healthcheck: при отвале Path A попробовать снова; при отвале Path B — reconnect TURN / сменить hash / transport UDP↔TCP.
4. Не держать оба TUN одновременно (один системный VPN-интерфейс).

### Состояния

```
Idle → ProbingDirect → ConnectedDirect
                   └→ ConnectingBypass → ConnectedBypass
Connected* → Reconnecting → …
Любое → Disconnecting → Idle
```

---

## Компоненты

### Клиент

| Модуль | Роль | Ориентир |
|--------|------|----------|
| `awg-engine` | AWG 2.0 userspace (или kernel + userspace config) | [amneziawg-go](https://github.com/amnezia-vpn/amnezia-wg) 2.0 |
| `bypass-transport` | VK auth → TURN creds → DTLS workers → WRAP/RTP | proxy-turn-vk-android / qWDTT client |
| `raw-tun` | TUN, адресация, маршруты, MSS clamp | qWDTT `--mode raw` |
| `connmgr` | probe, failover, reconnect | новый |
| `profile` | импорт конфигов, секреты, device-id | свой формат + совместимость |
| `ui` / `cli` | платформенный фронт | Android / Linux CLI (фаза 1 — решить) |

### Сервер (VPS)

| Модуль | Роль |
|--------|------|
| `amneziawg` | AWG 2.0 интерфейс, ключи, Jc/H/S/I параметры |
| `bypass-server` | DTLS+WRAP терминация; RAW TUN + NAT; auth по password/device-id |
| `provision` | выдача клиентских профилей (AWG conf + bypass link), опционально бот/API |

Деплой: один install-скрипт / compose, который поднимает оба сервиса и согласованные порты/firewall.

---

## Профиль подключения

Единый профиль несёт данные для обоих path:

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
    "peer": "x.x.x.x:56000",
    "rawPort": 56003,
    "password": "...",
    "hashes": ["vk_call_hash_1", "vk_call_hash_2"],
    "workers": 9,
    "transport": "tcp",
    "mode": "raw"
  }
}
```

Ссылки: свой URI (например `nvpn://...`) + опциональный импорт `wdtt://` / `.conf` AWG для миграции.

---

## Слои безопасности (Path B)

Как в WDTT/qWDTT, снаружи внутрь:

1. **RTP framing** — похоже на медиа WebRTC (OPUS-like PT).
2. **WRAP AEAD** (ChaCha20-Poly1305) — ключ из пароля профиля, не захардкожен.
3. **DTLS** до TURN relay → allocate → connect к VPS.
4. **RAW IP** внутри — без второго WG; целостность/конфиденциальность на WRAP+DTLS.

Для Path A криптография и обфускация — целиком AWG 2.0 (Noise IK + транспортный камуфляж).

---

## Платформы (предложение)

**Фаза 1 — Linux CLI (Go)**  
Быстрее прототипировать `connmgr` + AWG + RAW bypass; опираться на qWDTT-linux и amneziawg-go.

**Фаза 2 — Android**  
`VpnService` + один native Go core (gomobile / JNI), UI на Kotlin. Обход с VK captcha/WebView — как в qWDTT.

**Позже:** Windows/macOS по необходимости.

Ядро транспорта лучше держать **общим на Go**, UI — тонкой оболочкой.

---

## Отличия от существующих проектов

| | Amnezia | qWDTT / WDTT | **nonameVPN** |
|--|---------|--------------|---------------|
| Прямой AWG 2.0 | да | нет | да |
| Failover на TURN | нет (другие обходы в Amnezia) | всегда через TURN | да, автоматический |
| RAW без WG | нет | да (qWDTT) | да, в обходе |
| Один профиль на оба path | — | — | да |

---

## MVP scope

1. Сервер: AWG 2.0 + bypass-server RAW.
2. Linux CLI: профиль, Path A, Path B, auto-failover.
3. Healthcheck и логи состояния.
4. Документированный формат профиля / share-ссылка.

**Не в MVP:** SOCKS-only режим, multi-hop, свой TURN (только публичные VK TURN), полный паритет UI Amnezia.

---

## Риски и решения

| Риск | Митигация |
|------|-----------|
| VK TURN/API меняется | Изолировать `bypass-transport` за интерфейсом; несколько hash; кэш кредов ~9 мин |
| Captcha ломает UX | Цепочка auto-solver → WebView (как qWDTT); на Linux — headless/ограниченный fallback |
| MTU / фрагментация на RAW | MSS clamp (nftables), MTU ≤ 1280 |
| UDP до TURN режут | `transport=tcp` по умолчанию в bypass |
| AWG 2.0 не совместим с 1.x | Только 2.0 параметры в профиле; сервер и клиент одной ветки |
| Юридическая/ToS зависимость от VK TURN | Проект — self-hosted VPS + research transport; документировать ограничения |

---

## Открытые решения (нужно зафиксировать)

1. **Платформа фазы 1:** Linux CLI vs сразу Android?
2. **Сервер bypass:** форк/адаптация `wdtt-server` RAW или свой минимальный бинарь?
3. **Шифрование в bypass:** только WRAP+DTLS (как qWDTT Raw) или всё же AWG-over-TURN для единого криптостека?
4. **Имя продукта / URI scheme** вместо `nonameVPN`.
5. **Совместимость:** читать чужие `wdtt://` и AWG `.conf` или только свой формат?

Рекомендация по умолчанию: **Linux CLI → адаптация wdtt-server RAW → bypass без внутреннего AWG → свой профиль + импорт AWG conf**.

---

## Следующий шаг после согласования

1. Каркас репозитория (`cmd/`, `internal/connmgr`, `internal/awg`, `internal/bypass`).
2. Минимальный Path A на amneziawg-go.
3. Path B: перенос/адаптация RAW-потока из qWDTT.
4. `connmgr` probe + failover.
5. Compose/install для VPS.
