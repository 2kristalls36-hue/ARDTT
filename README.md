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

Клиент — `android/`, сервер — `server/`. Репозиторий **публичный**. Стек **не** вшит в APK: телефон по SSH запускает установку, а **VPS** сам качает пакет из GitHub Releases — по индексу `ardtt-server-<DEPLOY_VERSION>-linux-<amd64|arm64>.index.json` только недостающие компоненты (частичный деплой) либо целиком `ardtt-server-<DEPLOY_VERSION>-linux-<amd64|arm64>.tar.gz`. Архив содержит образ, Compose и Docker Engine (`vendor/docker.tgz`); установка делает `docker load`, без build/pull/git.

> [!WARNING]
> **Назначение проекта**
> ARDTT — технический инструмент для туннелирования трафика через **ваш** сервер (VPS). Проект распространяется в ознакомительных и исследовательских целях, в том числе для изучения сетевых протоколов и self-hosted VPN.
>
> Автор **не призывает** использовать ARDTT для обхода блокировок или нарушения правил платформ и **не несёт ответственности** за сценарии применения пользователями. Это неофициальный продукт: не Amnezia, не VK и не Cloudflare.

> [!NOTE]
> Клиент **0.5.264** (`versionCode` 282), пакет `com.ardtt.app`. Серверный стек **1.0.53** (`DEPLOY_VERSION`, каталог `/opt/ardtt`). Канонический источник стека — актив Releases, не `assets/` APK. Старые APK (ждут `ardtt-stack-*.tar.gz` / `main`) этот пакет не ставят.
>
> Заметки релиза — [CHANGELOG.md](CHANGELOG.md). Документы — [docs/](docs/README.md).

---

## Сейчас

| | |
|---|---|
| Клиент | **0.5.264** · minSdk 28 · APK `arm64-v8a` / `armeabi-v7a` / `x86_64` / universal · [Releases](https://github.com/2kristalls36-hue/ARDTT/releases/latest) |
| Стек | **1.0.53** · `/opt/ardtt` · один контейнер, isolated netns, labels `com.ardtt.owner` · переменные `ARDTT_*` |
| Откуда стек | GitHub Releases: `ardtt-server-1.0.53-linux-<amd64\|arm64>.tar.gz` (docker save + Engine), рядом `…index.json` и ассеты по слоям — **частичный деплой: VPS качает только недостающее**. Не APK, не исходники |
| Compose | production без `build:`; provision/direct/bypass/dns/warp/cascade/telemetry; host-порты на 51820/56003/9100/9200 внутри |
| Профиль | ссылка `ardtt://config` |
| Обновления | публичные GitHub Releases [`2kristalls36-hue/ARDTT`](https://github.com/2kristalls36-hue/ARDTT/releases): APK, `ardtt-update.json`, пакеты сервера — без PAT. Только стабильные `versionName` (без `test`). Тестовые APK — [Actions → Artifacts](https://github.com/2kristalls36-hue/ARDTT/actions) |

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

Публичный канонический источник **клиента и стека**. Push в `main` собирает подписанный APK. Если в `versionName` есть `test` — файл только в **Actions → Artifacts**. Стабильная версия публикует GitHub Release: APK, `ardtt-update.json` и `ardtt-server-<DEPLOY_VERSION>-linux-*.tar.gz`. В APK остаётся метка `deploy/DEPLOY_VERSION`.

```
ARDTT/
├── android/      # Jetpack Compose-клиент (Gradle живёт здесь)
├── server/       # единый Docker-образ ardtt (compose profile isolated)
├── scripts/      # APK, pack-server-package (docker save), проверки установщика
├── docs/         # LEGEND, ARCHITECTURE, DEPLOY, TELEMETRY
├── .github/      # сборка APK (Releases только без test в versionName)
├── CHANGELOG.md
├── LICENSE       # GNU GPL v3
└── NOTICE        # Amnezia Apache-2.0 + SpaceNeuroX/qWDTT GPL RAW
```

Клиент и Gradle **не** вынесены в корень: сборка — `cd android && ./gradlew …`.

## Как поставить

Деплой в ранней бете: автор не обещает, что установка стека отработает, и не несёт за это ответственности. Ставите **на свой страх и риск**.

Один и тот же стек ставится **из приложения** (VPS качает пакет с GitHub) или **архивом с Releases**. Сборка из исходников — [инструкция разработчика](docs/DEPLOY.md#путь-разработчика--сборка-из-исходников).

| | Откуда пакет | Когда |
|---|---|---|
| **Приложение** | GitHub Releases → HTTPS с VPS (`fetch-and-install.sh`, частичный деплой по индексу); телефон только SSH | удобно с телефона |
| **Архив** | тот же актив + `install.sh` из него | shell; Engine ставится из архива, если его ещё нет |

Старые APK с `ardtt-stack-*.tar.gz` и fallback на `main` пакет 1.0.53 не ставят. Подробности: [docs/DEPLOY.md](docs/DEPLOY.md).

## Быстрый старт

Клиент — готовый APK:

```bash
# https://github.com/2kristalls36-hue/ARDTT/releases/latest
```

Сервер с GitHub Releases (Engine из архива, если на VPS его ещё нет):

```bash
# https://github.com/2kristalls36-hue/ARDTT/releases/latest
# актив ardtt-server-1.0.53-linux-amd64.tar.gz (или arm64)
# сверьте SHA-256 с digest / SHA256SUMS релиза, затем:
export ARDTT_PUBLIC_HOST=IP_этого_VPS
export ARDTT_PACKAGE=/opt/ardtt/incoming/ardtt-server-1.0.53-linux-amd64.tar.gz
export ARDTT_PACKAGE_SHA256=...
# см. docs/DEPLOY.md
```

Разработчикам (сборка образа из дерева): `docker compose -f server/docker-compose.dev.yml up -d --build`.

Каноническая раскладка `/opt/ardtt` — [docs/DEPLOY.md](docs/DEPLOY.md).

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
| [CHANGELOG.md](CHANGELOG.md) | Линейка 0.5.264 / стек 1.0.53 |
| [docs/LEGEND.md](docs/LEGEND.md) | Имя: Amnezia & Raw Dial over TURN Tunnel |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Схемы, probe, каскад, Hide-IP WARP |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Пакет `ardtt-server-*-linux-<arch>.tar.gz`: приложение или архив; Engine в `vendor/docker.tgz` |
| [docs/TELEMETRY.md](docs/TELEMETRY.md) | Режим тестирования |
| [docs/UI.md](docs/UI.md) | Дизайн-система клиента: реестр компонентов и токенов, карта экранов, режимы |
| [docs/UI-AUDIT.md](docs/UI-AUDIT.md) | Аудит интерфейса: находки, исправления, отложенное |
| [android/README.md](android/README.md) | Сборка клиента, keystore, релизы |
| [server/README.md](server/README.md) | Compose: provision, direct, bypass, dns, warp, cascade, telemetry |

## Поддержать

Донат добровольный и **не открывает** функций: [Спасибо Мир](https://spasibomir.ru/pay/34807). В приложении карточка **«Поддержка автора»**.

## Откуда код

- Path A — [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0).
- Path B RAW — [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0, `-listen-raw`).

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0** — [LICENSE](LICENSE), атрибуции — [NOTICE](NOTICE).
