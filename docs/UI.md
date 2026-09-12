# UI-слой клиента

Соглашения для `android/app/src/main/java/com/ardtt/app/ui`. Правило одно: значение,
имя или паттерн определяются в одном месте, экраны их только используют.

## Структура

```
ui/
├── AppRoot.kt / AppDestination.kt / PendingUiAction.kt / ArdttNavPlan.kt
├── AppCopy.kt                                            # общие пользовательские строки
├── ConnectionControls.kt                                 # запись настроек подключения
├── theme/          # переменные и палитра
│   ├── ArdttTokens.kt      ArdttSpacing · ArdttLayout · ArdttRadius · ArdttShapes
│   │                       ArdttElevation · ArdttSize · ArdttAlpha · ArdttMotion
│   │                       ArdttChrome
│   ├── ArdttColors.kt      светлая/тёмная схемы, семантические цвета, обои → палитра
│   ├── ArdttSurface.kt     единственная проверка «тёмная ли поверхность» и заливки
│   ├── ArdttBackdropTone.kt  цвета и тень текста поверх иллюстрированных обоев
│   ├── ArdttTypography.kt  Inter + шкала, ArdttTerminalTextStyle / ArdttTerminalLabelStyle
│   └── ArdttTheme.kt       ArdttTheme() и системные панели (без wallpaper-tint — он в AppRoot)
├── components/     # дизайн-система
│   ├── layout/     каркас: ArdttFeedScaffold, ArdttLazyFeedScaffold, ArdttScrollChrome,
│   │               ArdttPageHeader, ArdttNavigationBar,
│   │               ArdttBottomChrome, ArdttStickyBottomBar, ArdttPullRefresh, ArdttBackdrop,
│   │               ArdttDestinationRow
│   ├── surface/    контейнеры: ArdttSectionCard, ArdttSettingsCard, ArdttCompactCard,
│   │               ArdttDialog, ArdttConfirmDialog, ArdttBottomSheet, ArdttLinkShareDialog,
│   │               ArdttQrCode, ArdttTerminalCard, ArdttFloatingShell, ArdttSectionTitle,
│   │               ArdttLeadingIcon
│   ├── control/    ввод: ArdttButton, ArdttPrimaryButton, ArdttChoiceChip(+Row),
│   │               настроечные чипы, ArdttSwitchRow, ArdttCheckboxRow, ArdttSettingBlock,
│   │               ArdttTextField / ArdttPasswordField / ArdttDigitsField,
│   │               ArdttOverflowMenu, ArdttHaptics
│   └── feedback/   состояние: ArdttStatusChip/Pill/Dot, ArdttIpChip/ArdttIpHostRow,
│                   ArdttEmptyState/LoadingState/ErrorState, ArdttInlineFactRow,
│                   ArdttStackedFactRow, ArdttCopyRow, ArdttLinearProgress, ArdttPingDot
├── util/           ClipboardActions.kt — копирование, вставка, «Поделиться»
└── admin · settings · tunnel · profiles · exceptions · telemetry   # экраны
```

## Реестр компонентов

Единый список: прежде чем писать `Surface`, `OutlinedTextField`, `Switch`, `Checkbox`
или `Text(titleSmall, SemiBold)` на экране — проверить таблицу. Новый общий элемент
добавляется сюда и в структуру выше, а не остаётся приватным в экране.

