# Диагностика: самопроизвольный обрыв туннеля

Разбор по трём слоям: клиент Android (`android/app`), Go-обход (`android/go_client` и `server/bypass/wdtt-server`) и остальной серверный стек (`server/`). Исходники в этом документе не меняются. Номера строк сверены с деревом на ветке диагностики; исходник pion в репозиторий не вендорится, его внутренние строки сюда не переносятся.

Связанные документы: [ARCHITECTURE.md](ARCHITECTURE.md), [TELEMETRY.md](TELEMETRY.md).

---

## 1. Симптом и область разбора

«Туннель оборвался» в логах — три разных класса. Чинить их одним патчем нельзя.

| Класс | Что видит пользователь | Что произошло |
|-------|------------------------|---------------|
| Интерфейс пропал | VPN в системе выключен, статус «отключено» / «VPN-сервис остановился» | Процесс или `VpnTunnelService` умер, сессия не восстановлена |
| Сервис жив, транспорта нет | Плашка «подключено» или «переподключение», пакеты не идут | TUN закрыт или backend остановлен, `tunnelSessionActive` ещё true |
| «Подключено, но данные не идут» | Интерфейс на месте, счётчик ↑ может расти, ↓ стоит | Чёрная дыра: живый воркер или AWG при мёртвой сессии на сервере / hop |

Ниже «обрыв» — любой из трёх, если он случился без нажатия «Отключить» и без отзыва разрешения VPN.

---

## 2. Краткий вывод

В большинстве спонтанных обрывов сеть ни при чём. Клиент сам гасит рабочий туннель.

На прямом пути сторож Direct считает handshake старше 180 с смертью, даже если за 15 с не было ни полезного TX, ни пробного пакета. Экран и wake-grace этот путь не закрывают. Смена подложки (Wi‑Fi ↔ LTE, SIM) почти всегда делает `backend.stop()` + `forgetTun()` + новый TUN; флаг «путь здоров» в решении не читается, а `rebindLiveDirectUnderlay` на HANDOVER не вызывается.

На обходе сервер закрывает raw-сессию после 90 с тишины, а клиентский reader таймаут 30 мин игнорирует. Воркер остаётся READY, `channels > 0`, ↑ растёт (байты считаются до очереди), ↓ нет. Через 45 с Kotlin-сторож делает soft restart. Если падают все воркеры сразу, пауза 5–16 с пересекается с grace «ноль воркеров» 8 с — сторож рестартит процесс, который уже собирался переподключиться.

На сервере два жёстких механизма: каскадный вход каждые 5 с роняет `awg0`/`wdttraw0`, если handshake hop старше 180 с, и супервизор контейнера: умер любой ребёнок — `exit 1` и рестарт всего `ardtt` вместе со всеми сессиями.

`FOREGROUND_SERVICE_DEFERRED` приоритет процесса не снижает и причиной обрыва не является.

---

## 3. Сводная таблица

Уверенность: **высокая** — условие и вызов есть в коде этого дерева; **средняя** — механизм в коде есть, срабатывание зависит от тайминга или внешнего модуля; **низкая** — усилитель, сам по себе интерфейс не снимает.

| # | Слой | Причина | Симптом | Уверенность | Код |
|---|------|---------|---------|-------------|-----|
| 1 | Android Direct | Idle-туннель с handshake > 180 с (или `handshake_sec <= 0`) рвётся без пробного пакета. Экран и wake-grace не участвуют | Короткий обрыв и новый TUN на Wi‑Fi / forced Direct; на LTE с hash — проба или уход в обход | высокая | `NetworkRecoveryPolicy.kt:740-741`, `VpnTunnelService.kt:982-1048` |
| 2 | Android handover | Подтверждённая смена underlay → `requestSoftRestart(force=true)` → `stop` + `forgetTun`. `currentPathHealthy` не читается | Обрыв в момент Wi‑Fi ↔ LTE / SIM, даже если AWG был жив | высокая | `NetworkRecoveryPolicy.kt:508-568`, `VpnTunnelService.kt:666-671, 1815-1847` |
| 3 | Android Bypass | Handshake-stall 18 с при ≤ 200 КБ и traffic-stall 30 с внутри 120 с после handoff. Окно открывается и на старте сессии. В traffic-stall передан `lastInboundGrowthAtMs` | Soft restart обхода, пока пользователь просто не качает | высокая | `NetworkRecoveryPolicy.kt:827-896`, `VpnTunnelService.kt:1085-1124` |
| 4 | Go + wdtt-server | Сервер закрыл raw-сессию (idle 90 с или первый пакет не AUTH/GETCONF), воркер остаётся READY: reader глотает timeout, write не падает, ↑ считается до enqueue | «Подключено», ↓ плоский, затем `unanswered-uplink` | высокая | `server/raw.go:445-462, 632-661`, `session.go:724-732`, `dispatcher.go:349` |
| 5 | Go + Android | Все воркеры упали вместе. Обычный retry 5–16 с, grace нуля воркеров 8 с. `context canceled` не быстрый retry | Лишний soft restart поверх уже идущего реконнекта | высокая | `group.go:290-295`, `NetworkRecoveryPolicy.kt:354-355` |
| 6 | Сервер, каскад | Handshake hop старше `ARDTT_CASCADE_STALE_SEC` (180 с) или пустой → `awg0` и `wdttraw0` down. Ready остаётся зелёным | Телефоны на входе теряют туннель пачкой, раз в пропущенный rekey | высокая, если каскад включён | `cascade-entrypoint.sh:42-44, 345-354, 395-422` |
| 7 | Сервер, супервизор | `kill -0` каждые 2 с; умер любой ребёнок → `exit 1` → `restart: unless-stopped` гасит amneziawg-go, wdtt-server и каскад | Все сессии разом, в логе `child … died` или OOM | высокая как механизм | `entrypoint.sh:130-141`, `docker-compose.yml:28, 43` |
| 8 | Android, процесс | Нет сессионного wake lock. `TunnelSessionHolder.config` только в памяти. Sticky restart с пустым config → `stopForCommand`. `onDestroy` ставит `wantsConnected=false` | Интерфейс пропал и сам не вернулся | высокая | `TunnelSessionHolder.kt:6-9`, `VpnTunnelService.kt:391-396, 2415-2440` |
| 9 | Go TURN | 401 на Allocate не попадает в `turnCredRefreshNeeded`. Снимок кредов держится на время `RunSession` | Воркеры крутят 5–16 с без обновления кредов | высокая | `group.go:209-215, 241-246` |
| 10 | pion TURN | Зависимость `pion/turn v5.0.12`. Ошибка Refresh, кроме 438, по разбору модуля глотается. Исходник в дереве не вендорится | Allocation истекает, воркер выглядит живым (усиливает №4) | средняя | `android/go_client/go.mod` |
| 11 | Go, сон | Сборка `libclient.so` без патча часов. Таймеры Go 1.25 на `CLOCK_MONOTONIC` в suspend не идут | После сна сессия на VPS уже закрыта по idle 90 с, воркер ещё READY | средняя | `scripts/build-bypass-client.sh:54-58`, `go.mod` |
| 12 | Android Bypass | `GiveUp` (и Ask/NeedLogin) возвращают `KeepRecovering`, а `finally` уже закрыл TUN | Сервис «активен», fd закрыт, обход не поднимается | высокая | `CallRecreate.kt:66-73`, `ConnectionManager.kt:3106-3113`, `BypassSession.kt:161-167, 283-292` |
| 13 | Сервер, каскад | Каждые 5 с `setup_forwarding` снимает и заново ставит ip rule 220–235 и DNAT `ARDTT_CASCADE_MANAGED` | Короткие окна «подключено, ничего не грузится» | средняя | `cascade-entrypoint.sh:229-237, 254-264, 395-398` |
| 14 | Сервер, direct | Hot sync раз в 5 с; при ненулевом `syncconf` — `setconf`. `TouchPresence` переписывает `users.json` не чаще раза в минуту | Лишний resync peer’ов; `setconf` заново применяет конфиг | средняя | `direct/entrypoint.sh:128-137`, `provision/main.go:38-39, 1119-1197` |
| 15 | Go, учёт байтов | `TotalBytesUp` до enqueue, дроп локальный; `TotalBytesDown` только после успешного `tun.Write` | Ложный `unanswered-uplink` при живом TURN | средняя | `dispatcher.go:349, 470-478, 528` |

