# ARDTT — архитектура

Легенда имени и смыслов: [LEGEND.md](LEGEND.md).  
Деплой VPS (приложение / Compose, `install.sh`, версии): [DEPLOY.md](DEPLOY.md).  
**ARDTT** = Amnezia & Raw Dial over TURN Tunnel.  
Path B RAW — линия **qWDTT / SpaceNeuroX**, не classic WDTT (WG/TURN/DTLS); см. [LEGEND.md](LEGEND.md).

Клиентский VPN с двумя путями до своего VPS:

1. **Прямой** — AmneziaWG 2.0.
2. **Обход** — TURN + RAW (`RAW IP → WRAP → TURN → VPS`), без DTLS и без вложенного WG/AWG.

Опционально на VPS: egress через **WARP**, если пользователь включил «Скрыть свой IP».

---

## Зафиксированные решения

| Тема | Решение |
|------|---------|
| Платформа | Android; форк `amneziawg-android` + RAW bypass из qWDTT / SpaceNeuroX |
| Path B | RAW: WRAP + TURN, **без DTLS** (осознанно: DTLS сильно мешает) |
| Деплой | Compose: **один контейнер** (provision + direct + bypass + dns + warp + cascade + telemetry). Isolated netns + published ports. Продуктовый пакет — `ardtt-server-*-linux-<arch>.tar.gz` (docker save), **не** исходники и **не** APK. Из приложения: HTTPS GitHub → SSH + `install.sh` из архива — [DEPLOY.md](DEPLOY.md). Hostnet не используется |
| WARP | Не третий клиентский path. Галочка **«Скрыть свой IP»** → egress этого пользователя через `warp0`. **DNS (:53) не через WARP** — `ip rule` prio 100 → `main`, остальной трафик prio 300+ → table `51820` |