| Компонент | Роль | Где используется |
|-----------|------|------------------|
| `ArdttButton` (`Primary` / `Tonal` / `Outlined` / `Text` / `Danger` / `Icon`) | все кнопки; `busy`, `enabled`, единый disabled | везде |
| `ArdttPrimaryButton` | полноширинный CTA (sticky) | Туннель, Профили, Клиенты, Тестирование |
| `ArdttChoiceChipRow` / `ArdttChoiceChip` | сегментный выбор одного из N (radio-семантика) | режим/адрес/тема (Настройки, Туннель), панели Тестирование и Обход, ЧС/БС |
| `PathModeChipRow`, `HideIpChipRow`, `DialPathChipRow`, `ThemeModeChipRow` | готовые ряды чипов над `ArdttChoiceChipRow` | Настройки, Туннель |
| `ArdttSwitchRow` | заголовок + подзаголовок + Switch, одна toggleable-нода | Настройки, Туннель, Деплой, Обход |
| `ArdttCheckboxRow` | Checkbox + подпись, одна toggleable-нода | соглашение тестирования, выбор серверов |
| `ArdttSettingBlock` | заголовок + подзаголовок + произвольный контрол | Настройки, Туннель |
| `ArdttTextField` / `ArdttPasswordField` / `ArdttDigitsField` | единственный текстовый ввод; пароль с показом; цифры с числовой клавиатурой | Деплой, Клиенты, Профили, Журнал, Тестирование, код звонка |
| `ArdttOverflowMenu` / `ArdttOverflowMenuItem` | меню ⋮ | Профили, Серверы, Журнал |
| `ArdttSectionCard` | базовая карточка | все ленты |
| `ArdttSettingsCard` | пресет карточки настроек (Large / SmallPlus) | Настройки |
| `ArdttCompactCard` | плотная карточка списка | Серверы, Клиенты, Профили |
| `ArdttSectionTitle`, `ArdttLeadingIcon` | заголовок блока, квадратная иконка | карточки, ряды |
| `ArdttDialog` | нижний лист с действиями | все диалоги |
| `ArdttConfirmDialog` | подтверждение необратимого / меняющего сессию действия | очистка журнала, удаление профиля/записи/клиента, отвязка устройства, выход из admin, выход из VK |
| `ArdttBottomSheet` | прокручиваемый лист без кнопок | добавление профиля, настройки клиента |
| `ArdttLinkShareDialog`, `ArdttQrCode` | ссылка + QR | профиль, сервер |
| `ArdttTerminalCard` | монотекст лога | прогресс деплоя |
| `ArdttFeedScaffold` / `ArdttLazyFeedScaffold` / `ArdttScrollChrome` | каркас экрана | все вкладки |
| `ArdttTabHeader` / `ArdttFeedHeader` / `ArdttPageHeader` | шапка | все вкладки |
| `ArdttNavigationBar` | нижняя панель | `AppRoot` |
| `ArdttStickyBottomBar`, `ArdttBottomChrome` | закреплённые действия снизу | Туннель, Профили, Клиенты, Диагностика, Тестирование |
| `ArdttDestinationRow` | строка-переход с шевроном | Настройки, Диагностика, Серверы |
| `ArdttPullRefresh` / `rememberPullRefresh` | обновление жестом | ленты |
| `ArdttEmptyState` / `ArdttLoadingState` / `ArdttErrorState` | состояния экрана | Профили, Обход, Клиенты, Журнал, Тестирование |
| `ArdttStatusChip` / `ArdttStatusPill` / `ArdttStatusDot` / `ArdttIpChip` / `ArdttIpHostRow` | статусы и адреса | списки |
| `ArdttInlineFactRow` / `ArdttStackedFactRow` / `ArdttCopyRow` | факт + значение | статус туннеля, листы |
| `ArdttLinearProgress`, `ArdttPingDot` | прогресс, пинг | деплой, сеть |
| `rememberArdttHaptics`, `RisingEdgeSuccessHaptic` | тактильный отклик | кнопки, подключение |

Экранные приватные виджеты, осознанно не поднятые в дизайн-систему (одно место
использования): `AdminUnlockSlider` (ворота admin), `UpdateFillButton` (кнопка с
заливкой прогресса), `TunnelPowerToggle` / `ThemeModeBadge` / `ProfileSwitcherBar`
(иллюстрированный туннель), `BypassSearchBar`, `HopConnector`, `ServerOsBadge`.

## Реестр токенов