---

## 4. Разбор по слоям

### 4.1 Клиент Android

Точки, которые реально останавливают или пересоздают транспорт. Wake rescue при простое Direct **не** входит: без TX ≥ 4096 с момента `SCREEN_ON` он возвращает `None`.

| Точка | Условие | Действие | Гейт экрана |
|-------|---------|----------|-------------|
| Direct no-RX, ветка `!handshakeLive` | RX за 15 с ≤ 1024 и TX < 4096 и handshake не живой, после 4 с от старта или 3 с от handoff | `onDeadDirectNoRx` | нет |
| Direct no-RX, ветка TX | TX за 15 с ≥ 4096 и RX ≤ 1024 | то же | нет |
| Handover | `underlayChanged` и решение `SoftRestartSamePath` / `SwitchPath` | `requestSoftRestart(force=true)` | нет |
| Bypass handshake-stall | workers > 0, ≤ 200 КБ, 18–120 с после handoff, нет роста КБ 18 с | `onWatchdogFault` | да, `shouldObserveTunnelHealth` |
| Bypass traffic-stall | workers > 0, счётчик > 0, нет inbound-роста 30 с, внутри 120 с | то же | да |
| Bypass zero-workers | workers ≤ 0 непрерывно 8 с | то же | да |
| Bypass unanswered-uplink | workers > 0, ↑ рос в последние 45 с, ↓ не новее ↑, тишина ≥ 45 с | то же | да |
| Backend job dead | Direct, `sessionJob` не active ≥ 20 с | `onWatchdogFault` | нет (`shouldObserveDirectEgress`) |
| `GiveUp` / Ask / NeedLogin | мёртвый звонок | `KeepRecovering`, TUN уже закрыт | нет |
| Пустой config / `onDestroy` | sticky restart или смерть сервиса | `stopForCommand` или `wantsConnected=false` | нет |

Поллер сторожа: первый раз через 10 с, дальше каждые 3 с (`VpnTunnelService.kt:974-976`, `WATCHDOG_POLL_MS`).

#### 1. Idle Direct и handshake > 180 с

`shouldTreatDirectAsDeadNoRx` (`NetworkRecoveryPolicy.kt:721-741`) после отсечки 4 с / 3 с возвращает false, если входящих больше 1024 байт (`RecoverySettings.kt:136-139`). Иначе true, если TX ≥ `DIRECT_TX_DATA_MIN_BYTES` (4096, `NetworkRecoveryPolicy.kt:704`) **или** `!handshakeLive`. Keepalive 32 байта и handshake ~150 байт порог 4096 не берут, поэтому простой туннель убивает последняя строка: `return !handshakeLive`.

`directHandshakeLive` (`RecoverySettings.kt:119-128`) ложен при `handshakeSec <= 0` или если `nowSec - handshakeSec > 180`. Оба значения — unix epoch: `nowSec` это `currentTimeMillis()/1000` (`VpnTunnelService.kt:1009-1011`), `handshakeSec` — `last_handshake_time_sec=` из `awgGetConfig` (`VpnLiveStats.kt:415-424`). Пустой IPC даёт 0 (`VpnLiveStats.kt:139`) и тоже считается мёртвым.

Сторож Direct не вызывает `shouldObserveTunnelHealth`. Ветка `path == Direct` заканчивается `continue` (`VpnTunnelService.kt:1049`). Условие только `shouldObserveDirectEgress`: сессия жива, нет user-stop и нет soft restart (`NetworkRecoveryPolicy.kt:814-818`). Комментарий на `:813` прямо оставляет проверку включенной во время wake grace. Пробного пакета нет: сразу `onDeadDirectNoRx` (`VpnTunnelService.kt:1047`). Повтор не чаще раза в 30 с (`:1038-1040`).

`onDeadDirectNoRx` (`ConnectionManager.kt:2455-2482`) не всегда делает новый TUN:

| Режим | Решение | Дальше |
|-------|---------|--------|
| Auto + Wi‑Fi/Ethernet | `FailSession` | `DirectFailed` → backoff 2 с → `StartDirect` → soft restart (`stop` / `awgTurnOff` + `forgetTun`) |
| Auto + LTE + hash + whitelist | `SwitchToBypass` | уход с Direct |
| Auto + LTE + hash, whitelist нет | `MeasureUnderlay` | проба; `Ignore` оставляет туннель |
| Forced Direct, Auto без hash | `FailSession` | тот же soft restart |
| Auto + hash, не cellular и не Wi‑Fi | `KeepWatching` | ничего |

Отдельный wake rescue (`NetworkRecoveryPolicy.kt:638-664`) простой не рвёт: `DeadDirect` только при TX ≥ 4096 после пробуждения.

Лог: `watchdog: Direct uplink unanswered — sent `. Рядом раз в 15 с `watchdog: Direct rx=`. Дальше `Dead Direct (no TUN rx) — Auto recovery → Bypass` или `Dead Direct (no TUN rx) — recover or wait` или `Dead Direct underlay measure start`.

