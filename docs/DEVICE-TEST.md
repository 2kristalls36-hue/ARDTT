# Проверка клиента на белом списке оператора

Белый список здесь — **сеть оператора** (Yandex `77.88.8.8` жив, Cloudflare `1.1.1.1` режется), не список приложений split-tunnel. Классификация Auto: [ARCHITECTURE.md](ARCHITECTURE.md).

ARDTT для живой проверки **не перенастраивается**. Отдельный APK и SSH-контур лежат в приватном репозитории Lab, не в этом дереве.

## Без телефона

Юнит-тесты зонда (`NetworkProbeClassifyTest`, `NetworkRecoveryPolicyTest`, `HideIpPolicyTest`) проверяют классификатор, не DPI оператора.

```bash
cd android
./gradlew :app:testDebugUnitTest --tests com.ardtt.app.core.NetworkProbeClassifyTest
```

## Телеметрия

Режим тестирования в ARDTT шлёт JSONL на VPS — [TELEMETRY.md](TELEMETRY.md). Это запись после факта.

## USB (агент на том же ПК)

Кабель даёт `adb` только машине, где он воткнут. USB-модем включать нельзя.

```bash
./scripts/device-bs-dump.sh --phase underlay
./scripts/device-bs-dump.sh --phase tunnel
```

Облачной ВМ этот кабель не виден.

## Чеклист сессии на БС

1. Wi‑Fi выкл., LTE вкл.
2. ARDTT Auto, не «только прямое». Preselect — обход.
3. Подключить. Path B, не AWG UDP.
4. Сайт, который на underlay не открывался, открывается.
