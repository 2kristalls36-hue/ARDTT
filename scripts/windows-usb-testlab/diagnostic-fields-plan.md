# Минимальный план диагностических полей (без смены маршрута)

Не внедрять в продукт, пока не согласован отдельно. Цель — отличить ошибку надписи, неверный physical network, мёртвый туннель при живом VpnService и смерть процесса.

Существующие logcat-якоря (контрольный SHA `a325b83e`, теги `ConnMgr` / `VpnTunnel`):

- `auto-stage path_decision` — series, net handle, sim, operator, age, scores, path_before/after, reason
- `Connect resolved` — use/liveMode/kind
- `vpn_stop_cmd`, `service_stopped`, `on_revoke`, `call_recreate`
- `auto-stage path_confirmed` / `path_confirm_timeout`

Этого мало, если UI говорит «через Wi-Fi», а оператор «МТС»: не видно, **какой Network** реально выбран для сокетов и откуда взялся kind.

Предлагаемые поля (только лог, без изменения таймаутов и выбора пути):

| Поле | Зачем |
|---|---|
| `attemptId` / `sessionEpoch` / `transportEpoch` | Склеить UI, service и native |
| `networkHandle` + `transport` (CELLULAR/WIFI/VPN) | Что выбрал ConnectivityManager |
| Caps: `INTERNET`, `NOT_VPN`, `VALIDATED`, `CAPTIVE_PORTAL`, `NOT_SUSPENDED` | Отличить captive от «живого» Wi-Fi |
| `wifiRadioEnabled` vs `wifiAssociated` vs `boundNetwork` | Radio on / no AP ≠ connected Wi-Fi |
| `kindSource` + `autoReason` | Откуда kind: probe, capabilities, last SSID |
| `whitelistNetworkKey` + `evidenceAgeMs` + `freshStrong` | БС привязаны к актуальному NetworkKey |
| `socketBind` / `processBind` | actual binding, не UI |
| `path` Direct/Bypass + `confirmStage` | Handshake vs user data |

Правила: отсутствующее поле не восстанавливать догадкой; не логировать cookie, token, call URL, private keys.
