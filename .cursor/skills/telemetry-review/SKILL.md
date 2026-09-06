---
name: telemetry-review
description: Find, mark as read, analyze, and reply to ARDTT testing telemetry logs (обращение №N, unread test logs, вкладка История). Use whenever the user asks to look at a test log, an appeal number, or files sent from the Testing tab.
---

# Разбор логов тестирования ARDTT

Когда пользователь просит найти и разобрать лог с вкладки **Тест** / **История**,
работай по этому протоколу. Пользователь должен видеть прогресс в приложении:
сначала **Прочитано**, потом блок **Ответ автора**.

Полное описание API: [docs/TELEMETRY.md](../../../docs/TELEMETRY.md).

## Как найти файл

Review API по умолчанию только с VPS (`127.0.0.1:9200`). Снаружи — только с
`Authorization: Bearer $TELEMETRY_REVIEW_TOKEN`.

Если есть SSH на хост телеметрии (часто `45.129.2.3` / IP из профиля), выполняй
запросы там. Если доступа нет — скажи об этом и не выдумывай разбор.

```bash
# непрочитанные
curl -s 'http://127.0.0.1:9200/api/logs?status=unread'

# обращение №N
curl -s 'http://127.0.0.1:9200/api/logs/ticket/N'
```

В ответе: `client_id`, `filename`, `path`, `ticket`, `comment` (текст пользователя),
`read`, `reply`. Файл на диске: `/var/logs/app/{client_id}/{filename}` (JSONL).

Если пользователь назвал номер — ищи по `ticket`. Если «последний» / «непрочитанные»
— бери `status=unread`, самые новые первыми.

## Два шага, оба обязательны

### 1. Сразу пометить прочитанным

Как только файл найден и ты его открыл — до длинного анализа:

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"processed_by":"cursor-agent"}' \
  "http://127.0.0.1:9200/api/logs/${CLIENT_ID}/${FILENAME}/read"
```

В истории на телефоне статус меняется на **Прочитано**. Не передавай `reply`/`note`
на этом шаге: пустое поле не должно затирать будущий ответ, но и не должно
выглядеть как готовый разбор.

### 2. Когда разбор закончен — ответ пользователю

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"processed_by":"cursor-agent","reply":"краткий вывод на русском"}' \
  "http://127.0.0.1:9200/api/logs/${CLIENT_ID}/${FILENAME}/comment"
```

Этот текст попадает в карточку обращения как **Ответ автора**. Пиши по-русски,
коротко, без секретов из лога (пароли, ключи, cookie, токены). Не вставляй сырой
stack trace целиком — суть и что делать дальше.

`POST /comment` сам ставит «прочитан», если шаг 1 пропущен. Повторный `POST /read`
без `reply` уже записанный ответ не стирает.

## Анализ

JSONL: одна JSON-строка — событие. Смотри `event_type`: `error`, `user_comment`,
`network`, `deploy`, `app_log`, `lifecycle`. Комментарий пользователя уже есть в
хвосте файла и в поле `comment` API.

В ответе человеку назови **Обращение №N**, что сломалось и чем это подтверждается
в логе. Не утверждай, что пометка дошла до телефона, если `POST /read` или
`POST /comment` вернул ошибку.
