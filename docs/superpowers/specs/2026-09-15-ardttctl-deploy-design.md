# ardttctl: transactional VPS deployment

Дата: 2026-09-15. Стек на момент аудита: **1.0.53**. Клиент **0.5.264** (`versionCode` 283).

Это Phase 0 (аудит + решение). Реализация идёт **поверх** `install.sh` / `fetch-and-install.sh`, без их удаления. Kubernetes / OCI-primary / split контейнера — не в этом срезе.

## 1. Как реально работает текущий deployment

```
Android DeployEngine
  SSH (JSch) → VPS
    если есть /opt/ardtt/current/fetch-and-install.sh → его
    иначе SFTP APK bootstrap → /opt/ardtt/bin/fetch-and-install.sh
  env: ARDTT_DEPLOY_VERSION, порты, ROLE, CASCADE_*, DISK_CLEANUP, CPUS
  VPS: flock fetch.lock → GitHub Releases (index / tar.gz) → SHA-256
    → flock install.lock → verify staging (живой стек ещё up)
    → docker load → snapshot current → previous/
    → stop_owned_stack → rm -rf current; cp -a releases/<ver>/ current/
    → compose up --no-build --pull never
    → wait_readiness (ready.sh в контейнере, ~120 с)
    → instance.json + DEPLOY_VERSION + ARDTT_DONE
    при ошибке compose/readiness: restore_previous_release (compose up, без wait)
```

Телефон **не** качает пакет и **не** реализует state machine установки. Он парсит строки `ARDTT_PROGRESS|` / `ERROR|` / `DONE|` / `CASCADE_PUBLIC_KEY|`. `INFO`/`WARN` только в лог. JSON-строки уже игнорируются.

Успех деплоя для Android: **SSH exit 0 и нет `ARDTT_ERROR|`**. HTTP `:9100` после установки не проверяется.

## 2. Компоненты

| Слой | Где |
|------|-----|
| UI / SSH | `DeployEngine.kt`, `DeployInstallEnv.kt`, `DeployIssue.kt`, `DeployPreflight.kt` |
| Каталог версий | `DeployVersionCatalog.kt`, VPS `/health.latestDeployVersion` |
| Fetch | `server/fetch-and-install.sh` |
| Install | `server/install.sh` + `install-lib/*` |
| Образ | `server/Dockerfile`, pack/index/layer-cache |
| Runtime | один контейнер Compose, isolated bridge, `cap_drop ALL` |
| Control plane in-image | `provision` :9100, telemetry :9200 |
| Хост-контроллер | **нет** (только bash/python) |

## 3. Наиболее рискованные места

1. Окно после `stop_owned_stack` и до успешного `compose up`: стек down, reboot не поднимает ARDTT.
2. `rm -rf current` + `cp -a` — не атомарно; crash оставляет пустой/частичный `current/`.
3. `data/DEPLOY_VERSION` и корневой `.env` пишутся **до** stop (новые значения на ещё живом старом стеке).
4. Auto-rollback не ждёт readiness восстановленного стека.
5. `ardtt_cleanup_ardtt_leftovers` делает `rm -f install.lock` при живом `flock` — второй installer может взять новый inode.
6. Каскад: exit ставится первым; падение entry не откатывает exit.
7. Отмена на телефоне рвёт SSH, VPS-скрипт продолжает.
8. Provision `:9100` и upload `:9200` слушают `0.0.0.0` без auth (`/v1/users` отдаёт ключи).
9. Fallback `releases/latest/download/SHA256SUMS-server.txt` может попасть на APK-only latest tag.
10. Нет persistent phase: повтор после kill всегда с начала (кроме `.partial` и layer cache).

## 4. Race conditions

- Два телефона на один VPS: `flock -n` → `BUSY` (если lock-файл не unlink-нули).
- Disk cleanup unlink `install.lock` vs flock.
- `DeployEngine` — один job на процесс APK; второй VPS с другого телефона не сериализуется кроме flock.
- Каталог версий APK (`per_page=30`) vs VPS (`per_page=40`) vs кэш provision 1 ч.
- Last `ARDTT_ERROR` wins; `DONE` после ERROR не очищает ошибку.
- Compose `.env` vs экспортированные с телефона порты — уже закрыто (`env -i`).

