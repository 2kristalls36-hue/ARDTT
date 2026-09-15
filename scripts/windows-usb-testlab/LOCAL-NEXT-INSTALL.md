# Сейчас на OnePlus всё ещё 0.5.264 / 51cf7ce

Переустановка в сессии `20260915T075437Z-S00-a325b83` **не сменила байты**.
`lastUpdateTime` и новый путь пакета не значат новый билд.

На устройстве:

- `versionName` **0.5.264**, `versionCode` **283**
- SHA-256 APK `c6e9a361d1f12a6d943c9babbfdefdb37827f95c9d18a07a1e76251785ea20fd`
- имя артефакта **`51cf7ce`** (Preview PR 217)

Это **тот же файл**. GitHub `v0.5.264` и этот Preview имеют один `versionCode` 283 — Android не считает это обновлением.

Нужный файл — **другой SHA в имени артефакта и другой versionCode**:

| | Стоит сейчас | Ставить |
|---|---|---|
| versionName | 0.5.264 | **0.5.265** |
| versionCode | 283 | **284** |
| артефакт | `ardtt-0.5.264-51cf7ce-arm64-v8a` | **`ardtt-0.5.265-3f14a8c-arm64-v8a`** |
| SHA-256 | `c6e9a361…` | **`8ba45dd66a13bafeb4faec3ee8a114a0d31c35e0c94a06141637929ca34d0642`** |
| run | 34856621087 | **[34944525811](https://github.com/2kristalls36-hue/ARDTT/actions/runs/34944525811)** |

Не `releases/latest`, не `app-arm64-v8a-release.apk` из старой папки, не debug.

```powershell
git fetch origin auto/windows-usb-testlab-85a8
git checkout auto/windows-usb-testlab-85a8
git pull --ff-only origin auto/windows-usb-testlab-85a8
# HEAD должен быть 54e784f9 или новее

cd scripts\windows-usb-testlab
Set-ExecutionPolicy -Scope Process Bypass
.\Fetch-PreviewApk.ps1
# ожидаемый путь:
# %LOCALAPPDATA%\ARDTT-TestLab\apk\ardtt-0.5.265-3f14a8c-arm64-v8a.apk
# sha256 = 8ba45dd66a13bafeb4faec3ee8a114a0d31c35e0c94a06141637929ca34d0642

.\Install-ARDTT.ps1 -ApkPath "$env:LOCALAPPDATA\ARDTT-TestLab\apk\ardtt-0.5.265-3f14a8c-arm64-v8a.apk"
```

После install `dumpsys package com.ardtt.app` должен показать **versionName=0.5.265 versionCode=284**.
Если снова 0.5.264 / 283 — это снова 51cf7ce, не продолжать S01–S12 на этом пакете.

Не uninstall. USB tethering выкл.
