# Режим тестирования и телеметрия

Режим полного сбора телеметрии для отладки ARDTT на Android. Переключатель живёт в **общих** Настройках (не за PIN администратора).

## Включение

1. **Настройки** → **«Режим тестирования»** (после принятия соглашения; доступно в любом режиме UI).
2. Появится вкладка **«Тест»** в нижней навигации.

## Интерфейс вкладки «Тестирование»

- Список сохранённых логов: имя файла, дата начала, длительность, размер.
- У каждой записи: **Отправить** и **Удалить**.
- Перед отправкой обязательно вводится комментарий: что произошло и что ожидалось.
  Комментарий встраивается непосредственно в JSONL как событие `user_comment`.
- При отправке — тонкий прогресс-бар с процентами (до 3 повторов при ошибке сети, таймаут 30 с).
- Кнопка **«Начать запись»** / **«Остановить запись»** — в том же стиле, что и «Подключить» на экране туннеля.

## Визуальный индикатор записи

Во время записи по периметру всего Compose-интерфейса (включая диалоги внутри `MainActivity`) отображается пульсирующая рамка:

- цвет: ярко-красный (`#FF3B30`);
- толщина: 6 dp;
- анимация: opacity 1.0 ↔ 0.72, ~1 Гц;
- при остановке — мгновенно исчезает.

> **Ограничение:** отдельные Activity (например, WebView-вход VK) не обёрнуты Compose-оверлеем. Для них lifecycle-события всё равно пишутся в лог.

## Формат логов

Используется **JSONL** (одна JSON-строка на событие). Расширение файла — `.json` (как в ТЗ).

```json
{"timestamp":1712345678000,"event_type":"touch","session_id":"uuid","data":{"screen":"tunnel","x":120.5,"y":340.0,"element_id":null}}
```

Типы событий: `user_comment`, `touch`, `navigation`, `scroll`, `network`,
`error`, `app_log`, `deploy`, `system`, `lifecycle`.

### Что собирается

| Категория | Содержимое |
|-----------|------------|
| **system** | Модель, ОС, экран, RAM/ROM, батарея; снимки каждые 30 с |
| **network** (снимки) | Wi‑Fi SSID/BSSID/RSSI, сотовая сеть, IP устройства |
| **network** (HTTP) | URL, метод, заголовки, тело запроса/ответа (до 16 KB), код, время |
| **touch** | Экран, X/Y, timestamp |
| **navigation** | from → to между вкладками |
| **scroll** | Свайпы/скроллы (dx/dy) |
| **lifecycle** | onCreate/onResume/onPause/onDestroy, фон/передний план |
| **error** | Исключения + stack trace |
| **app_log** | Внутренние события VPN, подключения, trusted Wi‑Fi, WARP и фоновых сервисов |
| **deploy** | Старт, SSH, версия архива, прогресс, полный вывод install/Compose/uninstall, exit code, итог и удалённый install.log при ошибке установки |

Пароли, приватные ключи, токены, Cookie/Authorization и PEM-блоки
маскируются до записи. Длинные строки и stack trace ограничиваются по размеру.

### Имя файла

```
{client_id}_{app_version}_{build_number}_{server_ip}_{start_timestamp}_{end_timestamp}.json
```

Пример:

```
client_a1b2c3d4e5f6_1.0.0_4_192.168.1.100_1712345678_1712345987.json
```

При ротации (>100 MB) добавляется суффикс `_partN`.

### Локальное хранение

```
/data/data/com.ardtt.app/files/logs/
```

Запись буферизуется (flush каждые ~40 событий), запись идёт в фоне (`Dispatchers.IO`).

## Отправка на сервер

**POST** `multipart/form-data` на:

- **POST** `multipart/form-data` на `https://45.129.2.3/api/upload-log` (или явно через `BuildConfig.TELEMETRY_UPLOAD_URL` в `android/app/build.gradle.kts`).

Поля формы:

