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
│   ├── ArdttBackdropTone.kt  цвета поверх иллюстрированных обоев
│   ├── ArdttTypography.kt  Inter + шкала
│   └── ArdttTheme.kt       ArdttTheme() и системные панели
├── components/     # дизайн-система
│   ├── layout/     каркас: ArdttFeedScaffold, ArdttLazyFeedScaffold, ArdttScrollChrome,
│   │               ArdttPageHeader, ArdttNavigationBar,
│   │               ArdttBottomChrome, ArdttStickyBottomBar, ArdttPullRefresh, ArdttBackdrop
│   ├── surface/    контейнеры: ArdttSectionCard, ArdttCompactCard, ArdttDialog,
│   │               ArdttBottomSheet, ArdttLinkShareDialog, ArdttQrCode,
│   │               ArdttTerminalCard, ArdttFloatingShell, ArdttSectionTitle
│   ├── control/    ввод: ArdttChoiceChip(+Row), настроечные чипы, ArdttSwitchRow,
│   │               ArdttSettingBlock, ArdttButton, ArdttPrimaryButton, ArdttOverflowMenu, ArdttHaptics
│   └── feedback/   состояние: ArdttStatusChip/Pill/Dot, ArdttEmptyState/LoadingState/
│                   ErrorState, ArdttInlineFactRow, ArdttStackedFactRow, ArdttCopyRow,
│                   ArdttLinearProgress, ArdttPingDot
├── util/           ClipboardActions.kt — копирование, вставка, «Поделиться»
└── admin · settings · tunnel · profiles · exceptions · telemetry   # экраны
```

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

| Экран | Каркас | Кнопки / действия |
|-------|--------|-------------------|
| Туннель | свой layout + wallpaper | питание (`ConnectionControls`), Connecting → отмена, центр профиля → импорт/управление |
| Сеть | `ArdttFeedScaffold` | обновление в заголовке, «Повторить» на hop-карточке |
| Серверы / деплой | `ArdttScrollChrome` | `ArdttButton` сохранить / установить / назад |
| Клиенты | `ArdttScrollChrome` + sticky CTA | `ArdttButton` создать / лимит / вкл.; лист `ClientSettingsSheet` |
| Профили | `ArdttLazyFeedScaffold` | «Добавить», карточки с ключом `id` |
| Обход | `ArdttScrollChrome` | поиск, «Очистить поиск», «Добавить» |
| Логи | `ArdttScrollChrome` | follow / к последним / поиск / уровень (`LogsCatalog`) |
| Настройки | `ArdttFeedScaffold` | `ArdttButton` Wi‑Fi, админ-сессия, код звонка |
| Тестирование | `ArdttScrollChrome` | запись, отправка, история |

Вложенный `Deploy` открывается с Серверов, не вкладка. «Ещё» держит остальные `AppDestination` (`ArdttNavPlan`).

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

## Цвета и контраст

Ориентир WCAG 2.2: текст и подписи кнопок ≥ 4,5:1, значимые нетекстовые контуры
≥ 3:1. Светлый `primary` — `#1565C0`, `onPrimary` белый. Connected `#1B7A32`,
Warning `#B35C00`. `contentColorOn` выбирает кандидата по фактическому контрасту
на итоговом фоне (с композитом alpha). Неактивная вкладка использует полный
`onSurfaceVariant` (alpha 0,92), без 7,5 sp и без alpha 0,5.

## Навигация

Нижняя панель — не больше пяти пунктов: четыре основные вкладки и «Ещё».
План: `ArdttNavPlan`. Подпись вкладки `labelMedium` ≈ 12 sp, без автоуменьшения.
`selectableGroup` + `Role.Tab`. Вложенный раздел из «Ещё» оставляет выбранным
пункт «Ещё». Повторный выбор вкладки и admin/testing-ограничения не менялись.

## Верхняя панель (`ArdttScrollChrome`)

Лента рисуется один раз в `GraphicsLayer`. На API 31+ та же запись повторяется
в обрезанной полосе с GPU `BlurEffect` и вертикальным scrim. Заголовок и
status/cutout — отдельный чёткий слой сверху. На API 28–30 blur пропускается,
остаются fade и тематическая подложка. Жесты заголовка не перехватываются
декором; скрытые под панелью строки не кликаются и не дублируются в TalkBack.

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
Минимальная область касания 48×48 dp (`ArdttSize.TouchTarget`). Disabled-контроль
не должен быть единственным путём без объяснения: Connecting остаётся доступным
для отмены; пустой каталог ведёт к импорту.

## Тёмная тема

Тёмная поверхность определяется только через `isDarkSurface()` / `ArdttSurface.isDark`,
по яркости `background`. `isSystemInDarkTheme()` в компонентах использовать нельзя: она
игнорирует ручной выбор темы в настройках.
