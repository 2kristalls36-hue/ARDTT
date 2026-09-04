<div align="center">

<img src="docs/assets/brand/ardtt-icon-source.png" alt="ARDTT" width="128" height="128" />

# ARDTT

[Релизы](https://github.com/2kristalls36-hue/ARDTT/releases)
·
[Поддержка автора](https://spasibomir.ru/pay/34807)

</div>

**ARDTT** (Amnezia & Raw Dial over TURN Tunnel) — Android-приложение и self-hosted сервер для защищённого туннеля до **вашего** VPS. Прямой путь — AmneziaWG 2.0 по UDP. Обход поднимает локальный интерфейс на устройстве и несёт сырые IP-пакеты через TURN, маскируя транспорт под зашифрованный медиатрафик звонка (RAW Dial via TURN: WRAP).

> [!WARNING]
> **Назначение проекта**
> ARDTT — технический инструмент для туннелирования трафика через **ваш** сервер (VPS). Проект распространяется в ознакомительных и исследовательских целях, в том числе для изучения сетевых протоколов и self-hosted VPN.
>
> Авторы **не призывают** использовать ARDTT для обхода блокировок или нарушения правил платформ и **не несут ответственности** за сценарии применения пользователями. Это неофициальный продукт: не Amnezia, не VK и не Cloudflare.

> [!NOTE]
> Клиент **0.5.220** (`versionCode` 238), пакет `com.ardtt.app`. Серверный стек **1.0.31** (`DEPLOY_VERSION`, каталог `/opt/ardtt`).
>
> Смена пакета с `com.nonamevpn.app` — это **новое приложение** для Android: обновление поверх старого APK не встанет, нужна переустановка.
>
> Заметки релиза — [CHANGELOG.md](CHANGELOG.md). Документы — [docs/](docs/README.md).

---

## Сейчас

| | |
|---|---|
| Клиент | **0.5.220** · minSdk 28 · APK `arm64-v8a` / `armeabi-v7a` / `x86_64` / universal · [Releases](https://github.com/2kristalls36-hue/ARDTT/releases) |
| Стек | **1.0.31** · `/opt/ardtt` · контейнеры `ardtt-*` · переменные `ARDTT_*` |
| Compose | `provision` `:9100`, `direct`, `bypass`, `dns`, `warp`, `cascade`, `telemetry` `:9200` |
| Профиль | ссылка `ardtt://config` |
| Обновления | GitHub Releases [`2kristalls36-hue/ARDTT`](https://github.com/2kristalls36-hue/ARDTT/releases) |

## Два пути до VPS

| | Прямое | Обход |
|---|---|---|
| Стек | AmneziaWG 2.0 | RAW Dial via TURN (qWDTT / SpaceNeuroX, `-listen-raw`) |
| Транспорт | UDP `:51820` | TURN/TCP → WRAP → VPS UDP `:56003` |
| Подсеть | `10.8.0.0/24` | `10.9.0.0/24` |
| Крипто | AmneziaWG | WRAP AEAD, **без DTLS**, **без вложенного WG** |

**«Скрыть свой IP»** — не третий клиентский путь: egress выбранного пользователя через Cloudflare WARP (`wireproxy` + `tun2socks`). DNS `:53` остаётся на `main`.

**Каскад** (два VPS): телефон знает только **вход**; WAN и Hide-IP WARP — на **выходе**. Hide-IP выкл. → интернет с WAN выхода, не через Cloudflare.

## Репозиторий

```
ARDTT/
├── android/      # Jetpack Compose-клиент (Gradle живёт здесь)
├── server/       # Docker Compose: 7 сервисов
├── scripts/      # APK, иконки, deploy-бандл
├── docs/         # LEGEND, ARCHITECTURE, DEPLOY, TELEMETRY
├── CHANGELOG.md
├── LICENSE       # GNU GPL v3
└── NOTICE        # Amnezia Apache-2.0 + SpaceNeuroX/qWDTT GPL RAW
```

Клиент и Gradle **не** вынесены в корень: сборка — `cd android && ./gradlew …`.

## Быстрый старт

Сервер:

```bash
cd server
cp .env.example .env          # ARDTT_PUBLIC_HOST
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice
```

Каскад: `ARDTT_ROLE=entry|exit` и `ARDTT_CASCADE_*` в `.env`. Обычный путь установки — SSH из приложения, см. [docs/DEPLOY.md](docs/DEPLOY.md).

Android:

```bash
cd android
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Подписанные APK — [GitHub Releases](https://github.com/2kristalls36-hue/ARDTT/releases). Подробнее: [android/README.md](android/README.md), [server/README.md](server/README.md).

## Документация

| Документ | Содержание |
|----------|------------|
| [CHANGELOG.md](CHANGELOG.md) | Линейка 0.5.220 / стек 1.0.31 |
| [docs/LEGEND.md](docs/LEGEND.md) | Имя: Amnezia & Raw Dial over TURN Tunnel |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Схемы, probe, каскад, Hide-IP WARP |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Установка на VPS из приложения и Compose |
| [docs/TELEMETRY.md](docs/TELEMETRY.md) | Режим тестирования |
| [android/README.md](android/README.md) | Сборка клиента, keystore, релизы |
| [server/README.md](server/README.md) | Compose: provision, direct, bypass, dns, warp, cascade, telemetry |

## Поддержать

Донат добровольный и **не открывает** функций: [Спасибо Мир](https://spasibomir.ru/pay/34807). В приложении карточка **«Поддержка автора»**.

## Откуда код

- Path A — [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0).
- Path B RAW — [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0, `-listen-raw`).

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0** — [LICENSE](LICENSE), атрибуции — [NOTICE](NOTICE).
