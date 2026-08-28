# Режим тестирования и телеметрия

Скрытый режим полного сбора телеметрии для отладки nonameVPN на Android.

## Включение

1. **Настройки** → разблокировать режим администратора (PIN).
2. Включить переключатель **«Тестирование»**.
3. Появится вкладка **«Тестирование»** в нижней навигации.

Вкладка и запись доступны только при активном режиме администратора и включённом тестировании.

## Интерфейс вкладки «Тестирование»

- Список сохранённых логов: имя файла, дата начала, длительность, размер.
- У каждой записи: **Отправить** и **Удалить**.
- При отправке — тонкий прогресс-бар с процентами (до 3 повторов при ошибке сети, таймаут 30 с).
- Кнопка **«Начать запись»** / **«Остановить запись»** — в том же стиле, что и «Подключить» на экране туннеля.

## Визуальный индикатор записи

Во время записи по периметру всего Compose-интерфейса (включая диалоги внутри `MainActivity`) отображается пульсирующая рамка:

- цвет: тёмно-красный (`#8B0000`);
- толщина: 2.5 px;
- анимация: opacity 1.0 ↔ 0.5, ~1 Гц;
- при остановке — мгновенно исчезает.

> **Ограничение:** отдельные Activity (например, WebView-вход VK) не обёрнуты Compose-оверлеем. Для них lifecycle-события всё равно пишутся в лог.

## Формат логов

Используется **JSONL** (одна JSON-строка на событие). Расширение файла — `.json` (как в ТЗ).

```json
{"timestamp":1712345678000,"event_type":"touch","session_id":"uuid","data":{"screen":"tunnel","x":120.5,"y":340.0,"element_id":null}}
```

Типы событий: `touch`, `navigation`, `scroll`, `network`, `error`, `system`, `lifecycle`.

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

### Имя файла

```
{client_id}_{app_version}_{build_number}_{server_ip}_{start_timestamp}_{end_timestamp}.json
```

Пример:

```
client_a1b2c3d4e5f6_0.4.0-phone-integrate_4_192.168.1.100_1712345678_1712345987.json
```

При ротации (>100 MB) добавляется суффикс `_partN`.

### Локальное хранение

```
/data/data/com.nonamevpn.app/files/logs/
```

Запись буферизуется (flush каждые ~40 событий), запись идёт в фоне (`Dispatchers.IO`).

## Отправка на сервер

**POST** `multipart/form-data` на:

- по умолчанию: `http://{server_ip}:9200/api/upload-log` (IP из импортированного профиля);
- или явно через `BuildConfig.TELEMETRY_UPLOAD_URL` в `android/app/build.gradle.kts`.

Поля формы:

| Поле | Описание |
|------|----------|
| `file` | JSONL-файл лога |
| `client_id` | идентификатор устройства |

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
  collectors/               — device + network snapshots
ui/
  admin/TestingScreen.kt    — вкладка «Тестирование»
  telemetry/TelemetryRecordingOverlay.kt — рамка + touch/scroll
```

## Замечания по ТЗ

1. **JSONL вместо JSON-массива** — удобнее для потоковой записи и не держит весь файл в памяти.
2. **Рамка поверх системных панелей** — в рамках обычного Activity без `SYSTEM_ALERT_WINDOW` рисуется только над контентом приложения; для overlay поверх status bar нужно отдельное разрешение.
3. **HTTP не из OkHttp** (нативный go_client, VPN) в этот перехват не попадает — только Kotlin/OkHttp-вызовы (VK API и т.п.).
