<div align="center">

<img src="docs/assets/brand/ardtt-icon-source.png" alt="ARDTT" width="128" height="128" />

# ARDTT

[![Latest release](https://img.shields.io/github/v/release/2kristalls36-hue/ARDTT?label=release)](https://github.com/2kristalls36-hue/ARDTT/releases/latest)
[![License: GPL-3.0](https://img.shields.io/github/license/2kristalls36-hue/ARDTT)](LICENSE)

[Скачать APK](https://github.com/2kristalls36-hue/ARDTT/releases/latest)
·
[Документация](docs/README.md)
·
[Поддержка автора](https://spasibomir.ru/pay/34807)

</div>

> [!CAUTION]
> **Ранняя бета.** Текущая версия нестабильна. Автор **не несёт ответственности** за работу деплой-части (установка и обновление стека на VPS из приложения или с Git). Установка клиента и сервера — **на свой страх и риск**.

**ARDTT** (Amnezia & Raw Dial over TURN Tunnel) — открытый Android-клиент и self-hosted стек **в этом репозитории**: туннель до **вашего** VPS. Прямой путь — AmneziaWG 2.0 по UDP. Обход поднимает локальный интерфейс на устройстве и несёт сырые IP-пакеты через TURN, маскируя транспорт под зашифрованный медиатрафик звонка (RAW Dial via TURN: WRAP).

Клиент — `android/`, сервер — `server/`. Репозиторий **публичный**: clone, архивы тегов и GitHub Releases читаются **без токена**. Стек **не** вшит в APK: телефон скачивает `install.sh` и `ardtt-stack-<DEPLOY_VERSION>.tar.gz` из Releases (запас — архив тега `v{versionName}` / `main`) и заливает на VPS по SSH.

> [!WARNING]
> **Назначение проекта**
> ARDTT — технический инструмент для туннелирования трафика через **ваш** сервер (VPS). Проект распространяется в ознакомительных и исследовательских целях, в том числе для изучения сетевых протоколов и self-hosted VPN.
>
> Автор **не призывает** использовать ARDTT для обхода блокировок или нарушения правил платформ и **не несёт ответственности** за сценарии применения пользователями. Это неофициальный продукт: не Amnezia, не VK и не Cloudflare.

> [!NOTE]
> Клиент **0.5.249** (`versionCode` 267), пакет `com.ardtt.app`. Серверный стек **1.0.39** (`DEPLOY_VERSION`, каталог `/opt/ardtt`). Канонический источник стека — этот репозиторий, не `assets/` APK.
>
> Заметки релиза — [CHANGELOG.md](CHANGELOG.md). Документы — [docs/](docs/README.md).

---

## Сейчас

| | |
|---|---|
| Клиент | **0.5.249** · minSdk 28 · APK `arm64-v8a` / `armeabi-v7a` / `x86_64` / universal · [Releases](https://github.com/2kristalls36-hue/ARDTT/releases/latest) |
| Стек | **1.0.39** · `/opt/ardtt` · контейнер `ardtt` (isolated netns) · переменные `ARDTT_*` |
| Откуда стек | GitHub Releases `ardtt-stack-1.0.39.tar.gz` или архив тега `v0.5.247` — **не** APK |
| Compose | единый `ardtt`: provision `:9100`, direct, bypass, dns, warp, cascade, telemetry `:9200` |
| Профиль | ссылка `ardtt://config` |
| Обновления | публичные GitHub Releases [`2kristalls36-hue/ARDTT`](https://github.com/2kristalls36-hue/ARDTT/releases): APK, `ardtt-update.json`, архив стека — без PAT. Только стабильные `versionName` (без `test`). Тестовые APK — [Actions → Artifacts](https://github.com/2kristalls36-hue/ARDTT/actions) |

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

Публичный канонический источник **клиента и стека**. Push в `main` собирает подписанный APK. Если в `versionName` есть `test` (например `0.5.247-test`) — файл только в **Actions → Artifacts**, не в Releases. Стабильная версия без `test` публикует GitHub Release: APK, `ardtt-update.json` и `ardtt-stack-<DEPLOY_VERSION>.tar.gz`. В APK остаётся только метка `deploy/DEPLOY_VERSION` — карточка сервера сравнивает её с `/health`.

```
ARDTT/
├── android/      # Jetpack Compose-клиент (Gradle живёт здесь)
├── server/       # единый Docker-образ ardtt (compose profile isolated)
├── scripts/      # APK, иконки, pack-stack (релизный архив server/)
├── docs/         # LEGEND, ARCHITECTURE, DEPLOY, TELEMETRY
├── .github/      # сборка APK (Releases только без test в versionName)
├── CHANGELOG.md
├── LICENSE       # GNU GPL v3
└── NOTICE        # Amnezia Apache-2.0 + SpaceNeuroX/qWDTT GPL RAW
```

Клиент и Gradle **не** вынесены в корень: сборка — `cd android && ./gradlew …`.

## Как поставить

Деплой в ранней бете: автор не обещает, что установка стека отработает, и не несёт за это ответственности. Ставите **на свой страх и риск**.

Один и тот же стек ставится **из приложения** (телефон скачивает `server/` с GitHub) или **клоном этого репозитория**. Клиенты — APK с [Releases](https://github.com/2kristalls36-hue/ARDTT/releases/latest).

| | Откуда код стека | Когда |
|---|---|---|
| **Приложение** | GitHub Releases `ardtt-stack-*.tar.gz` или архив тега; заливка по SSH | удобно с телефона |
| **Git** | `ARDTT_GIT_REF` / `git clone` тега релиза на VPS | shell на машине с Docker |

Клонируйте **тег** `v0.5.247` (стек **1.0.39**), а не скользящий `main`. Репозиторий публичный: HTTPS clone, `raw.githubusercontent.com` и Releases **не требуют** секретов.

Подробности, каскад и **повторный деплой**: [docs/DEPLOY.md](docs/DEPLOY.md).

## Быстрый старт

Клиент — готовый APK:

```bash
# https://github.com/2kristalls36-hue/ARDTT/releases/latest
```

Сервер с GitHub (тег релиза):

```bash
git clone --depth 1 --branch v0.5.247 \
  https://github.com/2kristalls36-hue/ARDTT.git
cd ARDTT/server
cp .env.example .env          # ARDTT_PUBLIC_HOST=IP_этого_VPS
docker compose --profile isolated up -d --build
curl -s http://127.0.0.1:9100/health
./scripts/create-user.sh alice
```

Каноническая раскладка `/opt/ardtt` (как после деплоя из приложения) — тот же `server/install.sh`, см. [Путь 2 в DEPLOY.md](docs/DEPLOY.md#путь-2--git--compose).

Каскад: `ARDTT_ROLE=entry|exit` и `ARDTT_CASCADE_*` в `.env`. С телефона: вкладка «Серверы», SSH, пароль или PEM.

Сборка APK из исходников:

```bash
cd android
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Подписанные APK — [GitHub Releases](https://github.com/2kristalls36-hue/ARDTT/releases/latest). Подробнее: [android/README.md](android/README.md), [server/README.md](server/README.md).

## Документация

| Документ | Содержание |
|----------|------------|
| [CHANGELOG.md](CHANGELOG.md) | Линейка 0.5.249 / стек 1.0.39 |
| [docs/LEGEND.md](docs/LEGEND.md) | Имя: Amnezia & Raw Dial over TURN Tunnel |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Схемы, probe, каскад, Hide-IP WARP |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Установка и повторный деплой: из приложения (GitHub) или клоном репозитория |
| [docs/TELEMETRY.md](docs/TELEMETRY.md) | Режим тестирования |
| [docs/UI.md](docs/UI.md) | Дизайн-система клиента: токены, нейминг, каркас экрана |
| [android/README.md](android/README.md) | Сборка клиента, keystore, релизы |
| [server/README.md](server/README.md) | Compose: provision, direct, bypass, dns, warp, cascade, telemetry |

## Поддержать

Донат добровольный и **не открывает** функций: [Спасибо Мир](https://spasibomir.ru/pay/34807). В приложении карточка **«Поддержка автора»**.

## Откуда код

- Path A — [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0).
- Path B RAW — [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0, `-listen-raw`).

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0** — [LICENSE](LICENSE), атрибуции — [NOTICE](NOTICE).