Исправление (отдельный PR): в `shouldTreatDirectAsDeadNoRx` не возвращать `!handshakeLive` на том же тике. Смерть по чёрной дыре оставить на ветке TX ≥ 4096 без полезного RX. Ветку «handshake старый, TX маленький» откладывать минимум на 25 с и срабатывать, только если за это время handshake не обновился и RX так и не вырос.

#### 2. Handover всегда полный restart

На usable VALIDATED `onUnderlyingNetworkLost()` вызывается до разбора перехода, включая `UNCHANGED` (`VpnTunnelService.kt:1463-1465`). Сама функция туннель не стопит: снимок underlay, `UnderlayUpdated`, предпроба LTE (`ConnectionManager.kt:2600-2607`). Ветка `UNCHANGED` в `when` пустая (`VpnTunnelService.kt:1509`).

`HANDOVER` идёт в `scheduleUnderlyingNetworkReconnect`. Для Direct пропуск restart возможен только если underlay **не** менялся и есть свежий RX (`NetworkRecoveryPolicy.kt:322-333`). `underlayChanged` истинен, если предыдущую сеть теряли или `previousNetworkId != null` (`:428-431`); handover передаёт предыдущий id, поэтому свежий трафик skip не даёт.

`decideNetworkHandoverAction` принимает `currentPathHealthy` и помечает его неиспользуемым (`NetworkRecoveryPolicy.kt:514`). При `underlayChanged` живой Direct на Wi‑Fi/Ethernet и на LTE (если не нужен Bypass) получает `SoftRestartSamePath` (`:537-544`, `:564-568`). `requestSoftRestart(..., force = true)` обходит cooldown (`:343`, вызов `:666-671`). Для Direct `launchBackend` всегда делает `stop()` → `awgTurnOff` (`DirectBackend.kt:131-134, 186-192`) и `forgetTun()` (`VpnTunnelService.kt:490-491`). Повторное использование TUN есть только у Bypass.

`rebindLiveDirectUnderlay` (`VpnTunnelService.kt:2167-2173`) только пинит underlay и заново protect/bind сокетов. Вызовы: `validated-initial` (`:1492`) и `skip-restart-fresh-rx` (`:1829-1830`). На подтверждённый HANDOVER он вместо restart не подставляется.

Лог: `soft restart #`, причина с префиксом `[СЕТЬ]`, `handover: no action` если решения нет, `Direct underlay rebind`.

Исправление (отдельный PR): если Direct здоров, возвращать `NoAction` и звать `rebindLiveDirectUnderlay`. `onUnderlyingNetworkLost()` на `:1465` оставить для HANDOVER, полной потери и suspend, не для `UNCHANGED`.

#### 3. Ложный сторож обхода

Bypass-сторож включён только при интерактивном экране, без wake-grace 35 с, не на trusted Wi‑Fi и не во время soft restart (`NetworkRecoveryPolicy.kt:802-811`). Сетевой рестарт с причиной `[СЕТЬ]` grace не ставит (`VpnTunnelService.kt:731-733`). Плюс `allowsWatchdogRestart`: не user-stop, сеть разрешена, recovery не in-flight (`ConnectionSessionModels.kt:130-131`).

`lastHandoffAtMs` равен времени старта сессии (`VpnTunnelService.kt:329`), не только смене сети. Окно 120 с открыто сразу после Connect.

Порядок — `else if` (`VpnTunnelService.kt:1085-1124`):

1. Handshake-stall: Bypass, workers > 0, `trafficKb <= 200`, с handoff прошло от 18 до 120 с, и либо роста КБ не было, либо он старше 18 с (`NetworkRecoveryPolicy.kt:877-896`). Простой после старта с нулём или малым трафиком попадает сюда.
2. Traffic-stall: workers > 0, переданный «trafficBytes» > 0 и stamp > 0, внутри 120 с нет роста 30 с (`:827-843`). Вне окна функция возвращает false. `TRAFFIC_STALL_IDLE_MS` (45 с) в теле не используется. Вызов передаёт `trafficKb` как байты и `lastInboundGrowthAtMs` как момент роста (`VpnTunnelService.kt:1105-1108`); лог при этом печатает `lastTrafficGrowthAtMs` (граница килобайта).
3. Unanswered-uplink: нужен недавний рост ↑. Чистый простой без TX сюда не попадает (`:856-873`).

0.5.249 уже не рвёт обход, пока счётчик КБ растёт. Дыра — когда рост остановился, а воркеры живы.

Лог: `watchdog: Bypass handshake-only traffic=`, `watchdog: traffic stall workers=`, `watchdog: Bypass uplink unanswered workers=`, `watchdog: zero workers for `, и с тегом `ConnMgr` строка `watchdog fact:`.

Исправление (отдельный PR): handshake-stall не срабатывает, если с handoff не рос uplink. Traffic-stall по одной тишине внутри 120 с убрать; чёрную дыру после handoff оставить на unanswered-uplink. В вызов передать `lastTrafficGrowthAtMs`.

#### 4. Смерть go-процесса и `GiveUp`

Цикл `BypassSession` при мёртвом процессе ставит `Failed` (`BypassSession.kt:148-155`). `finally` (`:161-167`) вызывает `cleanup` (`:283-292`) и закрывает TUN. `onTunnelFailed` при живом желании пользователя возвращает `KeepRecovering` (`ConnectionManager.kt:2970-2972`): `tunnelSessionActive` остаётся true, backend сам не запускается (`VpnTunnelService.kt:606-610`).

Обычная смерть процесса (не captcha, не FATAL_AUTH, не мёртвый звонок) всё же планирует повтор: `BypassFailed` → backoff 2, 5, 10, 20, 30, 60 с (`RecoverySettings.kt:64-66`, `ConnectionReducer.kt:1598-1634`) → `StartBypass` → `launchConnectJob` → `ACTION_START`. Дыра — эти секунды с закрытым fd и «активной» сессией. Таймер держит `ardtt:recovery-retry`, если задержка плюс 10 с не больше 120 с.

`GiveUp` после одного silent-attempt (`CallRecreate.kt:66-73`) только ставит `CallValidity.ConfirmedDead` и возвращает `KeepRecovering` (`ConnectionManager.kt:3106-3113`). В режиме Bypass reducer на этом даёт `NeedsUserAction` и `RecoveryCommand.None` (`AutoPathPolicy.kt:105-109`). Повтора нет. `AskUser` и `NeedVkLogin` — тот же `KeepRecovering` (`ConnectionManager.kt:3086-3104`).

Исправление (отдельный PR): `GiveUp` возвращает `Stop`, чтобы снять VPN. Для обычного `Failed` не ждать первый backoff: сразу `requestSoftRestart` того же пути, либо не закрывать TUN, пока новый `establish` не готов.

