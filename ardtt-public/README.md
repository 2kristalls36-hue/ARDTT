# ARDTT — Amnezia & RAW Dial over TURN Tunnel

<p align="center">
  <img src="docs/assets/ardtt-icon.png" alt="ARDTT" width="128" height="128" />
</p>

**ARDTT** — Android-клиент и self-hosted сервер для защищённого туннеля до **вашего VPS**: прямое подключение на **AmneziaWG 2.0** и резервный **RAW Dial via TURN** — сырые IP-пакеты через медиарелей ВК TURN-серверов; снаружи резервный путь похож на зашифрованный медиатрафик звонка.

> [!WARNING]
> ## Назначение проекта
> ARDTT — технический инструмент для туннелирования трафика через **ваш** сервер (VPS). Проект распространяется в ознакомительных и исследовательских целях.
>
> Авторы **не призывают** использовать ARDTT для обхода блокировок или наружения правил платформ и **не несут ответственности** за сценарии применения пользователями. Неофициальный продукт: Amnezia, VK, Cloudflare.

## Что такое ARDTT

| Буква | Значение |
|-------|----------|
| **A** | **Amnezia** / AmneziaWG 2.0 — быстрый прямой путь (UDP до VPS) |
| **R** | **RAW** — обход несёт сырые IP-пакеты, без WireGuard поверх TURN |
| **DTT** | **D**ial via **T**URN / **T**elephony-style — транспорт через инфраструктуру звонков |

> **Не WDTT:** классический [WDTT](https://github.com/amurcanov/proxy-turn-vk-android) — это WireGuard over TURN/DTLS. Path B ARDTT — **RAW** из линии [qWDTT / SpaceNeuroX](https://github.com/SpaceNeuroX/proxy-turn-vk-android).

## Два пути до одного сервера

```
┌─────────────────────────────────────────────────────────────┐
│  Android (VpnService + ConnectionManager)                   │
│  Профиль: direct endpoint + bypass endpoint + host_id       │
└───────────────┬─────────────────────────┬───────────────────┘
                │ Path A                  │ Path B
                ▼                         ▼
         AmneziaWG 2.0              RAW / WRAP → TURN (TCP)
         UDP :51820                 UDP :56003 (-listen-raw)
                │                         │
                └───────────┬─────────────┘
                            ▼
                   ваш VPS (Docker Compose)
                     10.8.0.{id}  direct
                     10.9.0.{id}  bypass
                            │
                            ▼
                      интернет
                 (опц. WARP egress)
```

| Путь | Когда | Транспорт | Подсеть клиента |
|------|-------|-----------|-----------------|
| **Прямое** | UDP до VPS проходит | AmneziaWG 2.0 | `10.8.0.{host_id}` |
| **Обход** | прямой путь недоступен | RAW через TURN (как медиазвонок) | `10.9.0.{host_id}` |

Приложение на старте оценивает сеть и предлагает маршрут. Один профиль — два endpoint'а, один `host_id`.

## Быстрый старт

### Сервер (VPS)

```bash
git clone https://github.com/2kristalls36-hue/ARDTT.git
cd ARDTT/server
cp .env.example .env          # NVPN_PUBLIC_HOST=ваш IP
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice  # JSON-профиль в stdout
```

Подробнее: [docs/server.md](docs/server.md) · [docs/deploy.md](docs/deploy.md)

### Android

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

1. Установить APK.
2. Импортировать профиль (JSON / файл).
3. Один раз войти в VK и **создать звонок** (hash хранится только на телефоне).
4. Нажать **Подключить**.

Подробнее: [docs/android.md](docs/android.md)

## Структура репозитория

```
ARDTT/
├── README.md                 ← вы здесь
├── LICENSE                   ← GNU GPL v3
├── NOTICE                    ← атрибуции upstream
├── docs/
│   ├── overview.md           ← сценарии и термины
│   ├── architecture.md       ← компоненты и потоки данных
│   ├── android.md            ← сборка клиента
│   ├── server.md             ← Compose-стек
│   ├── deploy.md             ← установка на VPS
│   └── assets/               ← иконки, бренд
├── android/                  ← Kotlin / Compose / VPN
│   ├── app/                  ← UI, ConnectionManager, deploy
│   ├── tunnel/               ← AmneziaWG userspace (libwg-go)
│   └── go_client/            ← Path B RAW → libclient.so
├── server/                   ← Docker Compose (6 сервисов)
│   ├── provision/            ← пользователи, ключи, профили
│   ├── direct/               ← AmneziaWG 2.0
│   ├── bypass/               ← RAW / wdtt-server
│   ├── dns/                  │ warp/ │ telemetry-upload/
│   └── docker-compose.yml
└── scripts/                  ← сборка APK, упаковка deploy-бандла
```

## Сервер: шесть сервисов

| Сервис | Порт | Назначение |
|--------|------|------------|
| `provision` | 9100/tcp | Пользователи, AWG-ключи, JSON-профили, `/health` |
| `direct` | 51820/udp | AmneziaWG 2.0, подсеть `10.8.0.0/24` |
| `bypass` | 56003/udp | RAW `-listen-raw`, подсеть `10.9.0.0/24` |
| `dns` | — | dnsmasq на шлюзах `10.8.0.1` / `10.9.0.1` |
| `warp` | — | Cloudflare WARP egress при «Скрыть IP» |
| `telemetry` | 9200/tcp | Приём debug-логов (режим тестирования) |

## Клиент: возможности

- Автовыбор маршрута (прямое / обход / авто)
- Деплой сервера из приложения по SSH (режим администратора)
- Исключения приложений и сайтов, split tunnel
- Soft-reconnect при Wi‑Fi ↔ LTE
- OTA-обновления APK (manifest на вашем VPS)
- Виджет, Quick Settings tile, shortcuts
- Добровольный донат ([Спасибо Мир](https://spasibomir.ru/pay/34807))

## Лицензия

**GNU General Public License v3.0** — [LICENSE](LICENSE).

Комбинированное произведение (Apache AmneziaWG + GPL RAW bypass) распространяется под GPL-3.0. Атрибуции: [NOTICE](NOTICE).

## Документация

| Документ | Содержание |
|----------|------------|
| [docs/overview.md](docs/overview.md) | Термины, пользовательские сценарии |
| [docs/architecture.md](docs/architecture.md) | Архитектура Path A / Path B |
| [docs/android.md](docs/android.md) | Сборка и модули Android |
| [docs/server.md](docs/server.md) | Серверный стек |
| [docs/deploy.md](docs/deploy.md) | Деплой на VPS |

## Поддержать проект

Open source, донат добровольный и **не открывает** дополнительных функций.

- [Спасибо Мир](https://spasibomir.ru/pay/34807)
- В приложении: **Настройки → Поддержать проект**

## Upstream

- [AmneziaWG for Android](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0)
- [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0, RAW Path B)
- [amurcanov/proxy-turn-vk-android](https://github.com/amurcanov/proxy-turn-vk-android) (WDTT, WG over TURN — **не** источник RAW Path B)