| Объект | Что задаёт |
|--------|------------|
| `ArdttSpacing` | шаговая шкала отступов `None … XXXLarge` |
| `ArdttLayout` | роли шагов: `ScreenPadding`, `FeedSpacing`, `CardPadding`, `CardSpacing`, `SettingsCardPadding`, `SettingsCardSpacing`, `CompactCardPadding`, `ListSpacing`, `ControlSpacing`, `DialogPadding`, `SheetPadding` |
| `ArdttRadius` / `ArdttShapes` | радиусы и формы по типу поверхности (`Badge … Section`, `Field`, `Pill`, `Sheet`) |
| `ArdttElevation` | `None`, `Low`, `Card`, `Raised`, `Floating`, `FloatingDark` |
| `ArdttSize` | размеры контролов и глифов: иконки, спиннеры, `Chip`/`ChipCompact`, `Button`/`ButtonCompact`/`ButtonCluster`, `TouchTarget`, `NavTrack`/`NavZone`, `Border`/`Contour`/`Stroke`, `RecordingFrame` |
| `ArdttAlpha` | роли прозрачности: `Contour`, `Fill`, `FillSoft`, `Outline`, `Divider`, `Shadow`, `Disabled`, `DisabledContainer`, `Muted`, `Subtle`, `Strong` |
| `ArdttMotion` | длительности `Quick … Pulse` |
| `ArdttChrome` | blur/fade верхней панели |
| `ArdttColors` | семантика вне `ColorScheme`: `Connected`/`Warning` (+ `On*`, `*OnLight`/`*OnDark`), `SessionLit`, `Recording`, `PathDirect`/`PathBypass`, `Terminal*` |
| `ArdttSurface` | `isDark`, `contentColorOn`, `contrastRatio`, заливки карточек и «стекла» |
| `ArdttWallpaperTextShadow` | единственная тень текста прямо на обоях |
| `ArdttTerminalTextStyle` / `ArdttTerminalLabelStyle` | монотекст лога и метка уровня |

Экранные `*Defaults`-объекты (`UserTunnelDefaults`, `SettingsDefaults`,
`AdminUnlockDefaults`, `TunnelPollDefaults`, `ServerOsBadgeDefaults`) держат
разовую геометрию и интервалы рядом с местом использования, с именем — литерал в
вызове недопустим.

## Нейминг

| Что | Правило | Пример |
|-----|---------|--------|
| Компонент дизайн-системы | `Ardtt<Существительное>` | `ArdttSectionCard` |
| Оболочка приложения | `App<Существительное>` | `AppRoot`, `AppDestination` |
| Экран и его приватные части | по домену, без префикса | `TunnelScreen`, `ServerCard` |
| Объекты токенов и умолчаний | `Ardtt<Категория>`, `Ardtt<Компонент>Defaults` | `ArdttSpacing`, `ArdttHeaderDefaults` |
| Поля таких объектов | PascalCase | `ArdttColors.Connected` |
| `@Composable`, возвращающий значение | lowerCamelCase | `cardContainerColor()` |
| Строки интерфейса | `internal object <Домен>Copy`, поля UPPER_SNAKE | `PathModeCopy.AUTO` |

## Карта экранов

Роль контролов задаётся общим компонентом, не копией на экране.

