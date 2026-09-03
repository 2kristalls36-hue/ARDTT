#!/usr/bin/env python3
"""Rasterize ARDTT launcher / widget / notification icons from the brand master."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "docs/assets/brand"
RES = ROOT / "android/app/src/main/res"
COLOR_SRC = BRAND / "ardtt-icon-source.png"

# Inner field of the supplied mark (not the black corner padding).
CHARCOAL = (24, 30, 37)
CHARCOAL_A = (*CHARCOAL, 255)

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


def luma_of(rgb: np.ndarray) -> np.ndarray:
    r = rgb[:, :, 0].astype(np.float32)
    g = rgb[:, :, 1].astype(np.float32)
    b = rgb[:, :, 2].astype(np.float32)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def load_color_master() -> Image.Image:
    return Image.open(COLOR_SRC).convert("RGB")


def fill_pad_with_charcoal(master: Image.Image) -> Image.Image:
    """Turn the black corner padding of the squircle into the charcoal field."""
    arr = np.array(master.convert("RGBA"))
    pad = luma_of(arr) < 10.0
    arr[pad, 0] = CHARCOAL[0]
    arr[pad, 1] = CHARCOAL[1]
    arr[pad, 2] = CHARCOAL[2]
    arr[pad, 3] = 255
    return Image.fromarray(arr, "RGBA")


def square_color_icon(master: Image.Image, size: int) -> Image.Image:
    filled = fill_pad_with_charcoal(master)
    return filled.resize((size, size), Image.Resampling.LANCZOS)


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


def rounded_square(square_rgba: Image.Image, size: int, radius_frac: float = 0.22) -> Image.Image:
    im = square_rgba.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    radius = max(1, int(round(size * radius_frac)))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    arr = np.array(im)
    arr[:, :, 3] = np.minimum(arr[:, :, 3], np.array(mask))
    return Image.fromarray(arr, "RGBA")


def extract_mark(master: Image.Image, white_only: bool = False) -> Image.Image:
    """Keep AR (white) + DTT (orange) with a transparent charcoal field."""
    rgb = np.array(master.convert("RGB"))
    h, w = rgb.shape[:2]
    r = rgb[:, :, 0].astype(np.float32)
    g = rgb[:, :, 1].astype(np.float32)
    b = rgb[:, :, 2].astype(np.float32)
    luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
    orange = (r > 150) & (b < 90) & (g > 20) & (g < 220)
    soft = np.clip((luma - 48.0) * (255.0 / 60.0), 0, 255)
    alpha = np.maximum(np.where(orange, 255.0, 0.0), soft).astype(np.uint8)
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    if white_only:
        rgba[:, :, 0] = 255
        rgba[:, :, 1] = 255
        rgba[:, :, 2] = 255
    else:
        rgba[:, :, 0] = rgb[:, :, 0]
        rgba[:, :, 1] = rgb[:, :, 1]
        rgba[:, :, 2] = rgb[:, :, 2]
    rgba[:, :, 3] = alpha
    mark = Image.fromarray(rgba, "RGBA")
    hard = mark.getchannel("A").point(lambda p: 255 if p > 40 else 0)
    bbox = hard.getbbox()
    if bbox is None:
        raise SystemExit("could not extract ARDTT mark from color icon")
    left, top, right, bottom = bbox
    pad_px = max(8, int(round(0.02 * max(w, h))))
    return mark.crop(
        (
            max(0, left - pad_px),
            max(0, top - pad_px),
            min(w, right + pad_px),
            min(h, bottom + pad_px),
        )
    )


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
            fit_on_canvas(color_mark, size, safe_frac=0.62),
            RES / f"mipmap-{density}" / "ic_launcher_foreground.png",
        )

    save_png(fit_on_canvas(white_mark, 256, safe_frac=0.72), RES / "drawable" / "ic_launcher_monochrome.png")

    for density, size in LOGO_FULL_SIZES.items():
        save_png(
            rounded_square(square_color_icon(master, size), size),
            RES / f"drawable-{density}" / "ic_logo_full.png",
        )

    for density, size in GLYPH_SIZES.items():
        save_png(
            rounded_square(square_color_icon(master, size), size),
            RES / f"drawable-{density}" / "ic_tile_custom.png",
        )
        save_png(
            fit_on_canvas(white_mark, size, safe_frac=0.84),
            RES / f"drawable-{density}" / "ic_stat_connected.png",
        )

    print("wrote launcher icons from", COLOR_SRC.relative_to(ROOT))


if __name__ == "__main__":
    main()
