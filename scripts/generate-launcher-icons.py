#!/usr/bin/env python3
"""Rasterize ARDTT launcher / widget / notification icons from the brand master."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "docs/assets/brand"
RES = ROOT / "android/app/src/main/res"
COLOR_SRC = BRAND / "ardtt-icon-source.png"

# Inner field of the supplied mark (not the black corner padding / silver rim).
FIELD = (3, 29, 59)

LAUNCHER_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FOREGROUND_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
LOGO_FULL_SIZES = {
    "mdpi": 40,
    "hdpi": 60,
    "xhdpi": 80,
    "xxhdpi": 120,
    "xxxhdpi": 160,
}
GLYPH_SIZES = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}


def load_color_master() -> Image.Image:
    return Image.open(COLOR_SRC).convert("RGBA")


def _as_rgba(master: Image.Image) -> np.ndarray:
    return np.array(master.convert("RGBA"), dtype=np.float32)


def _rgb(rgba: np.ndarray) -> np.ndarray:
    return rgba[:, :, :3]


def _alpha(rgba: np.ndarray) -> np.ndarray:
    return rgba[:, :, 3]


def _luma(rgb: np.ndarray) -> np.ndarray:
    return 0.2126 * rgb[:, :, 0] + 0.7152 * rgb[:, :, 1] + 0.0722 * rgb[:, :, 2]


def _chroma(rgb: np.ndarray) -> np.ndarray:
    mx = np.maximum(np.maximum(rgb[:, :, 0], rgb[:, :, 1]), rgb[:, :, 2])
    mn = np.minimum(np.minimum(rgb[:, :, 0], rgb[:, :, 1]), rgb[:, :, 2])
    return mx - mn


def morph(mask: np.ndarray, radius: int, *, erode: bool) -> np.ndarray:
    if radius <= 0:
        return mask
    pad = int(radius) + 2
    h, w = mask.shape
    padded = np.zeros((h + 2 * pad, w + 2 * pad), dtype=np.uint8)
    padded[pad : pad + h, pad : pad + w] = mask.astype(np.uint8) * 255
    img = Image.fromarray(padded, mode="L")
    remaining = int(radius)
    filt = ImageFilter.MinFilter if erode else ImageFilter.MaxFilter
    while remaining > 0:
        step = min(5, remaining)
        img = img.filter(filt(size=step * 2 + 1))
        remaining -= step
    out = np.array(img)[pad : pad + h, pad : pad + w]
    return out > 0


def blur_mask(mask: np.ndarray, radius: float) -> np.ndarray:
    u8 = np.clip(mask.astype(np.float32) * 255.0, 0, 255).astype(np.uint8)
    return (
        np.array(
            Image.fromarray(u8, "L").filter(ImageFilter.GaussianBlur(radius=radius)),
            dtype=np.float32,
        )
        / 255.0
    )


def content_mask(rgba: np.ndarray) -> np.ndarray:
    return _alpha(rgba) > 8.0


def rim_erode_radius(rgba: np.ndarray) -> int:
    return max(24, int(round(0.028 * max(rgba.shape[:2]))))


def inner_field_mask(rgba: np.ndarray) -> np.ndarray:
    """Navy plate inside the silver squircle rim, using the master alpha."""
    return morph(content_mask(rgba), rim_erode_radius(rgba), erode=True)


def letter_seed(rgb: np.ndarray, inner: np.ndarray) -> np.ndarray:
    """Solid AR (white) + DTT (cyan→teal) cores; excludes the silver rim."""
    r, g, b = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    luma = _luma(rgb)
    chroma = _chroma(rgb)
    gb = np.maximum(g, b)
    white = inner & (luma > 165.0) & (chroma < 55.0)
    cyan = inner & (gb > 130.0) & (gb > r + 25.0) & (luma > 70.0)
    seed = white | cyan
    return morph(morph(seed, 2, erode=True), 2, erode=False)


def letter_alpha(rgba: np.ndarray) -> np.ndarray:
    rgb = _rgb(rgba)
    inner = inner_field_mask(rgba)
    seed = letter_seed(rgb, inner)
    halo = morph(seed, 3, erode=False)
    luma = _luma(rgb)
    gb = np.maximum(rgb[:, :, 1], rgb[:, :, 2])
    alpha = np.zeros(luma.shape, dtype=np.float32)
    alpha[seed] = 1.0
    fringe = halo & ~seed
    a_white = np.clip((luma - 45.0) / 130.0, 0, 1)
    a_cyan = np.clip((gb - 75.0) / 110.0, 0, 1)
    alpha[fringe] = np.maximum(a_white[fringe], a_cyan[fringe])
    alpha = np.clip(blur_mask(alpha, 0.45), 0, 1)
    alpha[~halo] = 0.0
    alpha[seed] = np.maximum(alpha[seed], 0.92)
    return alpha


def letter_silhouette_alpha(rgba: np.ndarray) -> np.ndarray:
    """Hard AR/DTT mask for monochrome. Skip the color master's drop-shadow fringe."""
    seed = letter_seed(_rgb(rgba), inner_field_mask(rgba))
    return seed.astype(np.float32)


