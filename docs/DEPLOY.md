# Деплой сервера ARDTT

> [!CAUTION]
> **Ранняя бета.** Деплой-часть не обещана рабочей. Автор **не несёт ответственности** за результат. Установка выполняется **на свой страх и риск**.

Документ описывает установку и сопровождение self-hosted стека **ARDTT** на VPS (деплой v2, стек 2.x). Обоснование архитектуры — [ADR 0006](adr/0006-deploy-v2.md). Состав сервисов — [ARCHITECTURE.md](ARCHITECTURE.md).

Стек представляет собой **один контейнер** в собственной Docker-сети. Каталог установки — `/opt/ardtt`.

---

## 1. Требования к VPS

| Требуется | Не требуется |
|-----------|--------------|
| Linux amd64 или arm64 | python3, Compose, git |
| root или sudo | сборка образа на VPS |
| `/dev/net/tun` | реестры образов (GHCR, Docker Hub) |
| исходящий HTTPS к `github.com` | |
| 200 МБ свободной RAM; на диске — около 5 размеров образа + 300 МБ | |

**Docker.** Работающий Docker используется как есть, не обновляется и не перезапускается. При отсутствии Docker установщик ставит его из репозитория дистрибутива:

| Система | Пакет |
|---------|-------|
| Debian, Ubuntu | `docker.io` |
| Fedora | `moby-engine` |
| Alpine | `docker` |
| openSUSE | `docker` |
| RHEL, AlmaLinux, Rocky | не поставляется: ошибка `DOCKER_MISSING`, установка Docker выполняется вручную |

Если Docker установлен, но демон не отвечает, установка прекращается с `DOCKER_NOT_RUNNING`.

---

## 2. Состав релиза

Для версии стека `X` в GitHub Release приложения публикуются:

| Ассет | Назначение |
|-------|-----------|
| `ardtt-server-X-linux-amd64.image.tar.gz` | образ `ardtt/server:X` (`docker save`, gzip) |
| `ardtt-server-X-linux-arm64.image.tar.gz` | то же для arm64 |
| `ardtt-deploy.sh` | установщик |
| `SHA256SUMS-server.txt` | суммы SHA-256 серверных ассетов |

Сборку и публикацию выполняет `.github/workflows/server-package.yml`. Ассеты прикрепляются только при пуше тега `v*`; уже опубликованный образ не перезаписывается.

---

## 3. Установка из приложения

Вкладка **Серверы** в режиме администратора. Приложение:

1. подключается по SSH (для каскада выход достигается через вход);
2. выполняет предварительную проверку только на чтение: архитектура, TUN, наличие Docker или пакета Docker в репозиториях;
3. передаёт по SFTP установщик `ardtt-deploy.sh` из APK в домашний каталог SSH-пользователя;
4. запускает его от root, предварительно скопировав в приватный временный файл;
5. отображает ход установки по строкам `ARDTT_PROGRESS` и итог по `ARDTT_DONE` либо `ARDTT_ERROR`.

Установщик выполняется в отдельной сессии. Обрыв SSH (блокировка экрана, смена сети) не прерывает переключение версии на сервере: оно завершается либо откатывается. Повторный запуск продолжает с текущего состояния.

Каскад: сначала устанавливается выход на втором VPS, затем вход на первом. Ключ входа передаётся на выход через provision API выхода; третий SSH-переход не требуется.

---

## 4. Ручная установка

```bash
curl -fsSLO https://github.com/2kristalls36-hue/ARDTT/releases/latest/download/ardtt-deploy.sh
sudo ARDTT_PUBLIC_HOST=203.0.113.10 ARDTT_DEPLOY_VERSION=2.0.0 ARDTT_AUTO_PORTS=1 bash ardtt-deploy.sh
```

Основные переменные:

| Переменная | Значение |
|------------|----------|
| `ARDTT_PUBLIC_HOST` | публичный адрес сервера (обязательно) |
| `ARDTT_DEPLOY_VERSION` | версия стека (обязательно) |
| `ARDTT_ROLE` | `entry` (по умолчанию) или `exit` |
| `ARDTT_DIRECT_PORT`, `ARDTT_BYPASS_PORT`, `ARDTT_PROVISION_PORT`, `ARDTT_TELEMETRY_PORT` | порты на хосте |
| `ARDTT_AUTO_PORTS=1` | занятый порт заменяется ближайшим свободным |
| `ARDTT_PROVISION_PUBLIC=0` | закрыть provision на `127.0.0.1` (по умолчанию `0.0.0.0`; приложение и клиенты тогда не смогут к нему обратиться) |
| `ARDTT_TELEMETRY_PUBLIC=1` | публиковать telemetry на `0.0.0.0` (по умолчанию `127.0.0.1`) |
| `ARDTT_CASCADE_*` | параметры каскада |
| `ARDTT_RELEASE_TAG` | релиз с образом (по умолчанию определяется через GitHub API) |
| `ARDTT_IMAGE_FILE` | локальный файл образа вместо загрузки |
| `ARDTT_ACTION` | `install` (по умолчанию), `uninstall`, `status` |