| Поле | Описание |
|------|----------|
| `file` | JSONL-файл лога |
| `client_id` | идентификатор устройства |

Комментарий не является отдельным полем multipart: он уже находится внутри
`file`, поэтому не потеряется при скачивании или переносе лога.

## Серверный приёмник

Сервис `telemetry` в Docker Compose:

```bash
cd server
docker compose up -d telemetry
curl -s http://127.0.0.1:9200/health
```

Файлы сохраняются в:

```
/var/logs/app/{client_id}/{original_filename}.json
```

### Очередь разбора и отметка «прочитан»

Непрочитанные логи (команды выполняются на VPS):

```bash
curl -s 'http://127.0.0.1:9200/api/logs?status=unread' | jq
```

После фактического разбора файла Cursor-агент обязан пометить его прочитанным:

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"processed_by":"cursor-agent","note":"краткий итог разбора"}' \
  'http://127.0.0.1:9200/api/logs/{client_id}/{filename}.json/read'
```

Рядом с логом создаётся скрытый sidecar
`.{filename}.json.read.json` с `processed_at`, `processed_by` и итоговой
заметкой. Сам лог не изменяется после загрузки. Повторная загрузка файла с тем
же именем сбрасывает отметку и возвращает его в непрочитанные.

Review API по умолчанию доступен только через localhost. Для удалённого доступа
нужно установить `TELEMETRY_REVIEW_TOKEN` и передавать
`Authorization: Bearer <token>`.

Локальный запуск без Docker:

```bash
cd server/telemetry-upload
pip install -r requirements.txt
TELEMETRY_LOG_ROOT=./logs python app.py
```

## Анализ логов на сервере

```bash
# Список клиентов
ls /var/logs/app/

# Новые файлы за сегодня
find /var/logs/app -name '*.json' -mtime -1

# Скачать на рабочую машину
scp user@vps:/var/logs/app/client_xxx/*.json ./

# Просмотр событий
jq -c . client_xxx_....json | head
jq 'select(.event_type=="error")' client_xxx_....json
jq 'select(.event_type=="network")' client_xxx_....json
```

Для JSONL каждая строка — отдельный JSON-объект; `jq` читает их построчно.

## Разрешения Android

Для полных сетевых снимков запрашиваются (при открытии вкладки):

- `ACCESS_FINE_LOCATION` — SSID Wi‑Fi на Android 10+;
- `READ_PHONE_STATE` — оператор и уровень сотового сигнала.

Без разрешений соответствующие поля в логе будут `null` или с пометкой об отказе.

## Настройка URL загрузки

В `android/app/build.gradle.kts`:

```kotlin
buildConfigField("String", "TELEMETRY_UPLOAD_URL", "\"https://example.com/api/upload-log\"")
```

Пустая строка — авто-URL из IP профиля (`:9200`).

## Архитектура (клиент)

```
telemetry/
  TelemetryRecorder.kt      — сессия записи, канал событий, ротация
  TelemetryFileManager.kt   — имена файлов, список/удаление
  TelemetryUploadClient.kt  — POST + прогресс + retry
  AppHttpClient.kt          — OkHttp + перехват HTTP
  TelemetryBridge.kt        — AppLog/deploy → активная запись
  TelemetryRedactor.kt      — удаление секретов и ограничение строк
  collectors/               — device + network snapshots
ui/
  admin/TestingScreen.kt    — вкладка «Тестирование»
  telemetry/TelemetryRecordingOverlay.kt — рамка + touch/scroll
```

## Замечания по ТЗ

1. **JSONL вместо JSON-массива** — удобнее для потоковой записи и не держит весь файл в памяти.
2. **Рамка поверх системных панелей** — в рамках обычного Activity без `SYSTEM_ALERT_WINDOW` рисуется только над контентом приложения; для overlay поверх status bar нужно отдельное разрешение.
3. **HTTP не из OkHttp** (нативный go_client, VPN) в этот перехват не попадает — только Kotlin/OkHttp-вызовы (VK API и т.п.).
