# Документация ARDTT

Обзор продукта, состав репозитория и текущие версии — в корневом [README.md](../README.md) (клиент и VPS-стек в одном дереве). Заметки линейки 0.5.267 — [CHANGELOG.md](../CHANGELOG.md).

| Документ | Содержание |
|----------|------------|
| [LEGEND.md](LEGEND.md) | Имя **ARDTT** (Amnezia & Raw Dial over TURN Tunnel), Path A/B, знак AR/DTT |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Схемы, probe, каскад, Hide-IP WARP, VpnService |
| [DIAGNOSIS-TUNNEL-DROP.md](DIAGNOSIS-TUNNEL-DROP.md) | Почему туннель обрывается сам: сторож и handover клиента, Go-обход, каскад и супервизор |
| [DEPLOY.md](DEPLOY.md) | Стек **1.0.54**: localhost bind + Bearer; `ardttctl` / atomic current; архив `ardtt-server-*-linux-<arch>.tar.gz` |
| [TELEMETRY.md](TELEMETRY.md) | Режим тестирования и формат логов |
| [UI.md](UI.md) | Дизайн-система клиента: реестр компонентов и токенов, карта экранов, режимы |
| [UI-AUDIT.md](UI-AUDIT.md) | Аудит интерфейса: находки, исправления, отложенное |
| [android/README.md](../android/README.md) | Сборка клиента 0.5.267, keystore, GitHub Releases |
| [server/README.md](../server/README.md) | Единый контейнер `ardtt`: provision, direct, bypass, dns, warp, cascade, telemetry |

Бренд-исходник иконки: квадрат [assets/brand/ardtt-icon-source.png](assets/brand/ardtt-icon-source.png), круг [assets/brand/ardtt-icon-round-source.png](assets/brand/ardtt-icon-round-source.png).
