# UI-слой клиента

Соглашения для `android/app/src/main/java/com/ardtt/app/ui`. Правило одно: значение,
имя или паттерн определяются в одном месте, экраны их только используют.

## Структура

```
ui/
├── AppRoot.kt / AppDestination.kt / PendingUiAction.kt   # оболочка приложения
├── AppCopy.kt                                            # общие пользовательские строки
├── ConnectionControls.kt                                 # запись настроек подключения
├── theme/          # переменные и палитра
│   ├── ArdttTokens.kt      ArdttSpacing · ArdttLayout · ArdttRadius · ArdttShapes
│   │                       ArdttElevation · ArdttSize · ArdttAlpha · ArdttMotion
│   ├── ArdttColors.kt      светлая/тёмная схемы, семантические цвета, обои → палитра
│   ├── ArdttSurface.kt     единственная проверка «тёмная ли поверхность» и заливки
│   ├── ArdttBackdropTone.kt  цвета поверх иллюстрированных обоев
│   ├── ArdttTypography.kt  Inter + шкала
│   └── ArdttTheme.kt       ArdttTheme() и системные панели
├── components/     # дизайн-система
│   ├── layout/     каркас: ArdttFeedScaffold, ArdttPageHeader, ArdttNavigationBar,
│   │               ArdttBottomChrome, ArdttStickyBottomBar, ArdttPullRefresh, ArdttBackdrop
│   ├── surface/    контейнеры: ArdttSectionCard, ArdttCompactCard, ArdttDialog,
│   │               ArdttBottomSheet, ArdttLinkShareDialog, ArdttQrCode,
│   │               ArdttTerminalCard, ArdttFloatingShell, ArdttSectionTitle
│   ├── control/    ввод: ArdttChoiceChip(+Row), настроечные чипы, ArdttSwitchRow,
│   │               ArdttSettingBlock, ArdttPrimaryButton, ArdttOverflowMenu, ArdttHaptics
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

`ArdttFeedScaffold` — единственный скроллящийся контейнер: отступы, отступ под плавающую
панель вкладок, pull-to-refresh и якорь заголовка в нём уже есть. Передайте
`stickyContent` — снизу закрепится действие, и лента сама зарезервирует под него место.
Экрану со списком на `LazyColumn` каркас не подходит; такой экран берёт
`ArdttStickyBottomBar`, чтобы кнопка встала там же, где у остальных.

Заголовок один: `ArdttTabHeader` внутри каркаса, `ArdttFeedHeader` для собственного
скролла, `ArdttPageHeader` для экрана, который не является корнем вкладки.

## Тёмная тема

Тёмная поверхность определяется только через `isDarkSurface()` / `ArdttSurface.isDark`,
по яркости `background`. `isSystemInDarkTheme()` в компонентах использовать нельзя: она
игнорирует ручной выбор темы в настройках.
