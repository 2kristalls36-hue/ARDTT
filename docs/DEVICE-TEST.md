# Проверка клиента на белом списке оператора

Белый список здесь — **сеть оператора** (Yandex `77.88.8.8` жив, Cloudflare `1.1.1.1` режется), не список приложений split-tunnel. Классификация Auto: [ARCHITECTURE.md](ARCHITECTURE.md).

Облачный агент Cursor не видит USB на вашем столе. Живой контур — отдельное приложение **ARDTT Lab**: телефон сам выходит на промежуточный сервер по SSH, агент заходит туда же. **ARDTT для этого не перенастраивается.** Подробности и команды: [LAB.md](LAB.md).

## Как проверить БС, не трогая ARDTT

1. Поставьте обычный ARDTT и отдельно Lab (`:lab`).
2. Wi‑Fi **выкл.**, LTE вкл., SIM на белом списке. Если Wi‑Fi оставить, ARDTT как всегда возьмёт Direct — Lab это не чинит и не должен.
3. В Lab введите SSH сервера и нажмите **Подключить**.
4. Агент: `./scripts/ardtt-lab --ssh user@хост attach` и дальше `status` / `probe` / `screenshot` / `launch`.
5. В ARDTT — обычный сценарий: Auto, подключить, смотреть обход. Lab только показывает сеть и экран.

Lab биндит свой SSH на сотовую сеть, поэтому канал к агенту не зависит от туннеля ARDTT и не требует исключений в split-tunnel.

## Что уже можно без телефона

Юнит-тесты зонда (`NetworkProbeClassifyTest`, `NetworkRecoveryPolicyTest`, `HideIpPolicyTest`) проверяют классификатор, не DPI оператора.

```bash
cd android
./gradlew :app:testDebugUnitTest --tests com.ardtt.app.core.NetworkProbeClassifyTest
```

## Телеметрия без Lab

Режим тестирования в ARDTT по-прежнему шлёт JSONL на VPS — [TELEMETRY.md](TELEMETRY.md). Это запись после факта, не управление в реальном времени.

## USB (если агент крутится на том же ПК)

Кабель даёт `adb` только машине, где он воткнут. USB-модем включать нельзя. Снимок:

```bash
./scripts/device-bs-dump.sh --phase underlay
./scripts/device-bs-dump.sh --phase tunnel
```

Облачной ВМ этот кабель не виден. Для облачного агента нужен Lab, не USB.

## Чеклист сессии на БС

1. Wi‑Fi выкл., LTE вкл.
2. Lab подключён, `ardtt-lab status` видит `wifi_on=false` и оператора.
3. `ardtt-lab probe` — Yandex ok, Cloudflare fail (`looks_like_whitelist=true`).
4. ARDTT Auto, без «только прямое». На экране туннеля — обход.
5. Подключить. Path B. Скрин через Lab, если нужно глазами.