Сборка из исходников для разработки:

```bash
bash scripts/build-server-image.sh amd64
bash scripts/pack-server-image.sh amd64 dist
sudo ARDTT_PUBLIC_HOST=… ARDTT_DEPLOY_VERSION="$(cat server/DEPLOY_VERSION)" \
  ARDTT_IMAGE_FILE=dist/ardtt-server-$(cat server/DEPLOY_VERSION)-linux-amd64.image.tar.gz \
  bash server/deploy/ardtt-deploy.sh
```

---

## 5. Порядок установки и обновления

1. Проверки: root, архитектура, `/dev/net/tun`, свободная память.
2. Docker: используется имеющийся либо ставится из дистрибутива.
3. Образ: если `ardtt/server:X` уже загружен — шаг пропускается; иначе проверка места, загрузка файла образа, сверка SHA-256, `docker load`, удаление файла.
4. Параметры экземпляра: из `/opt/ardtt/instance.env`, при миграции — из файлов 1.0.x, при первой установке — из переменных окружения.
5. Порты и сеть. Порты, занятые собственным контейнером, свободными считаются. Подсеть существующей собственной сети сохраняется.
6. Секреты и ключи каскада в `/opt/ardtt/data` (файлы 0600).
7. Переключение: текущий контейнер останавливается и переименовывается в `<имя>-prev`, создаётся новый, выполняется проверка готовности (`ready.sh`, до 2 минут).
   - Успех: `<имя>-prev` удаляется; образы ARDTT, кроме текущего и предыдущего, удаляются.
   - Неуспех: новый контейнер удаляется, предыдущий возвращается и проверяется; ошибка `READINESS_FAILED`.
8. Запись `instance.env`, вывод `ARDTT_DONE`.

Новые токены выводятся в `ARDTT_DONE` только при их создании. При обновлении и миграции существующие токены сохраняются.

### Миграция с 1.0.x

Выполняется автоматически при первом запуске v2 на сервере с установкой 1.0.x. Переносятся идентификатор экземпляра, порты, подсеть, роль, параметры каскада и публикация provision; каталог `data` остаётся на месте. Распознаются также установки, не дошедшие до фиксации (`instance.pending.json`). После успешной проверки готовности удаляются служебные файлы 1.0.x (`releases/`, `current`, `staging/`, `incoming/`, `cache/`, `state/`, `.env`, `instance.json` и т. п.); `data`, `logs`, `backups` сохраняются. Docker Engine, установленный ARDTT 1.0.x, не удаляется: на нём могут работать другие контейнеры.

---

## 6. Изоляция от других сервисов

Установщик изменяет на хосте только:

- каталог `/opt/ardtt`;
- контейнеры и сеть с меткой `com.ardtt.instance=<id>`, образы `ardtt/server:*`;
- при отсутствии Docker — пакеты Docker из репозитория дистрибутива.

Установщик не выполняет `docker system prune`, `docker compose down`, не изменяет `/etc/docker/daemon.json`, sysctl хоста и правила iptables вне цепочек Docker. Проверка — `scripts/check-deploy-bundle.sh` (статически) и VPS-лаборатория `.github/workflows/vps-lab.yml` (на реальном сервере с соседними контейнерами).

Параметры контейнера: `cap_drop ALL` + `NET_ADMIN`, `NET_RAW`, `SETUID`, `SETGID`; `no-new-privileges`; лимиты памяти, CPU и PID; журнал 3 × 10 МБ.

---

## 7. Раскладка на диске

```
/opt/ardtt/
  instance.env       параметры экземпляра (0600)
  container.env      окружение контейнера (0600)
  ardtt-deploy.sh    копия последнего установщика
  deploy.log         журнал последнего запуска (0600, содержит новые токены)
  data/              данные provision, ключи, токены (том /data)
  logs/              журналы приложения (том /var/logs/app)
  backups/           резервные копии 1.0.x, если были
```

---

## 8. Порты

