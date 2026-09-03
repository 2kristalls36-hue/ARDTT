#!/usr/bin/env python3
"""Rasterize ARDTT launcher / widget icons from docs/assets/brand masters."""
from __future__ import annotations

from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "docs/assets/brand"
RES = ROOT / "android/app/src/main/res"
COLOR_SRC = BRAND / "ardtt-icon-source.png"
RED = (255, 24, 0, 255)

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


def flood_edge_mask(rgb: np.ndarray, thresh: int = 240) -> np.ndarray:
    r, g, b = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    near = (r > thresh) & (g > thresh) & (b > thresh)
    h, w = near.shape
    edge = np.zeros((h, w), dtype=bool)
    edge[0, :] = near[0, :]
    edge[-1, :] = near[-1, :]
    edge[:, 0] = near[:, 0]
    edge[:, -1] = near[:, -1]
    seen = edge.copy()
    q = deque(zip(*np.where(edge)))
    while q:
        y, x = q.popleft()
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and not seen[ny, nx] and near[ny, nx]:
                seen[ny, nx] = True
                q.append((ny, nx))
    return seen


def load_color_master() -> Image.Image:
    return Image.open(COLOR_SRC).convert("RGB")


def square_color_icon(master: Image.Image, size: int) -> Image.Image:
    arr = np.array(master)
    pad = flood_edge_mask(arr)
    ys, xs = np.where(~pad)
    left, top, right, bottom = int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())
    side = min(right - left, bottom - top)
    inset = max(2, int(round(0.066 * side)))
    crop = master.crop((left + inset, top + inset, right - inset + 1, bottom - inset + 1))
    cw, ch = crop.size
    s = min(cw, ch)
    x0, y0 = (cw - s) // 2, (ch - s) // 2
    square = crop.crop((x0, y0, x0 + s, y0 + s))
    return square.resize((size, size), Image.Resampling.LANCZOS)


def round_icon(square_rgb: Image.Image, size: int) -> Image.Image:
    im = square_rgb.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    y, x = np.ogrid[:size, :size]
    cx = cy = (size - 1) / 2.0
    radius = size / 2.0
    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    alpha = np.clip((radius - dist) * 255.0, 0, 255).astype(np.uint8)
    arr = np.array(im)
    arr[:, :, 3] = np.minimum(arr[:, :, 3], alpha)
    return Image.fromarray(arr, "RGBA")


def extract_white_mark(master: Image.Image) -> Image.Image:
    arr = np.array(master.convert("RGB"))
    h, w = arr.shape[:2]
    r = arr[:, :, 0].astype(np.int16)
    g = arr[:, :, 1].astype(np.int16)
    b = arr[:, :, 2].astype(np.int16)
    pad = flood_edge_mask(arr)
    icon = ~pad
    icon_img = Image.fromarray(icon.astype(np.uint8) * 255)
    eroded = np.array(icon_img.filter(ImageFilter.MinFilter(31))) > 128
    gb = (g + b) // 2
    alpha = np.zeros((h, w), dtype=np.uint8)
    t0, t1 = 90.0, 230.0
    val = np.clip((gb.astype(np.float32) - t0) * (255.0 / (t1 - t0)), 0, 255)
    alpha[eroded] = val[eroded].astype(np.uint8)
    alpha[(gb >= 215) & eroded] = 255
    rgba = np.zeros((h, w, 4), dtype=np.uint8)
    rgba[:, :, 0] = 255
    rgba[:, :, 1] = 255
    rgba[:, :, 2] = 255
    rgba[:, :, 3] = alpha
    mark = Image.fromarray(rgba, "RGBA")
    hard = mark.getchannel("A").point(lambda p: 255 if p > 80 else 0)
    bbox = hard.getbbox()
    if bbox is None:
        raise SystemExit("could not extract ARDTT mark from color icon")
    left, top, right, bottom = bbox
    pad_px = 12
    return mark.crop(
        (
            max(0, left - pad_px),
            max(0, top - pad_px),
            min(w, right + pad_px),
            min(h, bottom + pad_px),
        )
    )


def fit_on_canvas(mark: Image.Image, canvas: int, safe_frac: float = 0.66 * 0.88) -> Image.Image:
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


def main() -> None:
    if not COLOR_SRC.exists():
        raise SystemExit(f"missing {COLOR_SRC}")
    master = load_color_master()
    square_1024 = square_color_icon(master, 1024)
    mark = extract_white_mark(master)
    save_png(square_1024.convert("RGBA"), BRAND / "ar-icon-color.png")
    save_png(square_1024.convert("RGBA"), BRAND / "ar-icon-red.png")
    save_png(mark, BRAND / "ar-mark-white.png")

    for density, size in LAUNCHER_SIZES.items():
        square = square_color_icon(master, size)
        save_png(square.convert("RGB"), RES / f"mipmap-{density}" / "ic_launcher.png")
        save_png(round_icon(square, size), RES / f"mipmap-{density}" / "ic_launcher_round.png")

    for density, size in FOREGROUND_SIZES.items():
        save_png(fit_on_canvas(mark, size), RES / f"mipmap-{density}" / "ic_launcher_foreground.png")

    save_png(fit_on_canvas(mark, 256), RES / "drawable" / "ic_launcher_monochrome.png")

    for density, size in LOGO_FULL_SIZES.items():
        save_png(
            square_color_icon(master, size).convert("RGBA"),
            RES / f"drawable-{density}" / "ic_logo_full.png",
        )
    print("wrote launcher icons from", COLOR_SRC.relative_to(ROOT))


if __name__ == "__main__":
    main()