| Call hash | **1 hash на устройство** (не на имя профиля): EncryptedSharedPreferences + файл в `noBackupFilesDir`, чтобы код переживал обновление APK |
| Дозвон | **`vkcalls` по умолчанию** + **`legacy` (капча) как fallback** |
| VK-аккаунт | Только чтобы **создать** звонок/hash; Connect — анонимный `vkcalls` (или legacy) по hash |
| Мёртвый звонок | По умолчанию диалог на экране Туннеля; настройка «Обновлять звонок автоматически» — recreate в фоне (нужна живая `remixsid`, иначе диалог входа). Один silent-attempt за цикл |
| TURN transport | **TCP** |
| Workers | **Default 3** на один hash (TCP); в настройках можно 1 («экономия») |
| Имя | **ARDTT** (Amnezia & Raw Dial over TURN Tunnel); см. [LEGEND.md](LEGEND.md). Внутренние id/`ardtt`/каталоги `wdtt-*` — совместимость |
| Формат | Свой профиль; без `wdtt://` |
| warp OOM | **Без авторестарта контейнера**; см. [WARP память](#warp-память-без-рестарта) |
| UI | **2 режима:** пользователь (по умолчанию, минимум) и **админ** (разблокировка в настройках → логи, деплой, расширенные опции) |
| Переподключение | Мягкий restart при смене Wi‑Fi/LTE/SIM, быстрый IP-probe (1.1.1.1 / 77.88.8.8 / VPS TCP); settle зависит от пути: Direct 200 мс (`DIRECT_NETWORK_SETTLE_MS`), Bypass 3 с после VALIDATED (`BYPASS_NETWORK_SETTLE_MS`) или 400 мс без VALIDATED (`BYPASS_UNVALIDATED_SETTLE_MS`); живой обход уступает Wi‑Fi только после выдержки 6 с, удваивающейся после каждого неудачного эпизода до 60 с (`WIFI_UPGRADE_SETTLE_MS` / `WIFI_UPGRADE_SETTLE_MAX_MS`). Auto — Direct↔Bypass; дыра без сети — hold + очередь, не рестарт в пустоту. Dual-SIM: `default data` / active data sub |
| Wake rescue | После `SCREEN_ON` через ~25 с (`WAKE_RESCUE_GRACE_MS`): если Path B без активных воркеров — soft restart |
| Watchdog | Path B: 0 воркеров ≥8 с при включённом экране (`ZERO_WORKERS_GRACE_MS`) или мёртвый backend ≥20 с (`PROCESS_DEAD_GRACE_MS`) → soft restart; отдача растёт, а приёма нет 45 с (`BYPASS_UNANSWERED_UPLINK_MS`) → soft restart, отсчёт от старта транспорта |
| Trusted Wi‑Fi | Список SSID: на сети VPN пауза (debounce вход 2 с / выход 5 с, `TRUSTED_WIFI_ENTER_DELAY_MS` / `TRUSTED_WIFI_EXIT_DELAY_MS`); при выходе — авто-подъём (нужна локация для SSID) |


---

## Высокоуровневая схема

```
Android
  probe (лёгкий) → preselect Direct|Bypass → Connect
  ├─ Path A: AWG 2.0 UDP ──────────────────────────► VPS1 direct (awg0)
  └─ Path B: TUN → WRAP → TURN/TCP → VPS1 bypass (raw0)
                                              │
              без каскада + «Скрыть IP» ──────┴─► warp0 на VPS1
              с каскадом, Hide-IP вкл ──────────► VPS1 cascade0 ─AWG─► VPS2 warp0
              с каскадом, Hide-IP выкл ─────────► VPS1 cascade0 ─AWG─► VPS2 WAN
                                                                      └ DNS :53 → main
```

---

## Каскад (два VPS)

Не два одинаковых VPN для телефона. Телефон знает только **вход** (VPS1). Второй сервер — **выход**.

```
телефон ── Path A / Path B ──► VPS1 (provision, клиенты, awg0, wdttraw0)
                                  │
                                  │ AmneziaWG 2.0 (cascade0, 10.10.0.0/30)
                                  ▼
                               VPS2 (DNS, WAN MASQ) ──► интернет
```

| | VPS1 вход | VPS2 выход |
|---|---|---|
| Кто деплоит | телефон, SSH | телефон, SSH **через VPS1** (туннель; напрямую до VPS2 не ходит) |
| Клиенты | живут здесь (`:9100`) | нет |
| Телефон коннектится | да (UDP 51820 / 56003) | нет |
| DNS | нет (форвард на 10.10.0.2) | dnsmasq на `cascade0`, upstream с main |
| WARP | не для клиентов (passthrough) | hideIp /32 на warp0; иначе WAN |
| Если выход мёртв | hop без handshake → `awg0`/`wdttraw0` down, туннель на телефоне падает | — |

Профиль по-прежнему с endpoint VPS1. Снаружи при выключенном Hide-IP виден IP VPS2, при включённом — Cloudflare у **выхода**, не IP входа.

На входе hop (`10.8.0.0/24` / `10.9.0.0/24` → `cascade0`) живёт в `ip rule` **220/221**, отдельно от Hide-IP (prio 300+ → table `51820`). Heartbeat WARP по `users.json` не должен сносить hop — иначе Path B на секунды уходит в `eth0` без MASQ.

Деплой из приложения: карточка первого сервера + тумблер «Каскад» (SSH второго). «Установить» сначала ставит **exit** на втором, затем **entry** на первом, затем копирует ключ пира. Пароль второго VPS на первый **не** записывается. Тот же стек можно поставить клоном тега релиза на каждом VPS (`ARDTT_ROLE=entry|exit`) — [DEPLOY.md](DEPLOY.md).

---

## UI: пользователь и админ

Интерфейс Android — **два режима**. Ориентир по простоте — идея SmartVPN.

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

UI перед Connect: чекбокс **«Скрыть свой IP»** (per-session или запомнить в профиле).

- Выкл: NAT в интернет с IP сервера (standalone — этот VPS; каскад — WAN выхода).
- Вкл: трафик **этого** `host_id` (с `10.8.0.{id}` или `10.9.0.{id}`) уходит в `warp0` через **wireproxy + tun2socks** (userspace WG → SOCKS → TUN). Переключение Hide-IP — только `ip rule` + flush conntrack, без рестарта клиентского TUN.
  На каскаде `/32` ставит **выход** (опрос `GET /v1/hide-ip-prefixes` у входа через `10.10.0.1`). Вход в режиме passthrough не перехватывает клиентов в свой WARP.

**DNS не через WARP.** Иначе резолв ломается (таймауты, «IP есть — сайты нет»), особенно на tun2socks/SOCKS и часто на WARP-пути.

```
prio 100: iif awg0|wdttraw0 udp/tcp dport 53 → lookup main   # DNS → WAN
prio 300+: from 10.8.0.x / 10.9.0.x → lookup 51820 → warp0         # остальное
+ MASQUERADE :53 на eth0 для 10.8/10.9
```

Клиентский DNS — **шлюз туннеля** (`10.8.0.1` Direct / `10.9.0.1` Bypass) → **dnsmasq** (`ardtt-dns`) → upstream `1.1.1.1`/`1.0.0.1` по `main`. Запросы на внешний `:53` (старые профили) по-прежнему уводятся `ip rule` prio 100 → main.

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

### Что делаем при старте / смене сети (параллельно, ~1–2 с)

Те же проверки. На **смене сети при активном туннеле** сокеты биндятся к underlay (`NOT_VPN`), чтобы не классифицировать мир через уже поднятый Direct/Bypass. В режиме Auto на **Wi‑Fi** путь всегда Direct (зонд VPS не выбирает обход). На **мобильной сети** при смене класса — переключение Direct↔Bypass; иначе soft-restart того же path. **Нет сети** (дыра WIFI↔LTE↔LTE / смена SIM) — hold, без рестарта в пустоту; следующий validated underlay / смена data SIM ставит probe в очередь, если предыдущий ещё идёт.

Таймауты handover/`quick`: TCP **450 мс**, captive **400 мс**, VPS TCP **600 мс**. Старт приложения: 700 / 600 / 900 мс. Пробы **по IP, без DNS**: `77.88.8.8` и `1.1.1.1` (порт 443 и 53 гонка). VPS — TCP на host:port provision (не HTTP GET). Это **не** AmneziaWG UDP :51820: на белом списке `:9100` часто отвечает, а UDP дропается — Auto на **мобильной сети** тогда берёт **обход**, а не мёртвый Direct. На Wi‑Fi Auto обход по зонду не берётся. Captive (`generate_204`) только когда оба IP мертвы — иначе Google на БС даёт ложный captive. Settle после смены сети **~400 мс**. Connect re-probe использует `quick` (на Wi‑Fi Auto Connect пропускает зонд). Dead-Direct watchdog: нет TUN rx ~4 с → Auto на мобильной сети переключается на обход; на Wi‑Fi сессия останавливается.

| Probe | Как | Зачем |
|-------|-----|--------|
| **System** | `ConnectivityManager` / validated network | Быстрый offline |
| **77.88.8.8** | TCP :443 \|\| :53 | «Есть интернет» на типичных БС (Yandex DNS) |
| **1.1.1.1** | TCP :443 \|\| :53 | Открытая сеть vs БС (Cloudflare режется на белом списке) |
| **vk.com** | TCP :443 по IP (гонка фронтов AS47541, без TLS и без DNS) | Второй российский контроль: на БС он жив, а мёртвый vk.com = плохой линк, а не белый список (и обход через звонок VK всё равно не поднимется) |
| **Captive** | `generate_204`, только если оба IP мертвы | Captive portal |
| **VPS TCP** | Connect на provision host:port | Открытая сеть → Direct; на БС (Yandex↑ Cloudflare↓) → обход, даже если :9100 жив |

Опционально позже: UDP-lite на `direct.endpoint` (handshake AWG) — отдельно от TCP provision.

### При нажатии Connect

Короткий **re-probe** (`quick`, доли секунды): VPS TCP + 77.88.8.8 / 1.1.1.1. Итог важнее устаревшего preselect со старта. Режим Auto на Wi‑Fi Connect идёт сразу в Direct, без этого зонда.

### Классификация (не блокирует легитимный обход)

| Класс | Условие | Preselect | UI |
|-------|---------|-----------|-----|
| **NoNetwork** | нет VPS и (!77.88.8.8 && !1.1.1.1) | — | Connect disabled; handover — hold |
| **Captive** | оба IP мертвы и generate_204 ≠ 204 | — | «Войдите в сеть» |
| **DirectOk** | VPS TCP ok и не белый список | Path A | «Прямое» |
| **NeedBypass** | 77.88.8.8 ok, 1.1.1.1 fail (VPS TCP не важен) | Path B | «Обход» (белый список) |
| **OpenNeedBypass** | VPS fail, 1.1.1.1 ok | Path B | Мягкий info, **не** blocking dialog |

**Убрали** hard-block «не используйте без БС». На открытой сети при недоступном VPS обход как раз нужен. Info-текст можно показать, Connect не запрещаем.

Инициализация клиента (профиль, `.so`, проверка VPN permission) — **параллельно** с probe. TURN Allocate — только после Connect на Path B.

---

## Режим Авто: белый список и смена сети

- Детект белого списка (БС) — только мобильные сети (`WhitelistDetection`); Wi‑Fi/Ethernet — заглушка на Direct, зонд БС на них не запускается.
- Оценка БС привязана к оператору (MCC+MNC в `NetworkKey.carrier`); смена PLMN обнуляет оценку и негативное свидетельство по Direct, но не пересобирает транспорт.
- Полный положительный сэмпл (+80, порог входа за один раунд) требует **обоих** контролей: Yandex DNS **и** vk.com. vk.com не ответил — раунд `Ignore` (перегруженный линк или авария VK); вердикта по vk.com нет (бюджет кончился) — `WeakPositive` (+25), одного раунда на обход не хватит.
- Обычные цели (Cloudflare / Google) после ответа контроля ждут `min(остаток бюджета, max(базовый таймаут, 4 × RTT контроля))` и один раз перезапрашиваются при таймауте, если осталось ≥ 300 мс: потеря пакета на перегрузе вероятностна, блокировка на БС детерминирована. Вердикт Direct публикуется раньше и этим ожиданием не задерживается.
- Повторная проверка Direct с обхода: 30 с → 60 с → 2 мин → 5 мин → 10 мин (`RecoverySettings.directReevalBackoffMs`), сброс при подтверждённом Direct или новой сети.
- Переход на неизмеренную соту: быстрый зонд (≤ 1,5 с, `FAST_PROBE_BUDGET_MS`) вместо слепого Direct; результат складывается в свидетельства.
- Предзондирование сотовой при активном Wi‑Fi: запрос сети освобождается между раундами.
- Голосовой звонок / приостановка данных: пауза сетевых операций, после возобновления ≥ 5 с (`DATA_SUSPENSION_REBIND_MS`) — форсированный хендовер; только для радио, на котором едет туннель.
- Таймеры восстановления держат CPU ограниченно (`RecoveryWakeLock`, потолок 120 с); слишком длинные таймеры CPU не держат.

---

## Нагрузка Path B (workers)

Слепой `workers=1` сильно режет скорость (RAW Path B как раз от параллельных каналов).

**Зафиксировано (подтверждено):**

| Режим | Workers | Комментарий |
|-------|---------|-------------|
| Default | **3** | Компромисс скорость/стабильность на 1 hash, TCP (клиент `BypassWorkers.DEFAULT`, Lab, provision) |
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
- В серверный профиль hash **не обязателен**; `ardtt://` несёт AWG + bypass peer/password/`hostId`.

### Роль VK-аккаунта

```
Создать hash: логин VK → создать звонок → сохранить hash на телефоне
Connect Path B: anonymous vkcalls(hash) по TCP  [fallback: legacy]
Звонок мёртв: спросить (диалог) | тихий recreate (настройка) | вход VK если нет remixsid
```

### Как в WDTT (#7 — сессия / тихий режим)

| Что | Поведение WDTT |
|-----|-----------------|
| Anonymous `vkcalls` | API: anonymous_token → call preview → anonym call token → `turn_server`; **без** долгого аккаунта |
| Account mode | WebView логин; `turn_server` с страницы звонка; Go получает `TURN_CREDS` через stdin; кэш кредов **~9 мин** в памяти |
| Cookies VK | Остаются в `CookieManager` WebView — повторный вход в звонок без полного логина, пока сессия жива |
| Hash | В профиле приложения (пользователь/генератор хешей) |
| Refresh | При ошибках Allocate — refresh creds; смена hash; цепочка captcha для legacy |

**Для нас (тихий recreate, реализовано):** hash на устройстве; Connect остаётся anonymous `vkcalls`. Если `libclient` / логи дают мёртвый звонок (`CALL_UNAVAILABLE`, `VK call is unavailable`, `error_code=951/954`, «Звонок не найден»):

- настройка выкл. → диалог «Создать новый»;
- настройка вкл. и есть cookie `remixsid` → создать звонок через `VkCallHashGenerator` и перезапустить Bypass, не роняя VpnService;
- нет сессии → диалог «Войти и создать»;
- повторный мёртвый hash в том же цикле → стоп, «создайте код вручную» (без петли).

TURN creds по-прежнему кэширует `go_client` (как WDTT, ≤9 мин). Пароль VK не храним. Legacy captcha WebView — отдельно, не часть этого контура.

### Зависимость от VK (#10)

**Как в WDTT:** форк API/WebView под текущий VK; при поломке — обновление приложения; Path всегда только через TURN VK; несколько hash/workers как смягчение; captcha/account как запасные ветки.

**Как устроено:**

1. Path A (AWG) независим — поломка VK ≠ полный даун продукта.
2. Авто: `vkcalls` → fail → `legacy`.
3. Модуль `bypass` изолирован (чинить VK без трогания AWG).
4. «Диалект» VK API живёт в клиенте; remote flag, чтобы принудить legacy, пока нет.
5. UX: «Обход временно недоступен», если оба дозвона мертвы; Direct, если VPS UDP ок.
6. SLA обхода не обещаем; один hash — осознанный риск (нет multi-hash failover); тихий recreate и legacy — основные подушки.

---

## Path B крипто (#8)

Оставляем **без DTLS**: WRAP AEAD (ключ из пароля). DTLS сознательно не возвращаем из‑за оверхеда. Риск: нет TLS-style forward secrecy на обходе — принять; пароль профиля = секрет; Path A остаётся сильным контуром.

---

## Лицензии GPL × Apache (#9)

- amneziawg-android / AmneziaWG ≈ **Apache-2.0**
- Path B RAW (qWDTT / SpaceNeuroX) ≈ **GPL-3.0** — см. [NOTICE](../NOTICE), [LEGEND.md](LEGEND.md)
- Классический WDTT (amurcanov) — идейный предок (WG/TURN/DTLS), не источник RAW

Как лицензировано:

1. В репо: `LICENSE` на **весь APK** — **GPL-3.0**, `NOTICE` с атрибуцией Amnezia (Apache) и SpaceNeuroX/qWDTT RAW (GPL).
2. Copyright headers из форкнутых файлов не удаляются.
3. README описывает комбинированное произведение под GPL-3.
4. Play/распространение: corresponding source отдаётся по GPL.
5. GPL-код нельзя линковать в закрытый proprietary без соблюдения GPL.

---

## WARP память без рестарта (#11)

Не используем `Restart=always` / OOM-kill цикл на контейнере `warp`.

Боевой контур (проверен на рабочем VPS ~2 ГБ RAM, 14+ дней без OOM):

- **wireproxy** 1.1.2 (userspace WG, SOCKS5 на localhost) — RSS ~110 MiB, `GOMEMLIMIT` 256 MiB;
- **tun2socks** 2.5.2 (`warp0` TUN) — RSS ~20 MiB;
- Hide-IP = `ip rule` from `/32` → table `51820` + conntrack flush (как ранние релизы: на лету);
- in-process recycle wireproxy, если RSS > 350 MiB — без docker restart.

Kernel `wg-quick` на `warp0` не используем: он раздувал память и требовал soft-restart TUN на телефоне.

---

## Dual VpnService (#15)

Один `VpnTunnelService` (`VpnService`); два **взаимоисключающих** backend:

- `DirectBackend` — Path A, AmneziaWG userspace (`libwg-go`)
- `BypassBackend` — Path B, RAW `go_client` (`libclient.so`)

Смена path = stop backend → start other в том же service (короткий reconnect).  
Probe **никогда** не держит VpnService up.  
Два TUN сразу не поднимаются.

---

## Деплой (кратко)

Полная механика: [DEPLOY.md](DEPLOY.md).

```
direct  awg0   10.8.0.0/24
bypass  raw0   10.9.0.0/24
warp    warp0  (egress; не клиентский path)
provision /data — host_id, keys, passwords
```

`NET_ADMIN`, `/dev/net/tun`, по умолчанию **своя netns** (не host).  
Create-user: адреса в `10.8` и `10.9` с одним octet; флаг hide-IP — клиентский/сессионный, применяется policy на сервере (mark по IP клиента).

Боевой путь: админ в приложении → GitHub (`server/`) → SSH → каталог установки ARDTT + `install.sh` + Compose.  
На диске путь по умолчанию `/opt/ardtt` оставлен для совместимости с уже развёрнутыми VPS (`ARDTT_INSTALL_DIR`).  
Версия стека (`DEPLOY_VERSION`) сравнивается с APK через `GET /health`.

---

## Профиль

Боевой JSON: клиент `VpnProfile` / `VpnProfileJson`, сервер — `provision`. Один профиль — два endpoint'а и общий `host_id`.

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

## Риски (актуальные)

| Риск | Митигация |
|------|-----------|
| VK API/TURN | Path A независим; vkcalls→legacy; изолированный bypass |
| 1 hash на юзера | Recreate; тихий режим; честный UX если обход мёртв |
| WRAP без DTLS | Пароль как секрет; сильный Path A |
| GPL | GPL-3 на APK + NOTICE |
| warp RAM | GOMEMLIMIT, без restart контейнера |
| Ложный VPS probe | TCP :9100 и TCP 1.1.1.1 ≠ открытый интернет; Cloudflare = TLS/UDP; БС (Yandex↑ CF TLS↓) → обход |
| Скорость RAW | workers default 3, не 1 |