#### 5. Смерть процесса приложения

Сессионного `PARTIAL_WAKE_LOCK` нет (`RecoveryWakeLock.kt:24-31`). Три коротких замка: handover (запрос 19.5 с: 12 с ожидания VALIDATED + 6 с Wi‑Fi settle + 1.5 с пробы, `VpnTunnelService.kt:2763-2766`), soft restart 30 с (`:2768`), retry recovery. Потолок 120 с: более длинный таймер замок не берёт (`RecoveryWakeLock.kt:7, 15-21`).

`TunnelSessionHolder.config` — `@Volatile` в памяти (`TunnelSessionHolder.kt:6-9`). Профиль и hash звонка на диске есть, живая сессия — нет. `START_STICKY` с `intent == null` попадает в `ACTION_START` (`VpnTunnelService.kt:286-307`) и доходит до `launchBackend`; пустой config пишет `No session config` и зовёт `stopForCommand` (`:391-396`). Новый процесс создаёт `ConnectionManager` с `wantsConnected=false` (`ArdttApp.kt:27`).

Если сервис умер, а процесс ещё жив, `onDestroy` (`VpnTunnelService.kt:2415-2440`) зовёт `onServiceStopped(origin=Destroy)`. Destroy не считается остановкой пользователем (`TunnelTransportLifecycle.kt:213-216, 238`). Причина становится `unexpected` (`ConnectionManager.kt:3384-3390`), `AttemptFailed` обнуляет `wantsConnected` (`ConnectionReducer.kt:255-284`).

Исправление (отдельный PR): при старте сессии писать `profileId + path + wantsConnected`. В `launchBackend` при пустом holder сначала восстановить запись. В `failAttempt` для unexpected destroy не сбрасывать желание быть подключённым, а ставить retry.

#### `protect()` и DEFERRED

Провал `VpnService.protect` не роняет Direct. Три попытки, затем лог `protect … failed — AWG UDP may loop into the TUN` (`DirectBackend.kt:159-169`). Фатален только `awgTurnOn < 0` (`:103-109`).

`FOREGROUND_SERVICE_DEFERRED` ставится только в скрытом уведомлении, API 31+ (`VpnTunnelService.kt:2608-2611`), канал `IMPORTANCE_MIN` (`VpnNotificationStability.kt:59-66`). `startForeground` всё равно вызывается, тип `SPECIAL_USE` (`VpnTunnelService.kt:2510-2516`). Флаг откладывает показ уведомления и не меняет oom foreground-сервиса.

### 4.2 Go-обход

Пути: клиент `android/go_client/`, сервер `server/bypass/wdtt-server/server/` (не каталог `wdtt-server/` без `server/`).

#### Таймеры

| Что | Где | Значение |
|-----|-----|----------|
| Dial TURN | `session.go:139` | 10 с |
| TCP keepalive / `TCP_USER_TIMEOUT` | `netbind.go:42-46` | в коде не заданы. Дефолт Go 1.25: idle и interval 15 с, count 9 |
| STUN Binding | `session.go:323-336` | тикер 10 с, `SendBindingRequest` синхронно внутри `TryNetOp` (`control.go:110-117`, держит `opMu`) |
| RTO pion | модуль `pion/turn v5.0.12` | в нашем коде не задан; у pion типично 200 мс и до 7 попыток |
| App keepalive 0xFF | `session.go:33-39, 606-637` | каждые 10 с в `PrioCh`, в статистику байтов не входит |
| Read deadline воркера | `session.go:26, 724-732` | 30 мин; timeout → `continue`, сессия не кончается |
| Write deadline | `session.go:701` | 3 с выставляется. Ошибка Writer логируется (`:707-713`). Поведение обёртки pion `UDPConn` в этом дереве не проверялось |
| Ответ GETCONF_RAW | `protocol.go:19, 76` | чтение 45 с |
| Retry воркера | `group.go:290-295` | 5–16 с обычно; 1–3 с на pipe/reset/eof; 30–61 с на 486. `context canceled` в быстрый список не входит |
| Обновление кредов | `group.go:114-116` | не чаще раза в 15 с |
| Кэш TURN-кредов | `creds.go:152-153, 298` | TTL 10 мин минус 60 с. Аккаунт: 9 мин (`vk_account.go:16, 105`). Влияет на новый Allocate, не на живой |
| Цепочка VK HTTP | `creds_vkcalls.go:167, 210-323` | один проход, heartbeat звонка нет |
| Первый пакет raw | `raw.go:445-450` | 30 с, иначе тихий `return` |
| Не AUTH и не GETCONF | `raw.go:459-462` | соединение закрывается сразу |
| Цикл данных raw | `raw.go:632-661` | read 20 с, idle 90 с, keepalive 0xFF не обновляет «данные», но обновляет `lastActivity` |
| Очередь demux | `udp_demux.go:103, 123-124` | 256; переполнение — тихий `default` |
| Downlink на воркер | `raw.go:60, 102-108` | 256, переполнение дропает пакет |
| SendCh / PrioCh / ReturnCh | `session.go:25`, `dispatcher.go:40-41` | 128 / 32 / 512, переполнение дропает |
| Дворник паролей | `database.go:610-616, 639-648` | раз в час |
| `users.json` | `server/bypass/entrypoint.sh:111-124` | poll 5 с; SIGHUP только если изменился отпечаток кредов |
| Часы | `scripts/build-bypass-client.sh:54-58` | обычный `go build`. Патча `CLOCK_BOOTTIME` в дереве нет ни у `libclient.so`, ни у `libwg-go`. Go 1.25 (`go.mod`) |
| Логи pion | `session.go:102-105, 262` | `NullLoggerFactory` |

#### Как процесс вообще завершается

В data-plane нет своего `os.Exit` на ошибке сокета. `main` ждёт все группы (`main.go:484-487`, лог `[КЛИЕНТ] Все воркеры завершены`) и возвращается — код выхода 0. Kotlin видит мёртвый процесс и ставит `Failed` (`BypassSession.kt:148-155`).

Воркер выходит из цикла навсегда на `FATAL_AUTH` (`group.go:257-260`, текст из `DENIED:` в `protocol.go:36-43` и `:92-99`) и на `error 29` / `cannot create socket` (`group.go:277-282`). Проверка строки `хеш мёртв` (`group.go:90, 257`) мёртвая: такую строку Go нигде не печатает. `CALL_UNAVAILABLE` группу не останавливает.