def pad_to_square_rgba(rgba: np.ndarray) -> np.ndarray:
    h, w = rgba.shape[:2]
    side = max(h, w)
    out = np.zeros((side, side, 4), dtype=np.float32)
    y = (side - h) // 2
    x = (side - w) // 2
    out[y : y + h, x : x + w] = rgba
    return out


def composite_on_field(rgba: np.ndarray) -> np.ndarray:
    a = (_alpha(rgba) / 255.0)[:, :, None]
    field = np.array(FIELD, dtype=np.float32)
    return _rgb(rgba) * a + field * (1.0 - a)


def square_color_icon(master: Image.Image, size: int) -> Image.Image:
    """Opaque launcher tile: master alpha composited onto the navy field."""
    rgba = pad_to_square_rgba(_as_rgba(master))
    rgb = composite_on_field(rgba)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").resize(
        (size, size),
        Image.Resampling.LANCZOS,
    )


def plate_rgba_icon(master: Image.Image, size: int) -> Image.Image:
    """Widget mark: keep the squircle and transparent corners."""
    rgba = pad_to_square_rgba(_as_rgba(master))
    return Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA").resize(
        (size, size),
        Image.Resampling.LANCZOS,
    )


# Navy disc: slightly lighter center, matching the master's radial field.
ROUND_CENTER = (8, 42, 82)
ROUND_RIM = (166, 172, 188)
# Letter block as a fraction of the circle diameter. 0.66 matches the adaptive
# foreground safe zone; wider than that clips AR/DTT corners on a circular mask.
ROUND_LETTER_FRAC = 0.66
ROUND_RIM_FRAC = 0.016
# AdaptiveIconDrawable draws each 108dp layer at 1.5× bounds; the launcher
# only shows the inner 72dp. A rim at the 108dp edge is cropped away.
ADAPTIVE_VIEWPORT = 2.0 / 3.0
# Square adaptive keeps letters-only: baking the silver squircle into the
# 72dp viewport is clipped by the OEM mask into grey chords (the old round bug).
# 0.82 of the viewport keeps DTT inside a typical squircle (n≈4).
SQUARE_ADAPTIVE_LETTER_FRAC = 0.82
# Themed icons reuse this layer for both masks; size it for a circle.
MONO_ADAPTIVE_LETTER_FRAC = ROUND_LETTER_FRAC


def adaptive_safe_frac(viewport_content_frac: float) -> float:
    return ADAPTIVE_VIEWPORT * viewport_content_frac


