# ARDTT v0.5.214

Android `versionName` **0.5.214** (`versionCode` 232). Стек VPS: **1.0.28** (`DEPLOY_VERSION`).

APK: [GitHub Releases](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.214) — `arm64-v8a`, `armeabi-v7a`, `x86_64`, universal.

## Коротко

- Каскад: Hide-IP (WARP) применяется на **выходном** VPS; при выключенном Hide-IP выход идёт в WAN, а не в Cloudflare.
- После одиночных обновлений входа/выхода больше не теряются cascade DNS и ключи WARP.
- Обход: быстрее failover на белом списке; Hide-IP показывает Cloudflare, а не IP VPS; сборка bypass на Docker 29 / маленьких дисках не пропускает snapshot.
- Перед обновлением стека на VPS сбрасывается Docker build cache.
- В приложении обновления смотрят репозиторий **ARDTT**, а не старые имена.
- Карточки серверов: значки Ubuntu (Circle of Friends) и Debian; убран мигающий ping-dot.
- Клиент: иконка AR / DTT, QS-тайл смены профиля, баннер доната, режим тестирования в обычных Настройках.

## Каскад и Hide-IP

- На каскаде policy Hide-IP (`/32` → `warp0`) ставит **выход**. Вход в режиме passthrough не перехватывает клиентов в свой WARP.
- Hide-IP выкл. — Direct и Bypass выходят с WAN выхода, а не всегда через Cloudflare.
- После standalone-деплоя входа или выхода восстанавливаются DNS каскада и ключи WARP (раньше дырявился резолв и «Скрыть IP»).
- WARP на сервере: **wireproxy + tun2socks**; переключение Hide-IP — `ip rule` + flush conntrack, без рестарта клиентского TUN.

## Обход (Path B)

- Медленный failover Bypass на белом списке исправлен: Auto быстрее уходит на обход, когда прямой UDP мёртв.
- Несовпадение Hide-IP с фактическим egress (показывался IP VPS вместо Cloudflare) устранено.
- Пересборка bypass на Docker 29 больше не пропускает snapshot на VPS ~1 ГиБ.
- В начале обновления стека на VPS очищается Docker build cache — меньше «залипших» слоёв на маленьком диске.

## Деплой и серверные карточки

- Значок ОС на карточке сервера: официальные марки Ubuntu и Debian, версия дистрибутива в бейдже.
- Логи установки/обновления используют то же терминальное оформление, что вкладка «Логи».
- С листа обновления сервера нельзя случайно смахнуть в «залипший» overlay.
- С карточки сервера убран мигающий ping-dot.

## Клиент и UI

- Лаунчер, adaptive-иконка и Quick Settings — тёмное **AR** / оранжевое **DTT**.
- Отдельный QS-тайл переключает VPN-профили, не только туннель вкл/выкл.
- Баннер «Поддержка автора» на экране Туннеля (пока подключено) и карточка в Настройках; в тёмной теме — отдельная палитра.
- Действия профиля при поднятом VPN выглядят заблокированными, а не «пропавшими».
- Рамка записи телеметрии больше не прячет блок оформления (тема).
- Режим тестирования доступен из общих Настроек (по-прежнему нужен режим администратора для вкладки).
- Карта Сети: контуры хопов, ping, карточка провайдера даже если underlay lookup не удался.

## Поставка

- Релизные APK режутся по ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) плюс universal.
- In-app обновления: GitHub Releases репозитория `2kristalls36-hue/ARDTT` (manifest `ardtt-update.json`), fallback — `update.json` на VPS дистрибуции.
- Продуктовое имя везде **ARDTT**. Старое черновое имя nonameVPN в README и дереве репозитория больше не используется.

## Рекомендации

- На открытой сети, если UDP до VPS проходит, оставляйте **Авто** / прямое — так быстрее.
- На белом списке ожидайте **обход** (TURN). Один hash на устройство; мёртвый звонок — recreate в настройках или диалог на Туннеле.
- Hide-IP — это egress, не третий клиентский путь. DNS туннеля не должен идти через WARP.
- Каскад: сначала ставьте **выход**, затем **вход**. Телефон коннектится только ко входу.

## Предыдущие релизы 0.5.20x

| Релиз | Суть |
|-------|------|
| [0.5.212](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.212) | Docker snapshot bypass на 1 ГиБ VPS |
| [0.5.211](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.211) | GitHub-обновления → репозиторий ARDTT |
| [0.5.210](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.210) | Сброс Docker build cache при обновлении VPS |
| [0.5.209](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.209) | Ubuntu Circle of Friends на карточке сервера |
| [0.5.208](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.208) | DNS/WARP каскада после standalone-деплоя |
| [0.5.207](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.207) | Терминал деплоя, бейдж ОС, донат в тёмной теме |
| [0.5.206](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.206) | Bypass на БС и Hide-IP IP mismatch |
| [0.5.205](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.205) | QS-профиль, карточки серверов/профилей, Hide-IP каскада |
| [0.5.203](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.203) | Иконка ARDTT, баннер доната, карта Сети |
| [0.5.200](https://github.com/2kristalls36-hue/ARDTT/releases/tag/v0.5.200) | Режим тестирования в общих Настройках |

Полный список: [Releases](https://github.com/2kristalls36-hue/ARDTT/releases).
