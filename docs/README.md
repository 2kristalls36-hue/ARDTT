# Документация ARDTT

Обзор продукта, состав репозитория и текущие версии — в корневом [README.md](../README.md) (клиент и VPS-стек в одном дереве). Заметки линейки 0.5.263 — [CHANGELOG.md](../CHANGELOG.md).

| Документ | Содержание |
|----------|------------|
| [LEGEND.md](LEGEND.md) | Имя **ARDTT** (Amnezia & Raw Dial over TURN Tunnel), Path A/B, знак AR/DTT |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Схемы, probe, каскад, Hide-IP WARP, VpnService |
| [DEPLOY.md](DEPLOY.md) | Стек **1.0.52**: архив `ardtt-server-*-linux-<arch>.tar.gz`; Engine в `vendor/docker.tgz`; частичный деплой по индексу релиза |
| [TELEMETRY.md](TELEMETRY.md) | Режим тестирования и формат логов |
| [UI.md](UI.md) | Дизайн-система клиента: токены, нейминг, каркас экрана |
| [android/README.md](../android/README.md) | Сборка клиента 0.5.263, keystore, GitHub Releases |
| [server/README.md](../server/README.md) | Единый контейнер `ardtt`: provision, direct, bypass, dns, warp, cascade, telemetry |

Бренд-исходник иконки: квадрат [assets/brand/ardtt-icon-source.png](assets/brand/ardtt-icon-source.png), круг [assets/brand/ardtt-icon-round-source.png](assets/brand/ardtt-icon-round-source.png).
