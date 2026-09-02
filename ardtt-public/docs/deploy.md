# Деплoy на VPS

Два способа: **из Android** (SSH) или **вручную** (Compose / install.sh).

## Способ 1: Docker Compose (ручной)

```bash
git clone https://github.com/2kristalls36-hue/ARDTT.git
cd ARDTT/server
cp .env.example .env
nano .env   # NVPN_PUBLIC_HOST

docker compose up -d --build
./scripts/create-user.sh myuser
```

## Способ 2: Из приложения (admin)

1. Настройки → разблокировать **режим администратора**.
2. Вкладка **Серверы** → добавить VPS (SSH host, user, key/password).
3. Указать `NVPN_PUBLIC_HOST`, порты.
4. **Установить на VPS** → загрузка бандла и `install.sh`.

### Что происходит на VPS

```
Android DeployEngine
    │ SSH
    ▼
/opt/nonamevpn/
    install.sh
    stack/              ← распакованный server/ (compose + sources)
    stack/data/         ← users.json, keys (persistent)
```

Install script:

1. Распаковывает `stack.tar.gz`
2. `docker compose up -d --build`
3. Протокол прогресса: `NVPN_PROGRESS`, `NVPN_DONE`, `NVPN_ERROR`

Каталог `/opt/nonamevpn` — историческое имя; переменная `NVPN_INSTALL_DIR` переопределяет путь.

### Проверка версии

```bash
curl -s http://YOUR_VPS:9100/health
```

Ответ содержит `deployVersion` — сравнивается с `DEPLOY_VERSION` в APK assets.

## Deploy-бандл в APK

Сборка APK автоматически упаковывает сервер:

```bash
./scripts/pack-deploy-assets.sh
```

Содержимое assets:

- `deploy/stack.tar.gz.bin` — tar server/
- `deploy/install.sh`
- `deploy/DEPLOY_VERSION`

## Порты firewall

| Порт | Протокол | Сервис |
|------|----------|--------|
| 51820 | UDP | Direct (AmneziaWG) |
| 56003 | UDP | Bypass (RAW) |
| 9100 | TCP | Provision /health |
| 9200 | TCP | Telemetry (optional) |

## Обновление стека

```bash
cd /opt/nonamevpn/stack   # или ваш NVPN_INSTALL_DIR/stack
docker compose pull       # если используете registry
docker compose up -d --build
```

Или повторный деплoy из приложения с новым APK.

## OTA для клиентов

На VPS дистрибуции (отдельно от VPN-сервера):

- `update.json` — manifest
- `ardtt-latest.apk` — бинарник
- Опционально: telemetry endpoint, release keystore

## Откат / удаление

```bash
cd /opt/nonamevpn/stack
docker compose down
# rm -rf /opt/nonamevpn   # полное удаление
```

## Логи установки

При ошибке деплоя из приложения: `/opt/nonamevpn/install.log` на VPS.