def round_color_icon(mark: Image.Image, size: int) -> Image.Image:
    """Dedicated circular launcher: navy disc, circular silver rim, inset letters.

    Do not punch a circle through the squircle master — that clips the square
    rim into grey chords and crowds the letterforms against the round edge.
    """
    ss = max(size * 4, 768)
    y, x = np.ogrid[:ss, :ss]
    cx = cy = (ss - 1) / 2.0
    radius = ss / 2.0
    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    t = np.clip(dist / radius, 0.0, 1.0) ** 1.35
    center = np.array(ROUND_CENTER, dtype=np.float32)
    edge = np.array(FIELD, dtype=np.float32)
    rgb = center * (1.0 - t[..., None]) + edge * t[..., None]
    rim_w = max(2.0, ss * ROUND_RIM_FRAC)
    d_in = radius - rim_w * 2.0
    d_out = radius - 0.75
    rise = np.clip((dist - d_in) / max(1e-6, rim_w * 0.45), 0.0, 1.0)
    fall = np.clip((d_out - dist) / max(1e-6, rim_w * 0.45), 0.0, 1.0)
    ring = np.minimum(rise, fall)
    rgb = rgb * (1.0 - ring[..., None]) + np.array(ROUND_RIM, dtype=np.float32) * ring[..., None]
    aa = np.clip((radius - dist) * 255.0, 0, 255)
    plate = np.zeros((ss, ss, 4), dtype=np.float32)
    plate[:, :, :3] = rgb
    plate[:, :, 3] = aa
    disc = Image.fromarray(np.clip(plate, 0, 255).astype(np.uint8), "RGBA")
    disc.alpha_composite(fit_on_canvas(mark, ss, safe_frac=ROUND_LETTER_FRAC))
    return disc.resize((size, size), Image.Resampling.LANCZOS)


def round_adaptive_foreground(mark: Image.Image, size: int) -> Image.Image:
    """108dp round-icon layer: navy disc sits in the inner 72dp viewport.

    minSdk 28 always resolves @mipmap/ic_launcher_round to the v26 XML, so the
    density PNGs never appear on device. This layer is what circular launchers
    actually mask.
    """
    inner = max(1, int(round(size * ADAPTIVE_VIEWPORT)))
    disc = round_color_icon(mark, inner)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ox = (size - inner) // 2
    canvas.paste(disc, (ox, ox), disc)
    return canvas


