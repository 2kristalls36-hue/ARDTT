# Настройки GitHub-репозитория ARDTT

Скопируйте при создании https://github.com/2kristalls36-hue/ARDTT

## Repository name

```
ARDTT
```

## Description (About)

```
AmneziaWG- и RAW-туннель через медиарелей ВК TURN-серверов: Android-клиент и self-hosted VPS — прямое подключение или обход RAW Dial via TURN.
```

## Website (optional)

```
https://github.com/2kristalls36-hue/ARDTT#readme
```

## Topics

```
vpn
amnezia
amneziawg
turn
android
self-hosted
docker
kotlin
gpl-3
ardtt
```

## License

**GNU General Public License v3.0**

## Visibility

Public

## Initialize repository

**Не** добавляйте README, .gitignore или license при создании — они приходят из сборки.

## Сборка и первый push

Из каталога с исходниками (nonameVPN или ARDTT):

```bash
./scripts/assemble-ardtt-repo.sh /tmp/ARDTT-out
cd /tmp/ARDTT-out
git init
git add .
git commit -m "Initial public release of ARDTT"
git branch -M main
git remote add origin https://github.com/2kristalls36-hue/ARDTT.git
git push -u origin main --force   # заменит содержимое, если репо уже существует
```

## Что попадает в публичный репозиторий

| Из исходников | Из ardtt-public/ |
|---------------|------------------|
| `android/`, `server/`, `scripts/` | `README.md` |
| `LICENSE`, `NOTICE`, `.gitignore` | `docs/*.md` |
| `docs/assets/` | `.github/` |

**Не копируется:** черновые `docs/LEGEND.md`, `docs/ARCHITECTURE.md` и прочие internal docs из nonameVPN — заменены новой документацией.

## nonameVPN

Черновой репозиторий **nonameVPN** не трогаем. ARDTT — отдельный публичный репозиторий со своей документацией.

После публикации: nonameVPN → Archive + README «Проект: https://github.com/2kristalls36-hue/ARDTT».
