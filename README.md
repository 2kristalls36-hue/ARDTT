<div align="center">

<img src="docs/assets/brand/ardtt-icon-source.png" alt="ARDTT" width="128" height="128" />

# ARDTT

[Релизы](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.214)
·
[Поддержка автора](https://spasibomir.ru/pay/34807)

</div>

**ARDTT** — Android-приложение и self-hosted сервер для защищённого туннеля до вашего VPS. Прямой путь — AmneziaWG 2.0; обход поднимает локальный интерфейс на устройстве и маскирует транспорт под зашифрованный медиатрафик звонка (RAW Dial via TURN: WRAP, без DTLS и без вложенного WG).

> [!WARNING]
> **Назначение проекта**
> ARDTT — технический инструмент для туннелирования трафика через **ваш** сервер (VPS). Проект распространяется в ознакомительных и исследовательских целях, в том числе для изучения сетевых протоколов и self-hosted VPN.
>
> Авторы **не призывают** использовать ARDTT для обхода блокировок или нарушения правил платформ и **не несут ответственности** за сценарии применения пользователями. Это неофициальный продукт: не Amnezia, не VK и не Cloudflare.

> [!NOTE]
> Текущий клиент: **0.5.214** (`versionCode` 232). Серверный стек: **1.0.28**. Заметки релиза — [CHANGELOG.md](CHANGELOG.md). Технические документы — [docs/](docs/README.md).

---

## Сейчас

| Что | Значение |
|-----|----------|
| Клиент | [v0.5.214](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.214) · APK: `arm64-v8a`, `armeabi-v7a`, `x86_64`, universal |
| Сервер | `DEPLOY_VERSION` **1.0.28** · Compose: `provision` `:9100`, `direct`, `bypass`, `dns`, `warp`, `telemetry` `:9200` |
| Обновления | GitHub Releases [`2kristalls36-hue/ARDTT`](https://github.com/2kristalls36-hue/ARDTT/releases) |

## Два пути

| Параметр | Path A · прямое | Path B · обход |
|---|---|---|
| Стек | AmneziaWG 2.0 | RAW Dial via TURN (qWDTT / SpaceNeuroX, `-listen-raw`) |
| Транспорт | UDP `:51820` | TURN/TCP → WRAP → VPS UDP `:56003` |
| Подсеть | `10.8.0.0/24` | `10.9.0.0/24` |
| Крипто | AmneziaWG | WRAP AEAD, **без DTLS**, **без вложенного WG** |

Опционально **«Скрыть свой IP»**: egress через Cloudflare WARP (`wireproxy` + `tun2socks`). DNS `:53` остаётся на `main`.

**Каскад:** телефон говорит только со входным VPS; WAN и Hide-IP WARP — на **выходном** VPS. Hide-IP выкл. → выход в интернет с WAN выхода, не через Cloudflare.

## Репозиторий

```
ARDTT/
├── README.md
├── CHANGELOG.md
├── LICENSE                 # GNU GPL v3
├── NOTICE                  # Amnezia Apache-2.0 + SpaceNeuroX/qWDTT GPL RAW
├── android/                # Jetpack Compose-клиент (Gradle живёт здесь)
├── server/                 # Docker Compose-стек
├── scripts/                # APK, иконки, deploy-бандл
└── docs/                   # LEGEND, ARCHITECTURE, DEPLOY, TELEMETRY
```

Клиент и Gradle **не** вынесены в корень: сборка — `cd android && ./gradlew …`.

## Быстрый старт

Сервер:

```bash
cd server
cp .env.example .env          # NVPN_PUBLIC_HOST
docker compose up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice
```

Android:

```bash
cd android
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Подписанные APK — [GitHub Releases](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.214). Деплой из приложения (SSH) — [docs/DEPLOY.md](docs/DEPLOY.md).

Подробнее: [android/README.md](android/README.md), [server/README.md](server/README.md).

## Документация

| Документ | Содержание |
|----------|------------|
| [CHANGELOG.md](CHANGELOG.md) | Текущая линейка 0.5.214 / стек 1.0.28 |
| [docs/LEGEND.md](docs/LEGEND.md) | Имя ARDTT, Path A/B, знак |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Схемы, probe, каскад, WARP |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Установка на VPS из приложения и Compose |
| [docs/TELEMETRY.md](docs/TELEMETRY.md) | Режим тестирования |
| [android/README.md](android/README.md) | Сборка клиента, keystore, релизы |
| [server/README.md](server/README.md) | Шесть сервисов Compose |

## Поддержать

Донат добровольный и **не открывает** функций: [Спасибо Мир](https://spasibomir.ru/pay/34807). В приложении карточка **«Поддержка автора»**.

## Откуда код

- Path A — [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0).
- Path B RAW — [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0, `-listen-raw`).
- Классический [WDTT amurcanov](https://github.com/amurcanov/proxy-turn-vk-android) — WG over TURN/DTLS, **не** источник RAW.
- Близкий TURN/RTP-проект (другая архитектура и лицензия): [CSQTT](https://github.com/amurcanov/csqtt). ARDTT — не форк CSQTT.

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0** — [LICENSE](LICENSE), атрибуции — [NOTICE](NOTICE).
