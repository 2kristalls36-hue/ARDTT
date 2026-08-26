# AWDTT — архитектура

Легенда имени и смыслов: [LEGEND.md](LEGEND.md).  
**AWDTT** = Amnezia + WDTT (раньше черновое имя: nonameVPN).

Клиентский VPN с двумя путями до своего VPS:

1. **Прямой** — AmneziaWG 2.0.
2. **Обход** — TURN + RAW (`RAW IP → WRAP → TURN → VPS`), без DTLS и без вложенного WG/AWG.

Опционально на VPS: egress через **WARP**, если пользователь включил «Скрыть свой IP».

---

## Зафиксированные решения

| Тема | Решение |
|------|---------|
| Платформа | Android; форк `amneziawg-android` + bypass из WDTT |
| Path B | RAW: WRAP + TURN, **без DTLS** (осознанно: DTLS сильно мешает) |
| Деплой | Compose: `direct` + `bypass` + `dns` + `warp` + `provision`; `host_id` → IP в подсетях direct/bypass |
| WARP | Не третий клиентский path. Галочка **«Скрыть свой IP»** → egress этого пользователя через `warp0`. **DNS (:53) не через WARP** — `ip rule` prio 100 → `main`, остальной трафик prio 300+ → table `51820` |

| Call hash | **1 hash на пользователя VPN**, только на телефоне (не в серверном `nvpn` как обязательное поле) |
| Дозвон | **`vkcalls` по умолчанию** + **`legacy` (капча) как fallback** |
| VK-аккаунт | Только чтобы **создать** звонок/hash; Connect — анонимный `vkcalls` (или legacy) по hash |
| Мёртвый звонок | По умолчанию спросить; настройка «тихий режим» — recreate в фоне |
| TURN transport | **TCP** |
| Workers | **Default 3** на один hash (TCP); в настройках можно 1 («экономия») |
| Имя | **AWDTT** (Amnezia + WDTT); см. [LEGEND.md](LEGEND.md). Внутренние id/`nvpn` могут ещё встречаться |
| Формат | Свой профиль; без `wdtt://` |
| warp OOM | **Без авторестарта контейнера**; см. [WARP память](#warp-память-без-рестарта) |
| UI | **2 режима:** пользователь (по умолчанию, минимум) и **админ** (разблокировка в настройках → логи, деплой, расширенные опции) |
| Переподключение | Мягкий restart транспорта при смене Wi‑Fi/LTE (как WDTT-Plus): VpnService живёт, backend/`libclient` перезапускается после settle ~8 с |
| Wake rescue | После `SCREEN_ON` через ~60 с: если Path B без активных воркеров — soft restart |
| Watchdog | Path B: 0 воркеров ≥5 мин (экран вкл.) или мёртвый backend ≥60 с → soft restart |
| Trusted Wi‑Fi | Список SSID: на сети VPN пауза; при выходе — авто-подъём (нужна локация для SSID) |


---

## Высокоуровневая схема

```
Android
  probe (лёгкий) → preselect Direct|Bypass → Connect
  ├─ Path A: AWG 2.0 UDP ──────────────────────────► VPS direct (awg0)
  └─ Path B: TUN → WRAP → TURN/TCP → VPS bypass (raw0)
                                              │
              если «Скрыть IP» ───────────────┴─► policy route → warp0 → Cloudflare
```

---

## UI: пользователь и админ

Интерфейс Android — **два режима**. Ориентир по простоте: идея SmartVPN  
(`https://github.com/2kristalls36-hue/SmartVPN`; репозиторий сейчас недоступен агенту — детали экранов уточним при доступе).

### Режим пользователя (default)

Минимум. Цель: «профиль → Подключить».

| Есть | Нет (скрыто) |
|------|----------------|
| Статус сети / preselect (прямое / обход / нет сети) | Подробные логи TURN/AWG |
| Кнопка **Подключить / Отключить** | Деплой VPS / SSH |
| Галочка **Скрыть свой IP** | Workers, dial, MTU, AWG Jc/H/S… |
| Импорт профиля (QR / файл) | Сырые конфиги, debug |
| При необходимости: VK **создать звонок** (один раз) | Advanced DNS, bypass routes |
| Короткий статус ошибки понятным языком | Полный pipeline connection steps |

Вкладки: **Туннель** + компактные **Настройки** без админ-пунктов.

### Режим админа

Разблокировка в **Настройках** (не на главном экране):

1. «Для администратора» или длинный тап по версии.
2. PIN / пароль админа (hash в encrypted prefs).
3. «Выйти из режима админа» без сброса PIN.

Пока включён — доступно всё:

| Раздел | Содержимое |
|--------|------------|
| **Логи** | Подробные логи tunnel / bypass / probe; export |
| **Деплой** | Установка/обновление сервера (SSH), как Deploy в WDTT |
| **Серверы / provision** | Пользователи, host_id, выдача профилей |
| **Сеть / обход** | dial auto\|vkcalls\|legacy, workers, тихий recreate |
| **AWG** | Обфускация, endpoint, ключи |
| **WARP** | Учётка wgcf, статус egress |
| **Отладка** | Force path, сброс hash, verbose |

### Навигация

```
Пользователь:  [ Туннель ]  [ Настройки ]
Админ:         [ Туннель ]  [ Серверы ]  [ Деплой ]  [ Логи ]  [ Настройки+ ]
```

### Принципы

- После чистой установки — всегда **пользователь**.
- Админ-режим не светится тумблером на главном экране.
- Смена режима не рвёт активный туннель.
- Один формат профиля; отличается только глубина UI.

---

## WARP = скрытие IP (egress), не третий path

UI перед Connect: чекбокс **«Скрыть свой IP адрес»** (per-session или запомнить в профиле).

- Выкл: NAT с `awg0`/`wdttraw0` в интернет напрямую (IP VPS).
- Вкл: трафик **этого** `host_id` (с `10.8.0.{id}` или `10.9.0.{id}`) уходит в `warp0` (wgcf / kernel WG).

**DNS не через WARP.** Иначе резолв ломается (таймауты, «IP есть — сайты нет»), особенно на tun2socks/SOCKS и часто на WARP-пути.

```
prio 100: iif awg0|wdttraw0|wdtt0 udp/tcp dport 53 → lookup main   # DNS → WAN
prio 300+: from 10.8.0.x / 10.9.0.x → lookup 51820 → warp0         # остальное
+ MASQUERADE :53 на eth0 для 10.8/10.9/10.66
```

Клиентский DNS — **шлюз туннеля** (`10.8.0.1` Direct / `10.9.0.1` Bypass) → **dnsmasq** (`nvpn-dns`) → upstream `1.1.1.1`/`1.0.0.1` по `main`. Запросы на внешний `:53` (старые профили) по-прежнему уводятся `ip rule` prio 100 → main.

На клиенте **нет** отдельного `10.10.0.{id}` как path подключения.

```
provision: host_id → 10.8.0.id + 10.9.0.id
hideIp → policy from client → table 51820 → warp0 (кроме :53)
```

---

## Network probe (переработано)

### Чего не делаем

- Полный AWG handshake / подъём туннеля на каждый старт приложения.
- TCP connect на UDP-порт `-listen-raw` как доказательство «VPS жив».

### Что делаем при старте / смене сети (параллельно, ~3–4 с)

| Probe | Как | Зачем |
|-------|-----|--------|
| **System** | `ConnectivityManager` / validated network | Быстрый offline |
| **Yandex :443** | TCP | «Есть хоть какой-то интернет» на типичных БС |
| **Bigtech :443** | google/amazon/apple/microsoft параллельно | Открытая сеть vs ограничения (лейбл, не блокер) |
| **Captive** | `connectivitycheck.gstatic.com` / `generate_204` или аналог | Captive portal |
| **VPS UDP-lite** | Один UDP init-пакет на `direct.endpoint` (handshake initiation / CPS), ждать ответ ≤2 с **без** поднятия VpnService | Реальный LOS до AWG |

Опционально позже: крошечный **TCP health** на VPS от `provision` (`:9100/health`) — честный «хост жив», отдельно от UDP AWG.

### При нажатии Connect

Короткий **re-probe** (1–2 с): свежий VPS UDP-lite + yandex. Итог важнее устаревшего preselect со старта.

### Классификация (не блокирует легитимный обход)

| Класс | Условие | Preselect | UI |
|-------|---------|-----------|-----|
| **NoNetwork** | нет validated net и (!yandex && !bigtech) | — | Connect disabled |
| **Captive** | captive detected | — | «Войдите в сеть» |
| **DirectOk** | VPS UDP-lite ok | Path A | «Прямое» |
| **NeedBypass** | VPS UDP-lite fail, но yandex‖bigtech | Path B | «Обход» |
| **OpenNeedBypass** | NeedBypass и bigtech ok | Path B | Мягкий info, **не** blocking dialog |

**Убрали** hard-block «не используйте без БС». На открытой сети при недоступном VPS обход как раз нужен. Info-текст можно показать, Connect не запрещаем.

Инициализация клиента (профиль, `.so`, проверка VPN permission) — **параллельно** с probe. TURN Allocate — только после Connect на Path B.

---

## Нагрузка Path B (workers)

Слепой `workers=1` сильно режет скорость (WDTT RAW как раз от параллельных каналов).

**Зафиксировано (подтверждено):**

| Режим | Workers | Комментарий |
|-------|---------|-------------|
| Default | **3** | Компромисс скорость/стабильность на 1 hash, TCP |
| «Экономия» в настройках | 1 | Слабые сети / отладка |
| Потолок (позже) | 6–9 | Только после замеров |

Один **hash** на пользователя; несколько workers = несколько TURN allocations на тот же hash (как WDTT), не несколько звонков.

---

## VK / TURN

### Дозвон

- **`vkcalls` (Звонок)** — default, обычно без капчи.
- **`legacy` (Капча)** — fallback, если vkcalls не даёт креды / ошибка дозвона.
- Переключатель в UI: можно спрятать («Авто») = vkcalls → при ошибке legacy; или два пункта в расширенных настройках.

### Hash

- 1 hash на пользователя VPN.
- Хранится **на телефоне** (encrypted prefs / account storage).
- В серверный профиль hash **не обязателен**; `nvpn://` несёт AWG + bypass peer/password/`hostId`.

### Роль VK-аккаунта

```
Создать hash: логин VK → создать звонок → сохранить hash на телефоне
Connect Path B: anonymous vkcalls(hash) по TCP  [fallback: legacy]
Звонок мёртв: спросить | тихий recreate (настройка)
```

### Как в WDTT (#7 — сессия / тихий режим)

| Что | Поведение WDTT |
|-----|-----------------|
| Anonymous `vkcalls` | API: anonymous_token → call preview → anonym call token → `turn_server`; **без** долгого аккаунта |
| Account mode | WebView логин; `turn_server` с страницы звонка; Go получает `TURN_CREDS` через stdin; кэш кредов **~9 мин** в памяти |
| Cookies VK | Остаются в `CookieManager` WebView — повторный вход в звонок без полного логина, пока сессия жива |
| Hash | В профиле приложения (пользователь/генератор хешей) |
| Refresh | При ошибках Allocate — refresh creds; смена hash; цепочка captcha для legacy |

**Для нас (тихий recreate):** хранить hash + возможность открыть WebView/сессию VK при recreate; TURN creds кэшировать ≤9 мин как WDTT; не хранить пароль VK — только cookies/сессия WebView (как WDTT) в app-private storage. Тихий режим = auto WebView/API recreate без диалога (нужна ещё живая cookie-сессия; иначе всё равно показать логин).

### Зависимость от VK (#10)

**Как в WDTT:** форк API/WebView под текущий VK; при поломке — обновление приложения; Path всегда только через TURN VK; несколько hash/workers как смягчение; captcha/account как запасные ветки.

**Наше предложение:**

1. Path A (AWG) всегда независим — поломка VK ≠ полный даун продукта.
2. Авто: `vkcalls` → fail → `legacy`.
3. Изолировать `bypass` модуль (легче чинить VK без трогания AWG).
4. Версионировать «диалект» VK API в клиенте; remote flag (позже) для принудить legacy.
5. Честный UX: «Обход временно недоступен» если оба дозвона мертвы; Direct если VPS UDP ок.
6. Не обещать SLA обхода; один hash — осознанный риск (нет multi-hash failover); тихий recreate и legacy — основные подушки.

---

## Path B крипто (#8)

Оставляем **без DTLS**: WRAP AEAD (ключ из пароля). DTLS сознательно не возвращаем из‑за оверхеда. Риск: нет TLS-style forward secrecy на обходе — принять; пароль профиля = секрет; Path A остаётся сильным контуром.

---

## Лицензии GPL × Apache (#9)

WDTT ≈ **GPL-3.0**, amneziawg-android ≈ **Apache-2.0**.

**Сделать:**

1. В репо: `LICENSE` (решение для **всего APK** — практично **GPL-3.0**), `NOTICE` с атрибуцией Amnezia (Apache) и WDTT (GPL).
2. Не удалять copyright headers из форкнутых файлов.
3. README: откуда код, что продукт — комбинированное произведение под GPL-3.
4. Play/распространение: готовность отдать corresponding source (GPL).
5. Не линковать GPL в закрытый proprietary без соблюдения GPL.

Альтернатива позже (дорого): переписать TURN/WRAP без GPL-кода → снова можно Apache-only UI.

---

## WARP память без рестарта (#11)

Не используем `Restart=always` / OOM-kill цикл на `warp`.

Вместо этого:

- `GOMEMLIMIT` (~400–450 MiB) + мягкий GC внутри процесса;
- лимит одновременных SOCKS/потоков в tun2socks;
- периодический **soft recycle** соединений (не контейнера), если RSS растёт;
- метрики RSS в лог; алерт админу;
- `mem_limit` в Docker — только как cgroup ceiling **без** обязательного restart policy (или без limit, если предпочитаем деградацию скорости, а не kill).

Цель: процесс сам сбрасывает давление, клиенты не теряют egress из‑за рестарта контейнера.

---

## Dual VpnService (#15)

**Предложение:** один `TunnelService` / один `VpnService` binder; два **взаимоисключающих** backend:

- `AwgBackend` (Path A)
- `RawBackend` (Path B)

Смена path = stop backend → start other в том же service (короткий reconnect).  
Probe **никогда** не держит VpnService up.  
Не держать два TUN сразу.

---

## Деплой (кратко)

```
direct  awg0   10.8.0.0/24
bypass  raw0   10.9.0.0/24
warp    warp0  (egress; не клиентский path)
provision /data — host_id, keys, passwords
```

`network_mode: host`, `NET_ADMIN`, `/dev/net/tun`.  
Create-user: адреса в `10.8` и `10.9` с одним octet; флаг hide-IP — клиентский/сессионный, применяется policy на сервере (mark по IP клиента).

---

## Профиль (черновик)

```json
{
  "name": "home-vps",
  "deviceId": "...",
  "hostId": 5,
  "prefer": "direct",
  "hideIp": false,
  "direct": {
    "endpoint": "x.x.x.x:51820",
    "privateKey": "...",
    "peerPublicKey": "...",
    "address": "10.8.0.5/32",
    "awg": { }
  },
  "bypass": {
    "peer": "x.x.x.x:56003",
    "address": "10.9.0.5/32",
    "password": "...",
    "workers": 3,
    "transport": "tcp",
    "mode": "raw",
    "dial": "auto"
  }
}
```

Call hash — **локально на устройстве**, не обязан быть в шаринг-ссылке.  
`dial`: `auto` | `vkcalls` | `legacy`.

---

## MVP

1. Форк AmneziaWG Android + AWG direct.
2. Bypass RAW + TCP + dial auto (vkcalls→legacy).
3. Hash на телефоне; VK только create/recreate call.
4. Compose: direct + bypass + dns + warp (hide-IP egress) + provision.
5. Лёгкий parallel probe + re-probe на Connect; без hard-block OpenNoVps.
6. LICENSE/NOTICE (GPL-3 + атрибуции).
7. warp без restart-on-OOM; GOMEMLIMIT/soft recycle.

---

## Риски (актуальные)

| Риск | Митигация |
|------|-----------|
| VK API/TURN | Path A независим; vkcalls→legacy; изолированный bypass |
| 1 hash на юзера | Recreate; тихий режим; честный UX если обход мёртв |
| WRAP без DTLS | Пароль как секрет; сильный Path A |
| GPL | GPL-3 на APK + NOTICE |
| warp RAM | GOMEMLIMIT, без restart контейнера |
| Ложный VPS probe | Только UDP-lite / отдельный health TCP |
| Скорость RAW | workers default 3, не 1 |

---

## Следующий шаг

1. ~~Каркас репо + LICENSE/NOTICE.~~
2. ~~Server compose + provision (health, host_id, профиль, AWG keys).~~
3. ~~Упаковать **direct** (AmneziaWG 2.0 / amneziawg-go) и **bypass** (wdtt-server `-listen-raw`).~~
4. ~~Client scaffold: UI user/admin modes.~~
5. ~~Client: probe UDP-lite + path preselect + hide-IP → VpnService stub.~~ (`NetworkProbe`, `ConnectionManager`, `VpnTunnelService`).
6. ~~Импорт профиля JSON (формат provision) + demo; endpoints → probe.~~
7. ~~Bypass scaffold на Android: WRAP, hash-on-device, dial Auto, TunnelBackend wiring.~~
8. ~~Админ-деплой с приложения: SSH + upload Compose stack + install.sh.~~
9. ~~Native Direct (AWG GoBackend / libwg-go).~~ (`android/tunnel` → `DirectBackend`)
10. ~~Native Bypass (TURN TCP / vkcalls / RAW via go_client).~~ (`libclient.so` + `BypassSession`)
11. ~~Client: WebView create-call (VK login → calls.start → hash on device).~~
12. ~~Client: dial path UI (Авто / vkcalls / legacy) в Настройках + DataStore.~~
13. Legacy captcha WebView bridge (если нужно); WARP egress (не stub); UX vs SmartVPN.
