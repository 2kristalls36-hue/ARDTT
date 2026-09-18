# ADR 0005 — аутентификация и TLS provision API

- Статус: принято
- Дата: 2026-09-18
- Стек: 1.0.54

## Контекст

До 1.0.53 provision слушал `0.0.0.0:9100` без аутентификации и без TLS.
`GET /v1/users` и `GET /v1/profile/{name}` отдавали приватные ключи AWG и пароль
bypass любому хосту в интернете. `POST /v1/cascade/peer` перезаписывал ключ хопа.
`/v1/presence`, `/v1/hide-ip`, `/v1/netcheck` принимали чужой `name`/`deviceId`.

Телефон уже имеет SSH на VPS. Карточка сервера зондирует TCP/HTTP `:9100`.

## Варианты

1. **Только bind `127.0.0.1` на хосте**, API без токенов. Закрывает интернет, но любой
   локальный процесс и SSH-сессия читают ключи. Каскад `POST /v1/cascade/peer` идёт
   по WAN entry→exit — без токена это снова открытый эндпоинт.
2. **Токены + bind `127.0.0.1` по умолчанию + self-signed TLS на том же порту**
   (HTTP и HTTPS, детект ClientHello). Публикация `0.0.0.0` только при
   `ARDTT_PROVISION_PUBLIC=1`. Рекомендовано.
3. **mTLS / отдельный IdP.** Нет внешнего IdP в требованиях, телефон не готов.

## Решение

Вариант 2.

- Admin-операции (`/v1/users*`, `/v1/cascade/peer`, `/v1/hide-ip-prefixes`):
  `Authorization: Bearer` из `data/admin.token` (0600, 32 байта hex).
  Сравнение `crypto/subtle.ConstantTimeCompare`.
- `/v1/cascade/peer` и `GET /v1/hide-ip-prefixes` также принимают
  `ARDTT_CASCADE_SECRET` (Bearer или HMAC-SHA256 тела в `X-Ardtt-Cascade-HMAC`).
  `GET /v1/hide-ip-prefixes` с loopback (`RemoteAddr`, без `X-Forwarded-For`)
  остаётся без токена — warp в том же netns.
- Клиентские эндпоинты: per-user `deviceToken` в профиле при создании.
  Admin bearer тоже допускается (админ-UI). Действие только над своим пользователем.
- `/health` и `/ready` без токена (readiness и зонд карточки).
- Внутри контейнера provision по-прежнему слушает `0.0.0.0:9100` (overlay каскада
  `10.10.0.1` и docker-proxy). Ограничение интернета — **host bind**
  `127.0.0.1:port:9100`, не listen-адрес процесса. `X-Forwarded-For` для auth
  и rate-limit не доверяем: docker-proxy и так подставляет bridge, а заголовок
  подделывается.
- TLS: self-signed ECDSA P-256 в `data/tls/`, SHA-256 fingerprint в `/health`
  и в `ARDTT_DONE|provision_cert_fp=`. На `:9100` принимаются и HTTP, и HTTPS
  (первый байт `0x16` → TLS). Старый APK и `ready.sh` остаются на HTTP;
  `curl -k https://…` тоже работает. Закрыть plaintext снаружи должен bind,
  а не отказ в HTTP внутри netns (опубликованный порт приходит с адреса моста,
  не с `127.0.0.1`).
- Rate limit: token bucket по `RemoteAddr` (не XFF). Audit JSONL `data/audit.log`.
- Токен пишется при install, в `ARDTT_DONE` **один раз** (файл ещё не существовал).
- Обновление с 1.0.53: `data/` сохраняется; отсутствующий `admin.token` создаётся;
  пользователям без `deviceToken` токен выдаётся при первом старте provision.

## Сознательные отклонения от промта фазы 0

- **Non-root / `read_only: true` для всего контейнера отложены на фазу 3 (pod).**
  В одном netns `direct`/`warp` требуют `NET_ADMIN` и пишут в `/data` рядом с
  `users.json` 0600. Смена uid provision без разделения томов ломает запись
  store или чтение watcher'ами. Это не hotfix.
- **Старый APK (≤ 0.5.265) без Bearer получит 401** на admin/profile. Это цель
  hotfix, не регрессия установщика: stdout-протокол `ARDTT_*` не меняется,
  JSON `/health` расширяется полями. Админ-операции — SSH-туннель на
  `127.0.0.1:9100` или `ARDTT_PROVISION_PUBLIC=1` плюс токен.
- Версия стека **1.0.54**. Параллельный PR установщика не должен повторно
  занимать этот номер.

## Последствия

- Каскад entry→exit `POST /v1/cascade/peer` требует секрет выхода в
  `ARDTT_CASCADE_SECRET` на входе. Старый APK его не передаёт — push ключа
  завершится WARN; секрет из `ARDTT_DONE` выхода кладут на вход вручную
  (`/opt/ardtt/data/cascade.secret`) до появления поля в приложении.
- Телеметрия: обязательный Bearer (device token или `data/telemetry.token`),
  лимит 20 МБ, квота на `client_id`. Review не доверяет «локальному» Docker-NAT.
