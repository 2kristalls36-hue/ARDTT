# nonameVPN

VPN-клиент на **AmneziaWG 2.0** с автоматическим обходом через TURN (принцип WDTT / qWDTT), если до VPS нет прямой видимости. В обходе — **RAW**-туннель (IP без вложенного WireGuard).

Подробная схема, компоненты и открытые решения: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Идея в двух словах

```
прямо видно VPS?  →  AmneziaWG 2.0 (UDP)
иначе             →  TURN/DTLS + WRAP/RTP → RAW TUN на VPS
```

## Статус

Проектирование. Код ещё не начат.