| По умолчанию | Протокол | Назначение | Доступ снаружи |
|--------------|----------|-----------|----------------|
| 51820 | UDP | Direct (на выходе — каскад) | да |
| 56003 | UDP | Bypass RAW | да (кроме выхода) |
| 9100 | TCP | provision | да (закрывается `ARDTT_PROVISION_PUBLIC=0`) |
| 9200 | TCP | telemetry | нет (`127.0.0.1`), да при `ARDTT_TELEMETRY_PUBLIC=1` |

Provision публикуется наружу по умолчанию: к нему по публичному адресу сервера обращаются приложение администратора (`/v1/users*`) и клиентские устройства (`/v1/profile`, `/v1/presence`, `/v1/hide-ip`, `/v1/netcheck`). В стеке 1.0.54–1.0.57 при чистой установке он публиковался только на `127.0.0.1`, из-за чего управление из приложения и клиентские функции на таких серверах не работали.

Provision API: Bearer-токен обязателен для всех эндпоинтов, кроме `/health` и `/ready` ([ADR 0005](adr/0005-provision-auth-and-tls.md)). Токен администратора — `data/admin.token`. Отпечаток self-signed TLS — поле `provisionCertFp` в `/health` и `ARDTT_DONE|provision_cert_fp=`.

---

## 9. Удаление

```bash
sudo ARDTT_ACTION=uninstall ARDTT_PURGE_DATA=1 bash /opt/ardtt/ardtt-deploy.sh
```

Удаляются контейнеры и сеть экземпляра, образы ARDTT (если на хосте не осталось других экземпляров ARDTT) и каталог `/opt/ardtt`. Без `ARDTT_PURGE_DATA=1` сохраняются `data`, `logs`, `backups`. Docker остаётся.

---

## 10. Проверка после установки

```bash
sudo ARDTT_ACTION=status bash /opt/ardtt/ardtt-deploy.sh
docker ps --filter label=com.ardtt.owner=ardtt
curl -s http://127.0.0.1:9100/health
docker exec ardtt bash /opt/ardtt/ready.sh
docker exec ardtt provision -cmd create-user -name smoke -data /data
```

---

## 11. Коды ошибок

| Код | Причина и действие |
|-----|-------------------|
| `NOT_ROOT` | запуск не от root |
| `UNSUPPORTED_ARCH` | архитектура не amd64/arm64 |
| `TUN_MISSING` | провайдер отключил TUN; включить в панели VPS |
| `LOW_MEMORY` | свободно меньше 200 МБ RAM |
| `DOCKER_MISSING` | в репозиториях дистрибутива нет Docker; установить вручную |
| `DOCKER_INSTALL_FAILED` | пакетный менеджер не поставил Docker (нет доступа к репозиториям) |
| `DOCKER_NOT_RUNNING` | Docker установлен, демон не отвечает |
| `INSUFFICIENT_DISK` | недостаточно места; в сообщении указаны требуемый и свободный объём |
| `DOWNLOAD_FAILED` | VPS не скачал образ или список релизов с GitHub |
| `SHA256_MISMATCH` | сумма образа не совпала с `SHA256SUMS-server.txt` |
| `IMAGE_LOAD_FAILED` | `docker load` не прошёл или архитектура образа не совпала |
| `PORT_BUSY` | порт занят, автоподбор выключен |
| `SUBNET_EXHAUSTED` | нет свободной подсети /24 для сети ARDTT |
| `CONTAINER_START_FAILED` | Docker не создал контейнер; предыдущая версия возвращена |
| `READINESS_FAILED` | новая версия не прошла проверку готовности; предыдущая возвращена |
| `ROLLBACK_FAILED` | не поднялись ни новая, ни предыдущая версия; требуется повторная установка |
| `DEPLOY_IN_PROGRESS` | на сервере уже выполняется установка |
| `FOREIGN_CONTAINER` | имена контейнера заняты чужими контейнерами |

Телеметрия категории `deploy` — [TELEMETRY.md](TELEMETRY.md).

---

## 12. Версия стека

Файл `server/DEPLOY_VERSION`. Повышается при любом изменении того, что работает на VPS (образ, `entrypoint.sh`, `ready.sh`, установщик). Синхронно обновляются `android/app/src/main/assets/deploy/` (`scripts/sync-deploy-assets.sh`, выполняется сборкой Gradle) и `DeployBundle.FALLBACK_VERSION`; согласованность проверяет `scripts/check-deploy-bundle.sh`.

Приложение устанавливает наибольшую версию стека, для которой в релизах опубликован образ v2. APK 0.5.x с установщиком 1.0.x образы v2 не распознают; для работы со стеком 2.x приложение должно быть обновлено.
