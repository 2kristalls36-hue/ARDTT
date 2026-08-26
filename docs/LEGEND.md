# Легенда ARDTT

## Имя

**ARDTT** читается по буквам («а-эр-ди-ти-ти»), расшифровка:

| Часть | Откуда | Что даёт продукту |
|-------|--------|-------------------|
| **A** | **Amnezia** / AmneziaWG | Прямой быстрый VPN (Path A): UDP, AmneziaWG 2.0 на телефоне и `amneziawg-go` на VPS |
| **R** | **RAW** | Обход несёт **сырые IP-пакеты**, а не второй WireGuard/AmneziaWG внутри TURN |
| **DTT** | **D**ial via **T**URN / **T**elephony-style | Дозвон и транспорт через инфраструктуру звонков (TURN), снаружи похоже на медиазвонок |

Итоговая формула:

> **ARDTT = Amnezia + RAW Dial via TURN**  
> один клиент, один ваш сервер, два независимых способа дойти до него.

### Почему не «WDTT» и не «AWDTT»

**Классический WDTT** ([amurcanov/proxy-turn-vk-android](https://github.com/amurcanov/proxy-turn-vk-android)) — это **WireGuard over TURN/DTLS**; буква **W** как раз про WireGuard.

Режим **RAW** (`-listen-raw`, сырые IP-пакеты без вложенного WG/AWG и без DTLS на этом listener) появился в линии **qWDTT / SpaceNeuroX**:

- https://github.com/SpaceNeuroX/proxy-turn-vk-android

Именно оттуда в ARDTT вендорятся `server/bypass/wdtt-server/` и идеи Path B клиента (`android/go_client/`).  
Поэтому продуктовое имя — **ARDTT** (R = RAW), а не AWDTT/WDTT: имя отражает фактический обход, а не классический WG-поверх-TURN.

Каталоги upstream в дереве могут по-прежнему называться `wdtt-*` — это вендорные/исторические имена кода, не название продукта.

Ранние черновики репозитория: `nonameVPN` → коротко `AWDTT` → **ARDTT**.  
Внутренние пути (`com.nonamevpn.app`, `/opt/nonamevpn/`) могут ещё встречаться как совместимость.

Знак приложения — стилизованная **A** Amnezia (белый фрагментированный глиф) на тёмно-синем круге с тонкой белой обводкой: буква «A» в имени и наследие прямого пути.

---

## Замысел одной фразой

Сделать **личный** VPN, который:

1. работает **быстро**, когда сеть до VPS нормальная (AmneziaWG);
2. **не отваливается**, когда UDP/IP до VPS режут, но ещё ходят «белые» сервисы и звонки (**RAW** через TURN);
3. остаётся **self-hosted** — ключи и egress на вашей машине.

---

## Два пути (легенда для пользователя)

### Path A — «Прямое» (Amnezia)

- VPN-туннель на базе **AmneziaWG 2.0**.
- Телефон ↔ VPS по UDP.
- Когда проходит — основной, самый быстрый режим.
- В UI: «прямое», уведомление `ARDTT · прямое`.

### Path B — «Обход» (RAW Dial via TURN)

- Если прямой UDP мёртв или нестабилен:
  - hash звонка → сессии через **TURN** (TCP);
  - внутри — **RAW** IP-пакеты с AEAD (WRAP), **без** второго WireGuard и без DTLS;
  - на VPS их принимает сервер обхода (`-listen-raw`) и отдаёт в интернет (или в WARP).
- В UI: «обход», уведомление `ARDTT · обход`.

### Автовыбор

Приложение само зондирует сеть и предлагает path.  
Можно зафиксировать «только прямое» или «только обход» в настройках.

---

## «Скрыть свой IP» (WARP) — не третий path

- Трафик по-прежнему приходит на VPS по Path A или Path B.
- На сервере для этого `host_id` — policy routing → **Cloudflare WARP**.
- DNS клиента — на шлюз туннеля / main, **не** через WARP.

---

## Роли в системе

| Роль | Имя в стеке | Смысл |
|------|-------------|--------|
| Выдача профилей | `provision` | `host_id`, ключи AWG, пароль обхода |
| Прямой вход | `direct` | AmneziaWG на VPS |
| Обходной вход | `bypass` | RAW после TURN |
| DNS шлюза | `dns` | dnsmasq на `10.8.0.1` / `10.9.0.1` |
| Маскировка выхода | `warp` | Опциональный egress |
| Клиент | Android **ARDTT** | Один VpnService, два бэкенда |

Адресация: один `host_id` → `10.8.0.{id}` (Amnezia) и `10.9.0.{id}` (RAW/обход).

---

## Почему не «просто Amnezia» и не «просто обход»

- **Только Amnezia** — бессильна, если UDP до вашего IP режут.
- **Только RAW/TURN** — медленнее и зависит от чужой TURN/API; не нужен, когда прямой путь открыт.
- **ARDTT** — оба контура в одном продукте.

---

## Символика интерфейса

- Лаунчер и главный экран: **ARDTT**.
- Иконка: круглая **A** на тёмно-синем — Amnezia-половина имени; RAW/TURN — запасной контур.
- Уведомления: `ARDTT · прямое` / `ARDTT · обход`.

---

## Происхождение кода

- Path A — линии [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-android) / amneziawg-go (в основном Apache-2.0).
- Path B (RAW) — линия **qWDTT / SpaceNeuroX** ([SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android), GPL-3.0): `-listen-raw`, WRAP/TURN без вложенного WireGuard.
- Классический [WDTT amurcanov](https://github.com/amurcanov/proxy-turn-vk-android) — идейный предок (WG over TURN/DTLS); **не** источник RAW-режима ARDTT.
- Общая упаковка, UX (в т.ч. вкладка «Обход»), provision, DNS, WARP-policy, soft-reconnect — обвязка этого репозитория.

Комбинированная работа — **GPL-3.0**: [LICENSE](../LICENSE), [NOTICE](../NOTICE).

Это **не** официальный продукт Amnezia VPN, VK или Cloudflare.

---

## Краткая памятка

```
ARDTT
├── A    → AmneziaWG          → быстро, напрямую
└── RDTT → RAW Dial via TURN  → обход, когда прямого нет
     └── опционально WARP на VPS → скрыть IP сервера
```

Подробные схемы: [ARCHITECTURE.md](ARCHITECTURE.md).
