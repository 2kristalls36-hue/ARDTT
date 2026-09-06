package com.ardtt.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the whole UI layer.
 *
 * Every dp / alpha / duration used by a shared component or a screen comes from
 * one of the objects below. Feature code must not spell raw literals: a value
 * that has no token yet belongs here first, so the same visual step is reused
 * instead of re-invented per screen.
 *
 * Member naming is PascalCase across all token and `*Defaults` objects.
 */

/**
 * Layout step scale. Values are the ones the product already ships; the `Plus`
 * suffix marks the half step between two neighbouring sizes.
 */
object ArdttSpacing {
    val None: Dp = 0.dp
    val Hairline: Dp = 2.dp
    val Tiny: Dp = 4.dp
    val TinyPlus: Dp = 6.dp
    val Small: Dp = 8.dp
    val SmallPlus: Dp = 10.dp
    val Medium: Dp = 12.dp
    val MediumPlus: Dp = 14.dp
    val Large: Dp = 16.dp
    val LargePlus: Dp = 18.dp
    val XLarge: Dp = 20.dp
    val XLargePlus: Dp = 22.dp
    val XXLarge: Dp = 24.dp
    val XXXLarge: Dp = 28.dp
}

/** Named roles for the steps above, so screens describe intent, not size. */
object ArdttLayout {
    /** Horizontal gutter of every scrollable feed. */
    val ScreenPadding: Dp = ArdttSpacing.Large

    /** Gap between cards inside a feed. */
    val FeedSpacing: Dp = ArdttSpacing.MediumPlus

    /** Trailing air below the last feed item. */
    val FeedBottomExtra: Dp = ArdttSpacing.XXLarge

    /** Inner padding of a full-width section card. */
    val CardPadding: PaddingValues = PaddingValues(ArdttSpacing.LargePlus)

    /** Gap between rows inside a section card. */
    val CardSpacing: Dp = ArdttSpacing.Large

    /** Inner padding of a compact list card (servers, clients, profiles). */
    val CompactCardPadding: PaddingValues =
        PaddingValues(horizontal = ArdttSpacing.Medium, vertical = ArdttSpacing.SmallPlus)

    /** Gap between rows inside a compact list card. */
    val CompactCardSpacing: Dp = ArdttSpacing.TinyPlus

    /** Gap between items of a list of compact cards. */
    val ListSpacing: Dp = ArdttSpacing.Small

    /** Gap between sibling controls on one row. */
    val ControlSpacing: Dp = ArdttSpacing.Small

    /** Horizontal padding of dialog content and footer. */
    val DialogPadding: Dp = ArdttSpacing.XXLarge

    /** Horizontal padding of a modal bottom sheet. */
    val SheetPadding: Dp = ArdttSpacing.XLargePlus
}

/** Corner radii, named after the surface they belong to. */
object ArdttRadius {
    val Badge: Dp = ArdttSpacing.Small
    val Icon: Dp = ArdttSpacing.Medium
    val Row: Dp = ArdttSpacing.MediumPlus
    val Chip: Dp = ArdttSpacing.Large
    val Card: Dp = ArdttSpacing.LargePlus
    val Control: Dp = ArdttSpacing.XLarge
    val Menu: Dp = ArdttSpacing.XLargePlus
    val Panel: Dp = ArdttSpacing.XXLarge
    val Section: Dp = ArdttSpacing.XXXLarge
}

/** Ready-made shapes so call sites never build `RoundedCornerShape` inline. */
object ArdttShapes {
    val Badge: Shape = RoundedCornerShape(ArdttRadius.Badge)
    val Icon: Shape = RoundedCornerShape(ArdttRadius.Icon)
    val Row: Shape = RoundedCornerShape(ArdttRadius.Row)
    val Chip: Shape = RoundedCornerShape(ArdttRadius.Chip)
    /** Outlined text fields share the chip radius. */
    val Field: Shape = Chip
    val Card: Shape = RoundedCornerShape(ArdttRadius.Card)
    val Control: Shape = RoundedCornerShape(ArdttRadius.Control)
    val Menu: Shape = RoundedCornerShape(ArdttRadius.Menu)
    val Panel: Shape = RoundedCornerShape(ArdttRadius.Panel)
    val Section: Shape = RoundedCornerShape(ArdttRadius.Section)
    val Pill: Shape = CircleShape
    val Sheet: Shape = RoundedCornerShape(
        topStart = ArdttRadius.Section,
        topEnd = ArdttRadius.Section,
    )
}

/** Shadow / tonal elevation steps. */
object ArdttElevation {
    val None: Dp = 0.dp
    val Low: Dp = 2.dp
    val Card: Dp = 4.dp
    val Raised: Dp = 6.dp
    val Floating: Dp = 8.dp
    val FloatingDark: Dp = 10.dp
}

/** Fixed control and glyph sizes. */
object ArdttSize {
    val Dot: Dp = 8.dp
    val IconSmall: Dp = 16.dp
    val IconCompact: Dp = 18.dp
    val Icon: Dp = 22.dp
    val IconLarge: Dp = 24.dp
    val IconHero: Dp = 48.dp

    val SpinnerSmall: Dp = 16.dp
    val Spinner: Dp = 22.dp
    val SpinnerLarge: Dp = 36.dp
    val PullIndicator: Dp = 40.dp

    /** Height of a bottom-tab title row and of a choice chip. */
    val TitleRow: Dp = 44.dp
    val Chip: Dp = 44.dp
    /** Compact chip in tunnel quick-settings, where three rows share a card. */
    val ChipCompact: Dp = 40.dp
    val MenuWidth: Dp = 216.dp
    val MenuItem: Dp = 54.dp

    /** Height of every primary (sticky / full-width) button. */
    val Button: Dp = 58.dp

    /** Inner height of the floating tab pill. */
    val NavTrack: Dp = 48.dp

    /** Screen space the floating tab pill occupies, insets excluded.
     *  Must stay NavTrack + 2 × ArdttSpacing.Small (outer vertical padding). */
    val NavZone: Dp = 64.dp

    val Border: Dp = 1.dp
    val Contour: Dp = 2.dp
    val Stroke: Dp = 2.dp
    val StrokeThick: Dp = 2.5.dp
}

/** Opacity steps. One name per visual role, not per numeric value. */
object ArdttAlpha {
    /** Section-card hairline contour. */
    const val Contour = 0.16f

    /** Tinted background of a status badge or selected chip. */
    const val Fill = 0.18f

    /** Border of a selected control. */
    const val Outline = 0.22f

    /** Hairline separating rows inside a card. */
    const val Divider = 0.35f

    /** Text shadow over the illustrated wallpaper. */
    const val Shadow = 0.42f

    /** Disabled label or icon. */
    const val Disabled = 0.45f

    /** Secondary label that must stay readable. */
    const val Muted = 0.55f

    /** Unselected tab icon, placeholder text. */
    const val Subtle = 0.72f

    /** Translucent fill that still reads as opaque. */
    const val Strong = 0.94f
}

/** Animation durations in milliseconds. */
object ArdttMotion {
    const val Quick = 140
    const val Fast = 200
    const val Standard = 400
    const val Relaxed = 500
    const val Slow = 700
    const val Indicator = 720
    const val Pulse = 900
}