| Экран | Режим | Каркас | Кнопки / действия | Диалоги / листы |
|-------|-------|--------|-------------------|-----------------|
| Туннель (иллюстрированный) | user | `BoxWithConstraints`: кольцо + статус + чипы + переключатель профиля снизу; short-landscape → кольцо слева | питание (`ConnectionControls`), Connecting → отмена, «Добавить код звонка», центр профиля → импорт/управление, бейдж темы | — |
| Туннель (панель) | admin, user classic | `ArdttFeedScaffold` + sticky CTA | подключить/остановить, чипы режима/адреса, Wi‑Fi | `BypassMethodDialog` |
| Профили | оба | `ArdttLazyFeedScaffold` + sticky «Добавить» | выбрать (radio), ⋮ подключить/копировать/поделиться/переименовать/удалить | `ProfileAddSheet`, подписка / ручной ввод / переименование, `ArdttConfirmDialog` удаления, `ProfileShareDialog` |
| Обход | user — вкладка; admin — из Настроек | `ArdttScrollChrome` + плавающий поиск | панель Приложения/Правила (`ArdttChoiceChipRow`), ЧС/БС, системные приложения, «Очистить» | подтверждение очистки правил |
| Журнал | user — вкладка; admin — из Диагностики | `ArdttScrollChrome` | очистить (с подтверждением) / копировать / поделиться, поиск, уровень, автопрокрутка, «К последним» | `ArdttConfirmDialog` |
| Настройки | оба; admin-блоки скрыты у user | `ArdttFeedScaffold` | чипы режима/адреса/темы, свитчи, Wi‑Fi, слайдер admin / «Завершить сессию» | соглашение тестирования, подтверждение выхода из admin, код звонка, подтверждение выхода из VK |
| Серверы → карточка → Клиенты / Деплой | admin | `ArdttScrollChrome` (+ sticky CTA) | список: «Добавить сервер», экспорт/импорт; карточка: клиенты / обновить / удалить / деинсталляция; деплой: сохранить / установить / назад | `DeployProgressSheet`, переименование, удаление/переустановка, `ClientSettingsSheet`, лимиты, `ArdttConfirmDialog` отвязки |
| Диагностика | admin | `ArdttFeedScaffold` + sticky | «Сеть» / «Журнал» / «Тестирование» снизу над таб-баром | — |
| Сеть | admin (из Диагностики) | `ArdttFeedScaffold` | обновление в заголовке, «Повторить» на hop-карточке | — |
| Тестирование | оба, при включённом режиме (из Диагностики / Журнала / Настроек) | `ArdttScrollChrome` + sticky «Начать/Остановить запись» | панели Хранилище/История, отправить / удалить (с подтверждением) | комментарий к логу, `ArdttConfirmDialog` удаления |

Вложенный `Deploy` открывается с Серверов, не вкладка. «Ещё» нет: пять основных пунктов, редко используемые экраны — вложенные маршруты.

## Режимы: пользователь и администратор

Один флаг — `AppSettingsRepository.isAdminUnlocked`; `AppRoot` читает его один раз
в `SessionChromeFlags` и передаёт экранам параметром (`isAdmin`), чтобы первый кадр
не мигал чужим режимом.

- Вход: слайдер `AdminUnlockSlider` в карточке «Описание и доступ» (жест, действие
  TalkBack «Активировать», Enter/DPAD на фокусе). Выход: кнопка «Завершить сессию
  администратора» → `ArdttConfirmDialog` с перечислением последствий.
- Текущий режим виден в подзаголовке Настроек (`AdminModeCopy.modeSubtitle`).
- Набор вкладок задаёт `ArdttNavPlan.primary(admin, …)`; admin-маршруты (`Servers`,
  `Diagnostics`, `Network`) перечислены в `AppDestination.adminOnly`. Если админ выключен,
  пока открыт admin-экран, `AppRoot` уводит на Туннель (`LaunchedEffect(admin, …, currentRoute)`).
- Admin-only элементы **скрываются** (`if (admin)`), не блокируются: свитчи «Скрыть
  быстрые настройки» и «Кнопки во время соединения», `DialPathChipRow`, ссылка
  «Правила обхода» в Настройках, статус-панель туннеля с IP/узлами, подробные логи.
- User-only: иллюстрированный туннель с обоями и «Классический вид» (админ всегда на
  панельном туннеле). Обход и Журнал у пользователя — вкладки, у админа — вложенные
  маршруты из Настроек / Диагностики; содержимое одно и то же.

## Токены

Экран не пишет `16.dp`, `0.18f` или `RoundedCornerShape(20.dp)` — он берёт
`ArdttLayout.ScreenPadding`, `ArdttAlpha.Fill`, `ArdttShapes.Control`. Значение без
токена (разовая геометрия вроде кольца питания) остаётся литералом рядом с местом
использования, с именем.