## 5. Потеря работоспособности VPS

Не трогаются: `/opt/ardtt/data`, чужие контейнеры, рабочий Docker Engine, hostnet, firewall.

Ломается сервис ARDTT (не диск): kill/reboot в окне stop→up; первый install без `previous/` при падении compose; restore compose up fail; `ROLLBACK_FAILED` не выделен как код.

## 6. Rollback сейчас

- Авто: `restore_previous_release` если есть `previous/docker-compose.yml` (файл, не `[ -d ]`).
- Ручной: `ARDTT_ACTION=rollback` + `wait_readiness` + `ARDTT_DONE|rollback=1`.
- Android **не** вызывает rollback.
- `instance.json` пишется только после readiness; pending — до stop.
- First install: откатывать некуда.

## 7. Данные, которые нельзя потерять

`/opt/ardtt/data` (`users.json`, ключи AWG, warp, cascade). `logs/` по умолчанию. Не в tar-пакете. Bind-mount `/data`.

## 8. Android и протокол

Префиксы обязательны. JSON не ломает парсер. Новый сервер **обязан** продолжать `ARDTT_*` до снятия APK ≤0.5.264.

`ARDTT_DONE` не обязателен для успеха (exit 0). Без DONE порты на карточке не обновятся (auto-ports drift).

Не передаётся `ARDTT_GITHUB_TOKEN`.

## 9. Что уже есть из целевой архитектуры

Partial deploy, layer cache, outer SHA-256, safe extract, flock, `previous/`, pending instance, verify-before-stop, isolated compose, Engine-reuse, labels owner, disk preflight, port skip, amd64/arm64, no docker.sock, no git clone, no GHCR on VPS.

Нет: host `ardttctl`, versioned state, resume, atomic `current`, dual protocol, functional health, signed artifacts, bootstrap/app split, management-plane auth.

## 10. Что переделывать / что оставить

**Делать сейчас (надёжность):**

- `ardttctl` (Go) на хосте: emit / status / diagnose / state / lock helpers.
- Dual protocol (JSONL protocol=2 + `ARDTT_*`).
- Persistent `state/deploy.json` (atomic rename).
- Не unlink `install.lock` во время cleanup.
- Атомарный `current` → symlink на `releases/<ver>` + snapshot через dereference.
- `data/DEPLOY_VERSION` только на COMMIT.
- После auto-rollback — `wait_readiness`; код `ROLLBACK_FAILED` если не поднялось.
- Не бампать Engine на обычном update (уже так).

**Оставить:** bash fetch/install как engine до Phase 4; монолитный контейнер; GitHub Releases + SHA-256; partial layers; Android SSH-триггер; Compose.

**Не сейчас:** OCI/GHCR primary, Sigstore (только точки расширения в state: `imageDigest`), split data/control plane, закрытие 9100 вслепую (Android `ProvisionAdminApi` живёт на публичном HTTP), Kubernetes.

## 11. Архитектура ardttctl

Хост-бинарник в **hostfiles** (рядом с `install.sh`), static Go `linux/{amd64,arm64}`.

```
ardttctl emit progress|info|warn|error|done   # оба протокола на stdout
ardttctl status                                 # JSONL + краткий ARDTT_INFO
ardttctl diagnose                               # без секретов
ardttctl state get|set
ardttctl health                                 # docker inspect + ready.sh если контейнер up
```

`install.sh` / `fetch-and-install.sh` вызывают `./ardttctl` если executable, иначе старый `echo ARDTT_*`.

State: `/opt/ardtt/state/deploy.json` (schemaVersion=1). Phases: `idle|fetch|verify|stage|preflight|start|health|commit|rollback|rollback_failed|recovery_required`.

Lock: существующие `fetch.lock` / `install.lock` (flock). Cleanup **не** удаляет lock-файлы. `ardttctl` не берёт отдельный lock в этом срезе.

Версии (разделены в state, не смешивать с APK versionName):