def extract_mark(master: Image.Image, white_only: bool = False) -> Image.Image:
    """Keep AR + DTT with a transparent field; crop to the letter block."""
    rgba = _as_rgba(master)
    rgb = _rgb(rgba)
    h, w = rgb.shape[:2]
    alpha = letter_alpha(rgba)
    seed = letter_seed(rgb, inner_field_mask(rgba))
    ys, xs = np.where(seed)
    if ys.size == 0:
        raise SystemExit("could not extract ARDTT mark from color icon")
    pad_px = max(12, int(round(0.03 * max(w, h))))
    left = max(0, int(xs.min()) - pad_px)
    top = max(0, int(ys.min()) - pad_px)
    right = min(w, int(xs.max()) + pad_px + 1)
    bottom = min(h, int(ys.max()) + pad_px + 1)
    out = np.zeros((bottom - top, right - left, 4), dtype=np.uint8)
    out[:, :, 0] = 255
    out[:, :, 1] = 255
    out[:, :, 2] = 255
    if white_only:
        sil = letter_silhouette_alpha(rgba)
        out[:, :, 3] = np.clip(sil[top:bottom, left:right] * 255.0, 0, 255).astype(np.uint8)
    else:
        out[:, :, :3] = np.clip(rgb[top:bottom, left:right], 0, 255).astype(np.uint8)
        out[:, :, 3] = np.clip(alpha[top:bottom, left:right] * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def force_white_rgb(image: Image.Image) -> Image.Image:
    """Keep RGB white so LANCZOS / themed-icon masks do not pick up a dark fringe."""
    arr = np.array(image.convert("RGBA"))
    arr[:, :, 0:3] = 255
    return Image.fromarray(arr, "RGBA")


def fit_on_canvas(
    mark: Image.Image,
    canvas: int,
    safe_frac: float,
    *,
    white: bool = False,
) -> Image.Image:
    safe = max(1, int(round(canvas * safe_frac)))
    mw, mh = mark.size
    scale = min(safe / mw, safe / mh)
    nw = max(1, int(round(mw * scale)))
    nh = max(1, int(round(mh * scale)))
    scaled = mark.resize((nw, nh), Image.Resampling.LANCZOS)
    if white:
        scaled = force_white_rgb(scaled)
    fill = (255, 255, 255, 0) if white else (0, 0, 0, 0)
    fg = Image.new("RGBA", (canvas, canvas), fill)
    fg.paste(scaled, ((canvas - nw) // 2, (canvas - nh) // 2), scaled)
    return force_white_rgb(fg) if white else fg


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def cap_side(image: Image.Image, max_side: int = 1024) -> Image.Image:
    w, h = image.size
    longest = max(w, h)
    if longest <= max_side:
        return image
    scale = max_side / longest
    return image.resize(
        (max(1, int(round(w * scale))), max(1, int(round(h * scale)))),
        Image.Resampling.LANCZOS,
    )


def main() -> None:
    if not COLOR_SRC.exists():
        raise SystemExit(f"missing {COLOR_SRC}")
    master = load_color_master()
    square_1024 = square_color_icon(master, 1024)
    color_mark = extract_mark(master, white_only=False)
    white_mark = extract_mark(master, white_only=True)
    save_png(square_1024, BRAND / "ar-icon-color.png")
    save_png(square_1024, BRAND / "ar-icon-red.png")
    save_png(cap_side(color_mark), BRAND / "ardtt-mark-source.png")
    save_png(cap_side(white_mark), BRAND / "ar-mark-white.png")
    save_png(round_color_icon(color_mark, 1024), BRAND / "ardtt-icon-round-source.png")

    for density, size in LAUNCHER_SIZES.items():
        square = square_color_icon(master, size)
        save_png(square.convert("RGB"), RES / f"mipmap-{density}" / "ic_launcher.png")
        save_png(round_color_icon(color_mark, size), RES / f"mipmap-{density}" / "ic_launcher_round.png")

    for density, size in FOREGROUND_SIZES.items():
        save_png(
            fit_on_canvas(
                color_mark,
                size,
                safe_frac=adaptive_safe_frac(SQUARE_ADAPTIVE_LETTER_FRAC),
            ),
            RES / f"mipmap-{density}" / "ic_launcher_foreground.png",
        )
        save_png(
            round_adaptive_foreground(color_mark, size),
            RES / f"mipmap-{density}" / "ic_launcher_round_foreground.png",
        )
        save_png(
            fit_on_canvas(
                white_mark,
                size,
                safe_frac=adaptive_safe_frac(MONO_ADAPTIVE_LETTER_FRAC),
                white=True,
            ),
            RES / f"mipmap-{density}" / "ic_launcher_monochrome.png",
        )

    for density, size in LOGO_FULL_SIZES.items():
        save_png(
            plate_rgba_icon(master, size),
            RES / f"drawable-{density}" / "ic_logo_full.png",
        )

    for density, size in GLYPH_SIZES.items():
        # QS tiles are tinted by SystemUI. An opaque navy square becomes a
        # solid blob, so the tile stays on the old silhouette. Use the AR/DTT
        # mark on a transparent field so the new letterforms stay visible.
        save_png(
            fit_on_canvas(color_mark, size, safe_frac=0.84),
            RES / f"drawable-{density}" / "ic_tile_custom.png",
        )
        save_png(
            fit_on_canvas(white_mark, size, safe_frac=0.84, white=True),
            RES / f"drawable-{density}" / "ic_stat_connected.png",
        )

    print("wrote launcher icons from", COLOR_SRC.relative_to(ROOT))


if __name__ == "__main__":
    main()
