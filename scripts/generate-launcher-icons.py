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


def round_icon(square_rgba: Image.Image, size: int) -> Image.Image:
    im = square_rgba.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    y, x = np.ogrid[:size, :size]
    cx = cy = (size - 1) / 2.0
    radius = size / 2.0
    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    alpha = np.clip((radius - dist) * 255.0, 0, 255).astype(np.uint8)
    arr = np.array(im)
    arr[:, :, 3] = np.minimum(arr[:, :, 3], alpha)
    return Image.fromarray(arr, "RGBA")


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
    if white_only:
        out[:, :, 0] = 255
        out[:, :, 1] = 255
        out[:, :, 2] = 255
    else:
        out[:, :, :3] = np.clip(rgb[top:bottom, left:right], 0, 255).astype(np.uint8)
    out[:, :, 3] = np.clip(alpha[top:bottom, left:right] * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def fit_on_canvas(mark: Image.Image, canvas: int, safe_frac: float) -> Image.Image:
    safe = max(1, int(round(canvas * safe_frac)))
    mw, mh = mark.size
    scale = min(safe / mw, safe / mh)
    nw = max(1, int(round(mw * scale)))
    nh = max(1, int(round(mh * scale)))
    scaled = mark.resize((nw, nh), Image.Resampling.LANCZOS)
    fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    fg.paste(scaled, ((canvas - nw) // 2, (canvas - nh) // 2), scaled)
    return fg


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

    for density, size in LAUNCHER_SIZES.items():
        square = square_color_icon(master, size)
        save_png(square.convert("RGB"), RES / f"mipmap-{density}" / "ic_launcher.png")
        save_png(round_icon(square, size), RES / f"mipmap-{density}" / "ic_launcher_round.png")

    for density, size in FOREGROUND_SIZES.items():
        save_png(
            fit_on_canvas(color_mark, size, safe_frac=0.66),
            RES / f"mipmap-{density}" / "ic_launcher_foreground.png",
        )

    save_png(fit_on_canvas(white_mark, 256, safe_frac=0.72), RES / "drawable" / "ic_launcher_monochrome.png")

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
            fit_on_canvas(white_mark, size, safe_frac=0.84),
            RES / f"drawable-{density}" / "ic_stat_connected.png",
        )

    print("wrote launcher icons from", COLOR_SRC.relative_to(ROOT))


if __name__ == "__main__":
    main()