Строки, которые Kotlin считает фатальными: `FATAL_AUTH`; `CALL_UNAVAILABLE` и коды 951, 954, 9000–9999 (`creds.go:47-55, 65-82`, логи `:352-354, 383-385`, `creds_vkcalls.go:240, 269`); капча (`creds.go:344, 388`); `all VK credentials failed` (`creds.go:407`). После того как RAWCONF уже отдан, `BypassGoProcess` всё ещё пишет `lastError` и зовёт `onFatal` (`BypassGoProcess.kt:166-169`), но сессия в `Running` этого deferred больше не ждёт. Фаза `Failed` наступает, когда процесс реально завершился.

#### G1. Сессия на сервере мертва, воркер READY

Сервер закрывает raw после 90 с без байтов (keepalive 0xFF активность обновляет, так что при живом 0xFF idle 90 с не набирается) или сразу, если первый пакет новой сессии не `GETCONF_RAW` и не `AUTH`. Клиентский reader на timeout делает `continue`. Пока `Write` не вернул ошибку, воркер в READY. `TotalBytesUp` увеличивается в момент чтения из TUN, до постановки в очередь (`dispatcher.go:349`). `down` не растёт. Kotlin через 45 с при живых workers видит unanswered-uplink.

Если keepalive до сервера не доходит (сон, мёртвый TCP, протухшая allocation), idle 90 с закрывает сессию, а клиент об этом не узнаёт. Симптом класса «подключено, данные не идут», затем soft restart.

Исправление (отдельный PR): read timeout заметно меньше 90 с должен завершать `RunSession`; на TCP выставить `TCP_USER_TIMEOUT` 15–20 с; после тишины заново AUTH/GETCONF_RAW, а не ждать 30 мин.

#### G2. Refresh allocation в pion

В `go.mod` зафиксирован `github.com/pion/turn/v5 v5.0.12`. Разбор, что `Refresh` считает успехом любой ответ кроме 438, относится к внутреннему файлу модуля и здесь по строкам не подтверждался. Если это так, 401/437 выглядят как успешный refresh, lifetime не продлевается, лога нет из‑за `NullLoggerFactory`, и дальше сценарий G1.

Исправление (отдельный PR): свой Refresh или форк; на 401 — `refreshCreds` и новый Allocate. Пока исходник pion не проверен в этом дереве, PR должен начинаться с чтения `allocation.go` модуля.

#### G3. Общий backoff длиннее grace сторожа

Обычная пауза воркера 5–16 с (`group.go:290`). Ноль воркеров Kotlin рестартит через 8 с, и только при включённом экране. Если все воркеры упали разом и выпало больше 8 с, сторож убивает процесс, который уже ждал retry. Быстрый путь 1–3 с (pipe, reset, eof) grace переживает. `context canceled` туда не входит, поэтому смена сети и отмена сокета получают длинную паузу.

Исправление (отдельный PR): не считать «ноль воркеров» в окне, где группа ещё в retry; либо поднять grace выше 16 с; либо считать `context canceled` быстрым retry, когда отмена пришла от смены сети, а не от остановки пользователя.

#### G4. 401 не обновляет креды

`turnCredRefreshNeeded` (`group.go:241-246`) ищет `invalid credential`, `stale nonce`, `allocation mismatch`, `error 508`, `attribute not found`. Строка pion на 401 — `Allocate error response (401): Unauthorized` — в этот список не входит. `RunSession` работает со снимком кредов (`group.go:209-215`), сделанным до попытки.

Исправление (отдельный PR): распознавать `(401)` / `Unauthorized` и звать `refreshCreds` до следующего Allocate.

#### G5. Сон и монотонные часы

`scripts/build-bypass-client.sh:54-58` собирает `libclient.so` обычным `go build`. Патча перевода таймеров на `CLOCK_BOOTTIME` в репозитории нет. У Go 1.25 таймеры на Linux сидят на `CLOCK_MONOTONIC`, который в suspend стоит. За время сна не уходят Refresh и keepalive, VPS закрывает idle-сессию, TURN allocation истекает. После пробуждения воркер ещё числится живым — тот же G1.

Исправление (отдельный PR): патч часов для `libclient.so` и принудительный Refresh или новый Allocate на `SCREEN_ON`. Не путать с Direct: там wake rescue простой без TX не рвёт.

#### G6. `UPDATE_NETWORK` может стоять в очереди

STUN Binding держит `opMu` на время `SendBindingRequest`. `ApplyNetwork` и запрет net ops тоже берут `opMu` (`control.go:95-101, 173-176`). STDIN читает одна горутина (`main.go:139-141`). Сторож сокетов — `session.go:188-194`. Повтор того же handle — пустая операция (`control.go:168-169`). Пока Binding не вернулся (у pion это может быть пачка RTO), смена сети не применяется.

Исправление (отдельный PR): не вызывать сетевой STUN под тем же мьютексом, что и `ApplyNetwork`.

#### G7–G10

| ID | Суть | Почему не P0 |
|----|------|----------------|
| G7 | Полуоткрытый TCP до TURN. App-keepalive сбрасывает idle ядра, до `tcp_retries2` минуты. Детектора в коде нет | Средний усилитель G1 на LTE. Лечится `TCP_USER_TIMEOUT` вместе с G1 |
| G8 | Цепочка VK одноразовая, пульса звонка нет | Средний. Звонок может умереть на стороне VK, пока TURN ещё жив |
| G9 | Downlink только воркерам `gen == activeGen` (`raw.go:277-284`). Новый SID поднимает поколение (`raw.go:362-365`). Рестарт wdtt-server забывает сессии, клиент сам не делает повторный AUTH | Средний для чёрной дыры после рестарта контейнера. Совпадает с №7 |
| G10 | ↑ считается до дропа, ↓ только после успешной записи в TUN. Лог `[RAW-DIAG] пакет из TUN ДРОПНУТ` (`dispatcher.go:470-478`) | Средний усилитель ложного unanswered-uplink |

### 4.3 Серверный стек

Один контейнер `ardtt`, `restart: unless-stopped` (`server/docker-compose.yml:28`). Дети по роли — `server/entrypoint.sh:65-93` (direct, bypass, cascade, warp, dns, provision, telemetry).

Healthcheck `ready.sh` (интервал 15 с, timeout 8 с, retries 8, start 45 с, `docker-compose.yml:90-95`) контейнер не рестартит. `iface()` — это `ip link show` (`ready.sh:23`): admin-down интерфейс для неё всё ещё существует, поэтому каскад может уронить телефоны, а проверка останется зелёной (`ready.sh:39-41`). `/health` всегда отдаёт `ok: true` и заодно может до 2.5 с ходить в GitHub, кэш часа (`provision/main.go:217-244, 1567-1600`). Это делает контейнер unhealthy, но не причиной `restart`.

#### S1. Каскад роняет телефонные интерфейсы