`ArdttSpacing` — шаговая шкала: `None` 0 · `Hairline` 2 · `Tiny` 4 · `TinyPlus` 6 ·
`Small` 8 · `SmallPlus` 10 · `Medium` 12 · `MediumPlus` 14 · `Large` 16 ·
`LargePlus` 18 · `XLarge` 20 · `XLargePlus` 22 · `XXLarge` 24 · `XXXLarge` 28.
`ArdttLayout` даёт этим шагам роли (`ScreenPadding`, `FeedSpacing`, `CardPadding`),
`ArdttRadius`/`ArdttShapes` — радиусы по типу поверхности (`Field` — поля ввода,
тот же радиус, что у `Chip`), `ArdttSize` — фиксированные размеры элементов
управления (`Chip` 44, `ChipCompact` 40 в быстрых параметрах туннеля).

## Каркас экрана

`ArdttFeedScaffold` — скроллящийся контейнер на `Column`: отступы, место под
плавающую панель вкладок, pull-to-refresh и закреплённый заголовок. Список на
`LazyColumn` берёт `ArdttLazyFeedScaffold` (тот же chrome и sticky CTA), а не
вкладывается в `verticalScroll`.

Заголовок один: `ArdttTabHeader` внутри каркаса (`header` слот `ArdttFeedScaffold` /
`ArdttLazyFeedScaffold`), `ArdttFeedHeader` только для скролла **без**
`ArdttScrollChrome`, `ArdttPageHeader` для экрана, который не является корнем вкладки.

## Кнопки

`ArdttButton` — общий контракт. Варианты: `Primary`, `Tonal`, `Outlined`, `Text`,
`Danger`, `Icon`. Размеры: `Regular` (мин. 58 dp) и `Compact` (мин. 48 dp); высота
растёт с системным `fontScale`. `ArdttPrimaryButton` — тонкая обёртка полной ширины.
Пустой `text` у `Primary` не рисует подпись, не растягивается на ширину и убирает
боковой padding — так собраны стрелки переключателя профилей (`ArdttSize.ButtonCluster`).
`Icon` — прозрачная иконка в шапке/баннере, не залитый control.

Состояния default / pressed / focused / disabled / loading задаются одним API:
`enabled`, `busy`. Индикатор загрузки берёт `content` той же пары, что и подпись.
Кастомный `containerColor` (в том числе error) не оставляет `onPrimary`, если это
не подходит фону: цвет текста считается через `ArdttSurface.contentColorOn`.
Круглая кнопка питания туннеля сохраняет свою форму; цвета и доступность — из
той же системы.

Disabled считается в одном месте (`ardttDisabledButtonColors`): залитые варианты
уходят в `ArdttAlpha.DisabledContainer` и держат подпись на `Subtle`, контурные /
текстовые / иконочные — подпись на `ArdttAlpha.Disabled`. Тот же `Disabled` у пунктов
меню, приглушённых чипов и подписей выключенных `ArdttSwitchRow`. Явный
`containerColor` вызывающей стороны (замок переключателя профилей) не переопределяется.
`Icon`-вариант наследует `LocalContentColor`, если `contentColor` не задан.

## Подтверждения

Необратимое или меняющее сессию действие идёт через `ArdttConfirmDialog` с текстом
последствий: очистка журнала, удаление профиля / записи телеметрии / клиента /
карточки сервера, деинсталляция, отвязка устройства, выход из режима администратора,
выход из сессии ВКонтакте. Пока операция идёт, `busy = true` блокирует обе кнопки и
закрытие. Отключение туннеля, удаление доверенной сети Wi‑Fi и сброс фильтра
подтверждения не требуют: они обратимы одним касанием.

## Ввод

`ArdttTextField` — единственный текстовый ввод (форма `Field`, полная ширина, одна
строка по умолчанию, прокрутка к полю при фокусе). `ArdttPasswordField` добавляет
переключатель видимости, `ArdttDigitsField` — цифровую клавиатуру и фильтр цифр с
`maxLength`. Ошибка ввода — `isError` + `supportingText` в самом поле.

