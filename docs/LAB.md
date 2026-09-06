# ARDTT Lab — живое управление телефоном через SSH

Отдельное приложение `com.ardtt.lab`. **ARDTT не перенастраивается.** Lab само открывает SSH на промежуточный сервер и вешает реверс `127.0.0.1:7422`. Облачный агент заходит на тот же сервер по SSH и читает/командует в реальном времени.

```
агент ── ssh user@vps ──► 127.0.0.1:7422
                              ▲
                              │ SSH reverse (телефон)
                         ARDTT Lab
                              │ сокеты bind на LTE
                              ▼
                    интернет / белый список
         рядом стоит обычный ARDTT, без lab-режима
```

Сборка:

```bash
cd android
./gradlew :lab:assembleDebug
# android/lab/build/outputs/apk/debug/lab-debug.apk
```

Тестовый прыжок — `root@45.129.2.3` (пароль только на телефоне, в репозиторий не кладётся). На сервере: репозиторий `ssh://root@45.129.2.3/opt/repos/ARDTT.git`, рабочая копия `/opt/ardtt-lab`, страница https://45.129.2.3/lab/. Повторная подготовка: `sudo bash scripts/setup-lab-vps.sh` на VPS, затем push ветки `lab`.

На телефоне:

1. Поставьте Lab. ARDTT — как обычно, без особых настроек.
2. Для проверки БС **выключите Wi‑Fi**, оставьте LTE. Иначе ARDTT сам возьмёт Direct — это его обычное поведение, не баг Lab.
3. В Lab уже стоят сервер `45.129.2.3`, пользователь `root`, порт 22. Введите пароль SSH и нажмите **Подключить**.
4. На сервере должен быть разрешён TCP forwarding (`AllowTcpForwarding` в sshd).
5. По желанию **Разрешить экран** — тогда агент может снять скриншот (в том числе экран ARDTT).

На сервере появляется `/tmp/ardtt-lab.port`. Канал слушает только localhost — из интернета порт не торчит.

Агент (эта машина или уже сессия на VPS):

```bash
./scripts/ardtt-lab --ssh root@45.129.2.3 status
./scripts/ardtt-lab --ssh root@45.129.2.3 probe --vps 45.129.2.3
./scripts/ardtt-lab --ssh root@45.129.2.3 launch
./scripts/ardtt-lab --ssh root@45.129.2.3 screenshot -o /tmp/phone.png
./scripts/ardtt-lab --ssh root@45.129.2.3 attach
```

Если вы уже на сервере: `./scripts/ardtt-lab status`.

Команды — одна JSON-строка на запрос: `ping`, `status`, `probe`, `launch_ardtt`, `screenshot`, `watch`. Пока туннель жив, Lab раз в ~3 с шлёт событие `status` всем подключённым агентам.

SSH с телефона идёт в LTE (`requestNetwork` + `bindSocket`), не в VPN ARDTT. Само приложение ARDTT об этом не знает.

На белом списке оператора TCP до IP VPS (в том числе `:22`) часто жив. Если `:22` закрыт, поставьте SSH на другой порт и укажите его в Lab.