Цикл 5 с (`cascade-entrypoint.sh:395-398`) читает `awg show cascade0 latest-handshakes`. Возраст больше `ARDTT_CASCADE_STALE_SEC` (по умолчанию 180, `:42-44`), пустое значение или 0 — не ок (`hop_handshake_ok`, `:334-342`). После grace от старта (`:406-408`) вход делает `ip link set awg0 down` и `wdttraw0 down` (`:345-354, 417-422`). Поднять их обратно может только свежий handshake (`:410-415`).

`latest-handshakes` двигается на полном handshake (~120 с rekey), не на keepalive. Keepalive 25 стоит только у hop peer (`:135`). Один пропущенный rekey переваливает за 180 с, и все телефоны на входе теряют интерфейс. Комментарий в скрипте: прежние 45 с роняли живой hop. В CHANGELOG этой смены нет, в коде порог уже 180.

Лог: `hop handshake stale — dropping phone tunnels`, обратное `hop handshake restored — raising phone tunnels`.

Исправление (отдельный PR): не опускать `awg0`/`wdttraw0` по одному пропущенному rekey. Считать hop мёртвым по нескольким подряд провалам и по отсутствию трафика, а не по одному `latest-handshakes`. Пока hop проверяется, не сносить ip rule на каждом тике (это S4).

#### S2. Любой ребёнок роняет весь контейнер

Супервизор (`entrypoint.sh:130-141`): каждые 2 с `kill -0` по pid-файлам, иначе лог `child <name> pid=<pid> died` и `exit 1`. Политика restart поднимает контейнер заново и обрывает amneziawg-go, wdtt-server, cascade0 и все сессии.

Память: `mem_limit` 1g, на хосте меньше 1500 МиБ — 512m (`docker-compose.yml:43`, `server/install-lib/common.sh:78-89`). Внутри одновременно wireproxy (лимит RSS 350 МБ, `warp/entrypoint.sh:36-37`, перезапуск процесса на `:582-586`), tun2socks, amneziawg-go, wdtt-server, dnsmasq, provision, gunicorn. На каскадном входе wireproxy стартует даже в `passthrough` (`install.sh:234-237`).

Проверка: `docker inspect -f 'oom={{.State.OOMKilled}} restarts={{.RestartCount}}'`.

Исправление (отдельный PR): смерть warp/dns/telemetry не должна делать `exit 1` всего супервизора. Либо рестартить только этого ребёнка. Память warp не должна делить один cgroup-лимит с dataplane без запаса.

#### S3. Hot sync Direct

`server/direct/entrypoint.sh:128-144`: mtime `users.json` раз в 5 с, затем `awg syncconf awg0`. Если `syncconf` вернул не ноль — `awg setconf` (`:137`). `setconf` заново применяет конфиг устройства; это не инкрементальный sync. Первый старт и так делает `setconf` (`:113`) и при ошибке убивает direct-ребёнка, что через S2 роняет контейнер.

`TouchPresence` (`provision/main.go:1119`) пишет `users.json` не чаще раза в минуту, если не изменились метаданные устройства (`:38-39, 1188-1197`). Итог — resync порядка раза в минуту, пока клиенты шлют presence. Серверного `PersistentKeepalive` в шаблоне нет (`direct/sync/main.go:45-49`); для сервера AWG это нормально, keepalive инициирует клиент.

Отдельно, это не спонтанный обрыв: `ListenPort` в conf берётся из `config.directPort` (`direct/sync/main.go:67-70`), а provision записывает туда **хостовый** `ARDTT_DIRECT_PORT` (`provision/main.go:612-618`). Docker публикует его на внутренний `ARDTT_DIRECT_LISTEN_PORT`, который установщик фиксирует как 51820 (`install.sh:90`, `docker-compose.yml:84`). Если хостовый порт не 51820, AWG внутри слушает не тот порт, куда смотрит publish. Ломается с первого пакета, а не «само через час».

Исправление (отдельный PR): в conf писать внутренний listen 51820, хостовый порт оставить только в профиле клиента. Hot path не должен падать в `setconf`, пока `syncconf` может обновить peer’ов.

#### S4. Пятисекундный flap правил на каскадном входе

Каждый тик зовёт `setup_forwarding` (`cascade-entrypoint.sh:398`). Для entry это заново снимает ip rule с приоритетами 220–235 и добавляет их (`:229-237`) и заново ставит DNAT с комментарием `ARDTT_CASCADE_MANAGED` (`:254-264`, `delete_commented` перед добавлением). Окно, в котором правило уже снято и ещё не добавлено, — чёрная дыра при живом туннеле.

Исправление (отдельный PR): ставить rule/DNAT один раз и повторять только если `ip rule show` их не нашёл.

#### S5. WARP и Hide-IP

wireproxy и tun2socks поднимаются заново каждую секунду, если процесс умер; wireproxy ещё и по RSS (`warp/entrypoint.sh:576-590`). `bring_up` пересоздаёт `warp0` (`:194-216`). Смена набора /32 делает `conntrack -D` по IP клиента (`:383-397`, вызовы на снятии и добавлении правила `:509-536`). Для Hide-IP это обрыв уже установленных TCP. Для обычного AWG без hideIp путь слабый: dataplane AWG через warp0 не идёт.

Исправление (отдельный PR): не флашить conntrack, если набор префиксов не менялся; не пересоздавать `warp0`, если адрес и маршрут уже на месте.

#### S6. Conntrack UDP на хосте

Стек sysctl conntrack не настраивает (`install.sh` не пишет `nf_conntrack_udp_timeout`). Короткий `nf_conntrack_udp_timeout_stream` на хосте может выкинуть поток AWG. Это низкий приоритет и проверяется на конкретном VPS, а не по коду приложения.

---

## 5. Как понять, какой это обрыв

Подставить имя контейнера в `$C` (`docker ps --format '{{.Names}}' | grep ardtt`).

### Клиент, телеметрия

Искать в `app_log` теги `VpnTunnel` и `ConnMgr`.