## Цвета и контраст

Ориентир WCAG 2.2: текст и подписи кнопок ≥ 4,5:1, значимые нетекстовые контуры
≥ 3:1. Светлый `primary` — `#1565C0`, `onPrimary` белый. Заливка статуса
`Connected` `#1B7A32` / `Warning` `#B35C00` с белым onContainer. Цветной текст
статуса — отдельные `ConnectedOnLight` / `ConnectedOnDark` и Warning-пары
(`connectedStatusColor()` / `warningStatusColor()`). `contentColorOn` выбирает
кандидата по фактическому контрасту на итоговом фоне (с композитом alpha).
Неактивная вкладка использует полный `onSurfaceVariant` (alpha 0,92), без 7,5 sp
и без alpha 0,5.

## Навигация

Нижняя панель — ровно пять пунктов, без «Ещё». Пользователь: Туннель · Профили ·
Обход · Журнал · Настройки. Администратор: Туннель · Серверы · Профили ·
Диагностика · Настройки. План: `ArdttNavPlan`. Подпись вкладки `labelMedium`
≈ 12 sp, до двух строк, без автоуменьшения системного масштаба.
`selectableGroup` + `Role.Tab`. Вложенный маршрут (`network`, `logs`,
`testing`, `exceptions`, карточка сервера) выделяет родительскую вкладку.
Повторный выбор вкладки и admin/testing-ограничения не менялись; включение
тестирования не перестраивает набор вкладок.

Системная кнопка «Назад» на вложенном маршруте ведёт туда же, куда стрелка в шапке:
`ArdttNavPlan.backTarget(route, admin)` → `BackHandler` в `AppRoot`. Корневые
вкладки оставляют стандартный стек (→ Туннель → выход). Экранные `BackHandler`
(блокировка ухода во время деплоя, выход из режима выбора) регистрируются позже и
имеют приоритет.

Deep-link в Настройки (`PendingUiAction.openCallHashSettings` / `openUpdateDownload` /
`openAppearanceSettings`) один сценарий: прокрутка к карточке + вспышка контура
(`revealSection`).

## Верхняя панель (`ArdttScrollChrome`)

Лента рисуется один раз в `GraphicsLayer` и гасится `DstIn`-маской под шапкой
(текст становится прозрачным, а не «белеет» от цветной подложки). На API 31+
та же запись повторяется с GPU `BlurEffect`; обратная `DstIn`-маска гасит blur
к низу полосы, кроссфейд в чёткие пиксели. Заголовок вкладки растворяется при
прокрутке вниз (alpha + лёгкий подъём на высоту строки) и тем же ходом
возвращается при прокрутке обратно; status/cutout остаются. Отступ содержимого =
высота шапки + fade, поэтому первая строка при нулевой прокрутке не лежит под
переходом. Касания перехватывает только видимая шапка, не полоса fade. На API
28–30 blur пропускается; контент всё равно уходит в прозрачность. Скрытые под
панелью строки не кликаются и не дублируются в TalkBack.

Insets: высота статус-бара и выреза — `WindowInsets.statusBars ∪ displayCutout`,
не фиксированные 24 dp. Слот `header` уже стоит ниже этой полосы; не кладите
туда второй `ArdttStatusBarInset`. Токены: `ArdttChrome.BlurRadius`,
`FadeHeight`, `ScrimAlphaLight` / `ScrimAlphaDark`.

Pull-to-refresh и нижние закреплённые действия (`stickyContent`) сохраняются.

## Состояния экрана

Пустой список, загрузка и ошибка — `ArdttEmptyState` / `ArdttLoadingState` /
`ArdttErrorState` с `ArdttButton`. Поиск без совпадений предлагает сбросить
фильтр, а не «добавить с нуля». Повтор после ошибки вызывает ту же операцию
(`refreshAll`, повтор запроса), а не только закрывает плашку.

