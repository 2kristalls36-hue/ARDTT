# ARDTT

**ARDTT** = **A**mnezia + **R**AW **D**ial via **T**URN / **T**elephony-style path.

Клиент под Android и self-hosted сервер на вашем VPS: быстрый прямой VPN на AmneziaWG 2.0 и автоматический обход **RAW через TURN**, если до сервера нет прямой видимости.

> Имя **не** WDTT/AWDTT: историческая **W** в WDTT — от WireGuard. Path B у нас — **RAW** из **qWDTT / SpaceNeuroX**, не WG-поверх-TURN.

Полная легенда: [docs/LEGEND.md](docs/LEGEND.md).  
Техническая схема: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

<p align="center">
  <img src="docs/assets/ardtt-icon.png" alt="ARDTT icon" width="128" height="128" />
</p>

---

## Зачем этот проект

Обычный VPN (и даже «замаскированный») бесполезен, если оператор режет IP/UDP до вашего VPS или пускает в интернет только «белый список» сервисов.  
**ARDTT** делает два контура до **вашего** сервера:

1. **Amnezia (прямое)** — AmneziaWG 2.0: быстро, когда UDP до VPS проходит.
2. **RAW Dial via TURN (обход)** — трафик через инфраструктуру звонков (TURN); снаружи похож на медиазвонок, внутри — сырые IP-пакеты (RAW), без второго WireGuard и без DTLS.

Приложение само на старте оценивает сеть и заранее выбирает метод. Вам остаётся нажать **«Подключить»**.  
По желанию — **«Скрыть свой IP»**: выход в интернет через Cloudflare, а не с адреса VPS.

---

## Как это выглядит для пользователя

По умолчанию — **режим пользователя** (только подключение).  
**Режим администратора** включается долгим удержанием в настройках: логи, деплой сервера, расширенные опции.

1. Ставите сервер на VPS (Compose / деплой из приложения).
2. Импортируете профиль (файл / JSON).
3. Один раз вход в VK — **создать звонок** (hash только на телефоне).
4. При запуске — проверка сети; на экране уже выбран метод.
5. «Подключить»; по желанию — «Скрыть свой IP».

---

## Как устроен обход (кратко)

```
Приложения → VPN-туннель на телефоне
                ↓
         шифрование WRAP (как RTP-медиа)
                ↓
         TURN по TCP (релей звонка)
                ↓
         ваш VPS (RAW) → интернет
                         ↘ при «Скрыть IP» → WARP → интернет
```

- Дозвон по умолчанию — **Звонок** (`vkcalls`).
- Запасной путь — **legacy** (с капчей).
- VK нужен, чтобы **создать** звонок; дальше — анонимно по hash.
- Один hash на устройство; тихий recreate — опция в настройках.

---

## Сервер (VPS)

Механика установки и работы стека: [docs/DEPLOY.md](docs/DEPLOY.md)  
(из приложения по SSH или `docker compose` в `server/`).

Шесть сервисов в Docker Compose:

| Сервис | Назначение |
|--------|------------|
| `direct` | AmneziaWG 2.0 |
| `bypass` | RAW/WRAP после TURN |
| `dns` | dnsmasq на шлюзах туннелей |
| `warp` | WARP egress при «Скрыть IP» |
| `provision` | пользователи, ключи, профиль |
| `telemetry` | приём debug-логов с Android |

У клиента один `host_id` → `10.8.0.{id}` (прямое) и `10.9.0.{id}` (обход).  
WARP — не третий способ дозвона, а **выход** с сервера.

---

## Клиент (Android)

- База: [AmneziaWG for Android](https://github.com/amnezia-vpn/amneziawg-android).
- Обход (RAW): линия **qWDTT / SpaceNeuroX** —
  [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android)
  (`-listen-raw` / `-raw`). Классический
  [WDTT amurcanov](https://github.com/amurcanov/proxy-turn-vk-android) — WG over TURN/DTLS,
  **не** источник RAW Path B (см. [NOTICE](NOTICE), [docs/LEGEND.md](docs/LEGEND.md)).
- `applicationId` пока `com.nonamevpn.app` (можно ставить рядом с официальной AmneziaWG).
- Имя на экране и в лаунчере: **ARDTT**.
- Soft-reconnect при Wi‑Fi↔LTE, исключения приложений/сайтов, уведомление VPN.

---

## Структура репозитория

```
ARDTT/
├── README.md
├── LICENSE                 ← GNU GPL v3
├── NOTICE                  ← атрибуции
├── docs/
│   ├── LEGEND.md           ← легенда имени ARDTT
│   ├── ARCHITECTURE.md
│   ├── DEPLOY.md           ← механика серверного деплоя
│   └── assets/ardtt-icon.png
├── android/                ← клиент
└── server/                 ← Compose-стек
```

---

## Лицензия

**GNU GPL v3** — [LICENSE](LICENSE), атрибуции — [NOTICE](NOTICE).

Не официальный продукт Amnezia, VK или Cloudflare.

---

## Документация

- [Легенда ARDTT](docs/LEGEND.md)
- [Архитектура](docs/ARCHITECTURE.md)
- [Деплой сервера](docs/DEPLOY.md)
- [NOTICE](NOTICE)
- [LICENSE](LICENSE)