- `deployVersion` — `DEPLOY_VERSION`
- `imageDigest` — Docker image Id после load
- `controlProtocolVersion` = 2
- `configSchema` / `dataSchema` — зарезервированы (`1` пока миграций данных нет)

## 12. Миграция без downtime

1. Старый APK + новый installer: JSONL игнорируется, `ARDTT_*` работают.
2. Новый APK + старый installer: только `ARDTT_*`.
3. Обновление 1.0.53 → 1.0.54: обычный fetch; после stop `current/` (dir) мигрирует в `releases/<id>` и становится atomic symlink. Окно downtime как сейчас (~секунды compose).
4. Откат 1.0.54 → previous (тот же immutable release, не копия дерева) переставляет `current`. Следующий update снова atomic rename.
5. OCI / подписи / split контейнера — отдельные фазы после измерений.

## 13. Compatibility matrix

| APK | Server | Поведение |
|------|--------|-----------|
| OLD (≤0.5.263, `ardtt-stack-*`) | 1.0.54 | Не ставит (уже так для 1.0.52+) |
| 0.5.264 | 1.0.53 | Текущий production |
| 0.5.264 | 1.0.54 | JSONL в логе, `ARDTT_*` как сейчас, деплой работает |
| будущий APK с parser v2 | 1.0.53 | Только `ARDTT_*` |
| будущий APK | 1.0.54 | JSONL + `ARDTT_*`; прогресс из любого |

## 14. Файлы этого среза

Добавить:

- `server/ardttctl/` (Go module)
- `scripts/build-ardttctl.sh`
- `scripts/test-ardttctl.sh`
- `docs/superpowers/specs/2026-09-15-ardttctl-deploy-design.md` (этот файл)

Изменить:

- `server/install.sh` — emit через ardttctl, switch/snapshot, commit `data/DEPLOY_VERSION`, rollback wait
- `server/install-lib/disk-cleanup.sh` — не rm lock
- `server/install-lib/uninstall.sh` — restore совместим с symlink `current`
- `scripts/pack-server-package.sh`, `repack-server-host-files.sh`, `build-server-index.py`, `check-deploy-bundle.sh`
- `server/DEPLOY_VERSION` → **1.0.54**, APK fallback, CHANGELOG, DEPLOY.md
- Android: опциональный разбор JSONL в `DeployEngine` (не ломает `ARDTT_*`)

## 15. План тестирования

- `go test` / `go vet` `server/ardttctl`
- Dual emit: JSONL + `ARDTT_PROGRESS` / `ERROR` с `code=`
- Atomic state: crash mid-write оставляет предыдущий файл
- Snapshot pointer vs directory; atomic rename; no `cp -a` of the live tree
- `test-install-rollback.sh` + wait/ROLLBACK_FAILED; pointer restore
- `test-install-pointers.sh` / `test-install-gc.sh` / `test-disk-budget.sh`
- `test-install-unpack.sh` dry-run
- Isolation: `install.lock` не в `rm` disk-cleanup; нет docker prune/volume prune
- Corrupt state → `recovery_required`, directory fsync
- `check-deploy-bundle.sh` целиком
- Android unit: JSON-строка не fail; protocol=2 progress обновляет шаг; `INSUFFICIENT_DISK`

Не в этом PR: live compose на amd64 (уже есть job), OCI, подписи, functional UDP health, закрытие 9100.

## Решения, которые расходятся с буквальным ТЗ

| ТЗ | Решение | Почему |
|-----|---------|--------|
| Сразу заменить shell engine | Нет, обёртка | Нельзя сломать 0.5.264 |
| OCI primary | Отложить | Сначала окно stop→up и state |
| Functional health до COMMIT | Пока ready.sh + Restarting/OOM | UDP handshake в install удлинит downtime; отдельная фаза |
| Закрыть 9100/9200 | Не закрывать | `ProvisionAdminApi` / Testing зависят; нужен отдельный auth-дизайн |
| Sigstore | Только поле `imageDigest` | Нет ключей/инфры; фейковая подпись хуже SHA-256 |
| Разбить контейнер | Нет | Общий netns/TUN; больше риска, чем выгоды |
| Kubernetes | Нет | Один/два VPS |
