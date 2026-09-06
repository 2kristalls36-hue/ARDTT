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

На телефоне:

1. Поставьте Lab. ARDTT — как обычно, без особых настроек.
2. Для проверки БС **выключите Wi‑Fi**, оставьте LTE. Иначе ARDTT сам возьмёт Direct — это его обычное поведение, не баг Lab.
3. В Lab: сервер, порт SSH, пользователь, пароль, порт на сервере (`7422`).
4. **Подключить**. На сервере должен быть разрешён TCP forwarding (`AllowTcpForwarding` в sshd).
5. По желанию **Разрешить экран** — тогда агент может снять скриншот (в том числе экран ARDTT).

На сервере появляется `/tmp/ardtt-lab.port`. Канал слушает только localhost — из интернета порт не торчит.

Агент (эта машина или уже сессия на VPS):

```bash
./scripts/ardtt-lab --ssh user@vps status
./scripts/ardtt-lab --ssh user@vps probe --vps 45.129.2.3
./scripts/ardtt-lab --ssh user@vps launch
./scripts/ardtt-lab --ssh user@vps screenshot -o /tmp/phone.png
./scripts/ardtt-lab --ssh user@vps attach
```

Если вы уже на сервере: `./scripts/ardtt-lab status`.

Команды — одна JSON-строка на запрос: `ping`, `status`, `probe`, `launch_ardtt`, `screenshot`, `watch`. Пока туннель жив, Lab раз в ~3 с шлёт событие `status` всем подключённым агентам.

SSH с телефона идёт в LTE (`requestNetwork` + `bindSocket`), не в VPN ARDTT. Само приложение ARDTT об этом не знает.

На белом списке оператора TCP до IP VPS (в том числе `:22`) часто жив. Если `:22` закрыт, поставьте SSH на другой порт и укажите его в Lab.
