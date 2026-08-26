# nonameVPN

Android VPN на **AmneziaWG 2.0** с автоматическим обходом (TURN / RAW), если до VPS нет прямой видимости. Обход под капотом; пользователю можно предложить только вход через VK для белых списков.

Архитектура и решения: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Идея

```
прямо видно VPS?  →  AmneziaWG 2.0
иначе             →  WRAP + TURN + RAW (без DTLS и без WG)
```

База клиента — форк AmneziaWG Android; bypass — адаптация WDTT/qWDTT.

## Статус

Проектирование. Код ещё не начат.