| Строка | Причина |
|--------|---------|
| `watchdog: Direct uplink unanswered — sent ` | №1. Смотреть `hsLive=` и числа `sent` / `received`. `hsLive=false` и sent далеко ниже 4096 — ветка handshake, не чёрная дыра по трафику |
| `watchdog: Direct rx=` | Снимок раз в 15 с, само по себе не решение |
| `Dead Direct (no TUN rx) — Auto recovery → Bypass` | №1, уход в обход |
| `Dead Direct (no TUN rx) — recover or wait` | №1, `FailSession`, дальше backoff и новый TUN |
| `Dead Direct underlay measure start` | №1, LTE, сначала проба |
| `soft restart #` и `[СЕТЬ]` | №2 |
| `handover: no action` | Сторож сети дошёл, restart не выбран |
| `Direct underlay rebind` | Rebind без сноса TUN |
| `watchdog: Bypass handshake-only traffic=` | №3, handshake-stall |
| `watchdog: traffic stall workers=` | №3, traffic-stall |
| `watchdog: Bypass uplink unanswered workers=` | №4 или G10, либо честный мёртвый uplink |
| `watchdog: zero workers for ` | №5, если рядом Go только что логировал ошибки воркеров |
| `watchdog: backend job dead → recovery` | Умер `sessionJob` Direct |
| `watchdog fact:` | То же решение уже принято, тег `ConnMgr` |
| `Holding VPN for recovery backoff` | `KeepRecovering`: fd мог уже закрыться (№12) |
| `No session config` | №8, sticky без конфига |
| `Service stopped cause=unexpected` | №8, `onDestroy` без пользовательского stop |
| `protect v4=` / `protect … failed` | Не обрыв. Провал protect туннель не гасит |
| `wake rescue: path=` и `looks healthy` | Простой после сна Direct сторожем пробуждения не убит |

Дерево:

1. Есть `No session config` или `cause=unexpected` → №8, процесс умер, сессия не восстановлена.
2. Есть `[СЕТЬ]` / `soft restart` без предшествующего watchdog → №2. Если тут же `Direct underlay rebind` и нет `soft restart` — это INITIAL или skip, не полный снос.
3. Есть `Direct uplink unanswered` → №1. Ветку отличить по `hsLive` и байтам TX.
4. Есть `handshake-only` или `traffic stall` при `workers` > 0 → №3.
5. Есть `uplink unanswered` и до него в Go нет `Ошибка Reader` → №4 или G10. Если есть `пакет из TUN ДРОПНУТ` → G10.
6. Есть `zero workers` и в Go `Ошибка (попытка` → №5.
7. Ни одной строки `VpnTunnel`, а на VPS `dropping phone` или `child … died` → сервер, §5.3.

### stdout Go

| Строка | Смысл |
|--------|--------|
| `[ВОРКЕР #N] [READY]` | Воркер считает сессию живой |
| `[СТАТИСТИКА] Активных:` | То, что Kotlin читает как workers |
| `[ВОРКЕР #N] Ошибка (попытка` | Обычный retry 5–16 с |
| `Ошибка Reader` / `Ошибка Writer` | Транспорт реально порвался, это не тихий idle |
| `[ДИСП] Воркер #N отключён (осталось: 0)` | Живых слотов не осталось, дальше №5 |
| `[TURN] Креды обновлены` | Сработал refresh. Если при 401 строки нет — G4 |
| `[STDIN] V1\|…\|UPDATE_NETWORK` | Команда дошла до STDIN. Если после неё долго нет смены сокетов — G6 |
| `[RAW-DIAG] пакет из TUN ДРОПНУТ` | G10 |
| `[КЛИЕНТ] Все воркеры завершены` | Процесс сейчас выйдет с кодом 0 |
| `FATAL_AUTH` / `CALL_UNAVAILABLE` / `all VK credentials failed` | Фатал звонка или пароля, не сторож трафика |

### VPS

```bash
docker logs --tail 200 "$C" 2>&1 | grep -E 'hop handshake|dropping phone|raising phone|child .* died|OOM|re-sync|setconf failed|wireproxy RSS|conntrack flushed'
docker inspect -f 'oom={{.State.OOMKilled}} restarts={{.RestartCount}} status={{.State.Status}}' "$C"
docker exec "$C" sh -c 'cat /data/cascade.status; awg show cascade0 latest-handshakes; date +%s; ip link show awg0 | head -2'
docker exec "$C" awg show awg0 latest-handshakes
docker exec "$C" sh -c 'ps -o pid,rss,comm -ax | sort -nrk2 | head'
sysctl net.netfilter.nf_conntrack_udp_timeout net.netfilter.nf_conntrack_udp_timeout_stream
```

`hop handshake stale — dropping phone tunnels` → S1. `child ardtt-direct pid=… died` или `OOMKilled=true` → S2. `users.json changed — re-sync` и следом обрыв handshake у всех peer → S3, особенно если в логе был неуспешный sync и сработал `setconf`. `conntrack flushed` → S5, только клиенты с Hide-IP. `cascade.status=down` при `ip link show awg0` без `state DOWN` не бывает в S1: S1 как раз ставит DOWN. Если status `down`, а link UP — смотреть роль exit, там status пишется, интерфейсы телефона не опускаются.

---

## 6. План исправлений

Каждый пункт — отдельный PR. В эту ветку правки кода не входят.

### P0

1. **Direct idle handshake.** `shouldTreatDirectAsDeadNoRx`: убрать немедленный `return !handshakeLive`. Чёрная дыра остаётся на TX ≥ 4096 без RX > 1024. Старый handshake без TX подтверждать повторной проверкой не раньше чем через 25 с. Файл `NetworkRecoveryPolicy.kt`, вызов в `VpnTunnelService.startWatchdog`.
2. **Handover без сноса живого Direct.** `decideNetworkHandoverAction` читает `currentPathHealthy`. Живой Direct → `NoAction` и `rebindLiveDirectUnderlay`. `onUnderlyingNetworkLost` не звать на `UNCHANGED`.
3. **Bypass stall на простое.** `shouldSoftRestartForHandshakeStall` требует рост uplink. `shouldSoftRestartForTrafficStall` не рестартит по тишине внутри 120 с. Вызов передаёт `lastTrafficGrowthAtMs`.
4. **G1, мёртвая raw-сессия.** В `session.go` timeout чтения завершает `RunSession` (ориентир — меньше серверных 90 с). Повторить AUTH/GETCONF_RAW. На dial TURN выставить `TCP_USER_TIMEOUT`. Сервер: не закрывать сессию, которая шлёт 0xFF, это уже так; дыра в том, что клиент не замечает закрытия.
5. **G3, grace vs retry.** Не запускать zero-workers, пока группа в своём retry, либо поднять `ZERO_WORKERS_GRACE_MS` выше 16 с. `context canceled` от смены сети — быстрый retry.
6. **S1, каскад.** Не опускать `awg0`/`wdttraw0` по одному устаревшему handshake. Несколько провалов подряд плюс отсутствие трафика. Не пересобирать ip rule на каждом тике.
7. **S2, супервизор.** Смерть не-dataplane ребёнка не делает `exit 1`. Dataplane-ребёнок перезапускается отдельно, без сноса остальных.

### P1

