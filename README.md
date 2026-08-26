# nonameVPN

Android VPN на **AmneziaWG 2.0** с обходом (TURN/RAW) и опциональным выходом через WARP (`wireproxy`→tun2socks). На VPS — три контейнера с общим `host_id` в разных подсетях.

Архитектура и решения: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Идея

```
прямо видно VPS?  →  AmneziaWG 2.0
иначе             →  WRAP + TURN + RAW (без DTLS и без WG)
```

База клиента — форк AmneziaWG Android; bypass — адаптация WDTT/qWDTT.

## Статус

Проектирование. Код ещё не начат.
