# Переезд в ARDTT — два шага

## Шаг 1 — очистить репозиторий и подготовить к переезду

Удаляет **всё** текущее содержимое `main` на GitHub и заменяет одним коммитом-заготовкой:

- `README.md` — «репозиторий готовится к публикации»
- `LICENSE` — GPL-3.0
- `docs/assets/ardtt-icon.png` — иконка
- `.gitignore` — базовый для будущего кода

### Windows (PowerShell)

```powershell
cd C:\Users\a.shalaginov\nonameVPN
git pull origin cursor/ardtt-public-structure-c6c0

Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\prepare-ardtt-repo.ps1
```

Без вопроса подтверждения: `.\scripts\prepare-ardtt-repo.ps1 -Yes`

### Git Bash

```bash
bash scripts/prepare-ardtt-repo.sh
```

### Проверка

Откройте https://github.com/2kristalls36-hue/ARDTT — только README-заготовка, без старого кода.

---

## Шаг 2 — полный переезд (позже)

```powershell
.\scripts\push-ardtt-initial.ps1
```

Зальёт полное дерево: `android/`, `server/`, документацию, скрипты.

---

## nonameVPN

Черновой репозиторий **nonameVPN** не меняется. После шага 2 его можно архивировать.
