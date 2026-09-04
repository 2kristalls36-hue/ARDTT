# ARDTT



<p align="center">
  <img src="docs/assets/brand/ardtt-icon-source.png" alt="ARDTT" width="128" height="128" />
</p>

[Релизы](https://github.com/2kristalls36-hue/ARDTT/releases) · [Поддержать проект](https://spasibomir.ru/pay/34807)



**ARDTT** — Android-приложение и self-hosted сервер для защищённого туннеля до **вашего VPS**. Клиент поднимает локальный VPN-интерфейс: прямой путь на **AmneziaWG 2.0** и резервный **RAW Dial via TURN** — сырые IP-пакеты через медиарелей звонка; снаружи обход похож на зашифрованный медиатрафик.

> [!WARNING]
> **Назначение проекта**
> ARDTT — технический инструмент для туннелирования трафика через **ваш** сервер (VPS). Проект распространяется в ознакомительных и исследовательских целях.
>
> Авторы **не призывают** использовать ARDTT для обхода блокировок или нарушения правил платформ и **не несут ответственности** за сценарии применения. Это **не** официальный продукт Amnezia, VK или Cloudflare.

> [!NOTE]
> Текущий релиз **0.5.214** (стек VPS **1.0.28**). Что изменилось — в [CHANGELOG.md](CHANGELOG.md).
> Архитектура, деплой и легенда имени — в [docs/](docs/).

---

**ARDTT** = **A**mnezia + **R**AW **D**ial via **T**URN.

| Путь | Когда | Транспорт | Подсеть |
|------|-------|-----------|---------|
| **Прямое** | UDP до VPS проходит | AmneziaWG 2.0, UDP `:51820` | `10.8.0.{host_id}` |
| **Обход** | прямой UDP режут | RAW / WRAP → TURN (TCP) → VPS `:56003` | `10.9.0.{host_id}` |

Опционально **«Скрыть свой IP»** — egress через Cloudflare WARP на VPS (на каскаде — на **выходном** сервере). DNS клиентов остаётся на шлюзе туннеля, не через WARP.

---

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

Деплой из приложения (SSH) — [docs/DEPLOY.md](docs/DEPLOY.md).

### Android

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Подписанные сборки: [GitHub Releases](https://github.com/2kristalls36-hue/ARDTT/releases) (`ardtt-0.5.214-*.apk` для `arm64-v8a`, `armeabi-v7a`, `x86_64` и universal).

1. Установить APK.
2. Импортировать профиль (JSON / файл / QR).
3. Для обхода — один раз создать звонок (hash только на телефоне).
4. Нажать **Подключить**.

По умолчанию — режим пользователя. Режим администратора: долгий тап в Настройках (логи, деплой VPS, расширенные опции).

---

## Структура репозитория

```
ARDTT/
├── README.md              ← вы здесь
├── CHANGELOG.md           ← текущий релиз
├── LICENSE                ← GNU GPL v3
├── NOTICE                 ← атрибуции upstream
├── docs/                  ← архитектура, деплой, легенда
├── android/               ← Kotlin / Compose клиент
│   ├── app/               ← UI, VPN, деплой
│   ├── tunnel/            ← AmneziaWG userspace (libwg-go)
│   └── go_client/         ← Path B RAW → libclient.so
├── server/                ← Docker Compose (6 сервисов)
│   ├── provision/         ← пользователи, ключи, профили
│   ├── direct/            ← AmneziaWG 2.0
│   ├── bypass/            ← RAW / wdtt-server
│   ├── dns/  warp/  telemetry-upload/
│   └── docker-compose.yml
└── scripts/               ← сборка APK, упаковка deploy-бандла
```

Подробнее: [docs/README.md](docs/README.md) · [android/README.md](android/README.md) · [server/README.md](server/README.md).

---

## Сервер: шесть сервисов

| Сервис | Порт | Назначение |
|--------|------|------------|
| `provision` | 9100/tcp | Пользователи, AWG-ключи, JSON-профили, `/health` |
| `direct` | 51820/udp | AmneziaWG 2.0, `10.8.0.0/24` |
| `bypass` | 56003/udp | RAW `-listen-raw`, `10.9.0.0/24` |
| `dns` | — | dnsmasq на `10.8.0.1` / `10.9.0.1` |
| `warp` | — | Cloudflare WARP egress при «Скрыть IP» |
| `telemetry` | 9200/tcp | Приём debug-логов (режим тестирования) |

Каскад из двух VPS: телефон знает только **вход**; выход делает WAN / WARP. См. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Документация

| Документ | Содержание |
|----------|------------|
| [CHANGELOG.md](CHANGELOG.md) | Релиз 0.5.214 |
| [docs/LEGEND.md](docs/LEGEND.md) | Имя ARDTT, Path A / Path B |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Схемы, probe, VK/TURN, WARP |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Установка на VPS из приложения и Compose |
| [docs/TELEMETRY.md](docs/TELEMETRY.md) | Режим тестирования |

---

## Поддержать проект

Open source. Донат добровольный и **не открывает** дополнительных функций.

- [Спасибо Мир](https://spasibomir.ru/pay/34807)
- В приложении: **Настройки → Поддержка автора**

---

## Upstream

- [AmneziaWG for Android](https://github.com/amnezia-vpn/amneziawg-android) (Apache-2.0) — прямой путь
- [SpaceNeuroX/proxy-turn-vk-android](https://github.com/SpaceNeuroX/proxy-turn-vk-android) (GPL-3.0, **qWDTT**) — RAW Path B
- [amurcanov/proxy-turn-vk-android](https://github.com/amurcanov/proxy-turn-vk-android) — классический WDTT (WireGuard over TURN/DTLS), **не** источник RAW
- [amurcanov/csqtt](https://github.com/amurcanov/csqtt) — родственный TURN/RTP-проект (другая кодовая база)

---

## Лицензия

Этот проект распространяется под лицензией **GNU General Public License v3.0 (GPL-3.0)**.

Комбинированное произведение (Apache AmneziaWG + GPL RAW bypass) — GPL-3.0. Текст лицензии: [LICENSE](LICENSE). Атрибуции: [NOTICE](NOTICE).