8. **G4.** В `turnCredRefreshNeeded` ловить `(401)` и `Unauthorized`, затем `refreshCreds`.
9. **G2.** Прочитать `allocation.go` модуля `pion/turn v5.0.12`. Если Refresh глотает не-438, заменить вызов своим или форком.
10. **№8, смерть процесса.** Писать сессию в DataStore. Sticky с пустым holder восстанавливает её. `failAttempt` на Destroy не обнуляет `wantsConnected`.
11. **№12, GiveUp.** `TunnelFailureAction.Stop` вместо `KeepRecovering` с закрытым TUN. Обычный `Failed` — немедленный soft restart, не первый шаг backoff 2–60 с.
12. **S3.** `ListenPort` в awg conf = внутренний 51820. Hot reload не падает в `setconf`.
13. **G5.** Патч `CLOCK_BOOTTIME` для `libclient.so` и явный re-Allocate на `SCREEN_ON`.
14. **G6.** STUN Binding не под `opMu` вместе с `ApplyNetwork`.

### P2

15. **G10.** Не увеличивать `TotalBytesUp` для пакета, который дропнут до воркера. Unanswered-uplink тогда не врёт на локальном дропе.
16. **S4.** Идемпотентные ip rule и DNAT без delete-all каждые 5 с. Можно сделать вместе с P0.6, если тот PR и так трогает цикл каскада.
17. **S5.** Не флашить conntrack и не пересоздавать `warp0` без смены набора.
18. **G8.** Редкий heartbeat звонка VK, отдельно от TURN keepalive.
19. **G9.** После рестарта wdtt-server клиент заново шлёт AUTH, а не ждёт смерти reader.
20. **S6.** Документировать рекомендуемые `nf_conntrack_udp_timeout*`, не менять sysctl молча при установке.
21. **Скрытое уведомление.** Снять `FOREGROUND_SERVICE_DEFERRED`, только если на конкретной прошивке отложенное уведомление путают с отсутствием FGS. На приоритет это не влияет.

---

## 7. Что уже чинили

Это закрытые эпизоды. Повторять их как текущую причину не нужно, кроме тех мест, где в таблице выше дыра осталась.

| Версия | Что сделано | Что осталось |
|--------|-------------|--------------|
| Клиент 0.5.243 | На каскаде правила hop 220/221, а не 320/321, чтобы heartbeat `users.json` не уводил `10.9.0.0/24` в eth0. RAW MTU 1280. SIGHUP обхода только при смене пароля, не на LastSeen | S4 по-прежнему каждые 5 с снимает 220–235. SIGHUP по кредам сохранён (`bypass/entrypoint.sh:111-124`) |
| 0.5.248 | Auto на Wi‑Fi не уходит в обход из‑за сорвавшегося Direct. Повтор `establish`, если TUN на миг null | №1 на Wi‑Fi по-прежнему делает `FailSession` и новый TUN, просто не переключает путь |
| 0.5.249 | Обход снова с 3 воркерами, не с 9. Handshake-stall не рвёт сессию, пока КБ растут | Не рвёт только растущий счётчик. Простой после остановки роста внутри 18–120 с остаётся (№3) |
| 0.5.250 | Вторая VALIDATED-сеть (LTE рядом с Wi‑Fi) — не handover | Полный restart на настоящей смене underlay остаётся (№2). `shouldUseTurnTcp` сейчас всегда true (`NetworkRecoveryPolicy.kt:191`); запись 0.5.250 про UDP на Wi‑Fi к текущему коду не относится |
| 0.5.251 | Сторож обхода смотрит рост ↓, не TX и не нулевые тики. Очередь handover хранит underlay. Несколько устройств — разные RAW IP | Traffic-stall всё ещё зовётся с `lastInboundGrowthAtMs`. Unanswered-uplink как раз задуман на рост ↑ без ↓ и поэтому ловит G1 |
| 0.5.254 | На VPS `awg0` MTU 1280 и TCPMSS, раньше 1420 при клиентском 1280. Handshake был, страницы висели | Это не снос интерфейса. См. §8 |
| 0.5.256 | `bindProcessToNetwork` до `awgTurnOn`. Direct MTU 1200, TCPMSS 1160, без AAAA | `protect()` после `awgTurnOn` по-прежнему не фатален. EPERM на `bindSocket` уже обойдён пином процесса |
| Стек 1.0.33 | Каскадный выход снова слушает UDP, учётка WARP в `data/warp` | Не про сторож и не про stale handshake |
| Стек 1.0.52 | Crash-loop контейнера: `--chmod=644` на каталоги `/etc` в scratch-слое. Установщик откатывался | Исторический полный обрыв всех сессий. В текущем overlay так не собирается. Живой механизм «весь контейнер умер» — S2, не этот chmod |
| Каскад, порог handshake | В коде было 45 с, сейчас 180 (`cascade-entrypoint.sh:42-44`). В CHANGELOG записи нет | Один пропущенный rekey (~120 с) всё ещё дотягивает до 180 с и роняет телефоны (S1) |

---

## 8. Что причиной не является

| Подозрение | Почему нет |
|------------|------------|
| Healthcheck и `ready.sh` | Проверка не рестартит контейнер. `ip link show` успешен и на admin-down, поэтому S1 не делает контейнер unhealthy |
| `netns-guard.sh` | `ardtt_require_container_netns` (`server/netns-guard.sh:13-18`) только отказывается менять dataplane, если процесс видит netns хоста. Сессии сам не рвёт |
| `ardttctl` | `runHealth` (`server/ardttctl/main.go:208-245`) читает `docker inspect` и печатает статус. Управляющих действий по туннелю нет |
| Телеметрия | Запись JSONL и `/health` с опросом GitHub не останавливают VPN. Долгий `/health` может покрасить healthcheck, см. выше |
| MTU как механизм обрыва | 0.5.254 и 0.5.256 чинили «handshake есть, страницы нет». Это не снятие интерфейса и не `awgTurnOff`. Путать с №1 и S1 не стоит |
| `FOREGROUND_SERVICE_DEFERRED` | Ставится только для скрытого уведомления. Foreground-сервис уже запущен, тип `SPECIAL_USE`. Приоритет процесса флаг не снижает |
| `onLost` сам по себе | Полная потеря сети ставит «Ожидание сети…» и шлёт `onUnderlyingNetworkLost`, который обновляет снимок. `stop` + `forgetTun` делает последующий handover, и только если решение — restart. `UNCHANGED` транспорт не перезапускает |
| Дисковый GC | `ardtt_gc_releases` (`server/install-lib/disk-cleanup.sh:172-200`) работает при установке и только с `ARDTT_ALLOW_RELEASE_GC=1`. На живой туннель не подписан |
| Ротация логов, рестарт dnsmasq | Ни супервизор, ни dataplane от них не завязаны. Рестарт dnsmasq как ребёнка супервизора уже покрыт S2, если процесс именно умер, а не перечитал конфиг |