## Действия, которых не хватало

- Туннель Connecting: «Отменить» в обычном CTA и в круглой кнопке
  (`tunnelPowerToggleEnabled(Connecting) = true`). Probing не выдаётся за VPN.
- Упрощённый туннель: центр профиля при 0 профилях открывает импорт, при одном —
  управление (`PendingUiAction.requestOpenProfileAdd` / Profiles).
- Логи: автопрокрутка, «к последним», поиск и фильтр уровня (`LogsCatalog`).
- Сеть: кнопка обновления в заголовке и «Повторить» на карточке ошибки.
- Профили: `ArdttLazyFeedScaffold` + стабильные ключи `id`.

## Доступность

Роль, selected, `contentDescription`, `stateDescription` («Загрузка») задаются
у общих кнопок и вкладок. Декоративная иконка рядом с подписью — `null`.
Минимальная область касания 48×48 dp (`ArdttSize.TouchTarget`). Шапка
(`ArdttHeaderDefaults.TitleRowHeight`) совпадает с этим минимумом. Disabled-контроль
не должен быть единственным путём без объяснения: Connecting остаётся доступным
для отмены; пустой каталог ведёт к импорту; подсказка «добавьте код звонка» имеет
кнопку.

Одно действие — одна нода: `ArdttSwitchRow` и `ArdttCheckboxRow` делают toggleable
всю строку (`Role.Switch` / `Role.Checkbox`), сам `Switch`/`Checkbox` без обработчика;
строка приложения в Обходе устроена так же. Сегментные чипы — `selectableGroup` +
`Role.RadioButton` + `selected`; карточка профиля — `selectable` с `Role.RadioButton`.
Чисто декоративные элементы (соединитель hop-карточек, скелетон) — `clearAndSetSemantics {}`.
Жестовый контрол обязан иметь альтернативу: у слайдера admin — `CustomAccessibilityAction`
и Enter/DPAD-center на фокусе. Клавиатура: Tab обходит `clickable`/`toggleable`/кнопки
штатно, Enter активирует, Esc/Back закрывает `ArdttDialog`/`ArdttBottomSheet`.

## Адаптивность

Главный контрол и ключевые действия — снизу и достижимы на любой высоте: sticky CTA
над таб-баром, кольцо питания и переключатель профиля прижаты к низу `Spacer(weight)`.
Иллюстрированный туннель масштабирует кольцо от высоты окна
(`UserTunnelDefaults.ringSize`, 132–198 dp) и в коротком landscape раскладывает
кольцо и статус рядом (`sideBySide`). Длинные значения — `maxLines` + `Ellipsis`
(имена серверов/профилей/приложений, заголовок hop-карточки); ряды чипов профилей —
`horizontalScroll`. Фиксированные ширины допустимы только как `widthIn(max)`.

## Производительность

- Опросы (`while (true) { delay }`) оборачиваются в `repeatOnLifecycle(STARTED)`:
  статус туннеля, здоровье сервера, список клиентов, история тестирования, аптайм.
- Пассивная проверка обновлений при входе в Настройки объединяется на
  `BACKGROUND_CHECK_INTERVAL_MS`; pull-to-refresh идёт мимо троттла.
- Пакетные результаты (`probeAll`) пишутся в состояние один раз, не по одному.
- Списки — `LazyColumn` со стабильными `key`; фильтрация — `remember(keys)` /
  `derivedStateOf`; тяжёлые данные (иконки приложений) грузятся один раз на IO и кэшируются.
- Экран не читает `collectAsState(false)` для флагов, которые уже есть у `AppRoot`
  (`isAdmin`, `classicAppearance`) — иначе первый кадр мигает чужим режимом.

## Тёмная тема

Тёмная поверхность определяется только через `isDarkSurface()` / `ArdttSurface.isDark`,
по яркости `background`. `isSystemInDarkTheme()` в компонентах использовать нельзя: она
игнорирует ручной выбор темы в настройках.
