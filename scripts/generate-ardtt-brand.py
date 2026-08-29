#!/usr/bin/env python3
"""Rasterize ARDTT brand PNGs into Android mipmap/drawable densities."""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
DOCS = ROOT / "docs/assets"
BRAND = DOCS / "brand"
WHITE_SRC = BRAND / "ar-mark-white.png"

# Dominant red from the supplied icon.
BRAND_RED = (255, 24, 0, 255)

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def load_white_glyph() -> Image.Image:
    im = Image.open(WHITE_SRC).convert("RGBA")
    r, g, b, a = im.split()
    white = Image.new("L", im.size, 255)
    glyph = Image.merge("RGBA", (white, white, white, a))
    bbox = glyph.getbbox()
    if bbox is None:
        raise SystemExit("white glyph is empty")
    l, t, rgt, btm = bbox
    pad = 2
    l = max(0, l - pad)
    t = max(0, t - pad)
    rgt = min(glyph.width, rgt + pad)
    btm = min(glyph.height, btm + pad)
    return glyph.crop((l, t, rgt, btm))


def fit_glyph(glyph: Image.Image, canvas: int, occupancy: float) -> Image.Image:
    gw, gh = glyph.size
    target = max(1, int(canvas * occupancy))
    scale = target / max(gw, gh)
    nw = max(1, int(round(gw * scale)))
    nh = max(1, int(round(gh * scale)))
    g2 = glyph.resize((nw, nh), Image.Resampling.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(g2, ((canvas - nw) // 2, (canvas - nh) // 2), g2)
    return out


def on_color(glyph: Image.Image, size: int, occupancy: float, fill: tuple[int, int, int, int]) -> Image.Image:
    base = Image.new("RGBA", (size, size), fill)
    return Image.alpha_composite(base, fit_glyph(glyph, size, occupancy))


def circle_mask(size: int) -> Image.Image:
    hi = size * 4
    m = Image.new("L", (hi, hi), 0)
    ImageDraw.Draw(m).ellipse((0, 0, hi - 1, hi - 1), fill=255)
    return m.resize((size, size), Image.Resampling.LANCZOS)


def round_icon(square: Image.Image) -> Image.Image:
    mask = circle_mask(square.width)
    out = Image.new("RGBA", square.size, (0, 0, 0, 0))
    out.paste(square, (0, 0), mask)
    return out


def save_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, format="PNG", optimize=True, compress_level=9)


def write_density(rel: str, dp: int, maker) -> None:
    for name, scale in DENSITIES.items():
        px = int(round(dp * scale))
        folder = RES / f"drawable-{name}" if rel.startswith("drawable/") else RES / f"mipmap-{name}"
        filename = Path(rel).name
        save_png(maker(px), folder / filename)


def main() -> None:
    glyph = load_white_glyph()
    if not WHITE_SRC.is_file():
        raise SystemExit(f"missing brand source {WHITE_SRC}")

    # Docs / README icon.
    save_png(on_color(glyph, 1024, 0.70, BRAND_RED).convert("RGB").convert("RGBA"), DOCS / "ardtt-icon.png")
    save_png(fit_glyph(glyph, 1024, 0.82), DOCS / "ardtt-mark.png")

    # Legacy launcher (48dp) + round.
    def launcher(px: int) -> Image.Image:
        return on_color(glyph, px, 0.68, BRAND_RED).convert("RGB")

    def launcher_round(px: int) -> Image.Image:
        return round_icon(on_color(glyph, px, 0.68, BRAND_RED))

    # Adaptive foreground (108dp): keep glyph inside the 66dp safe zone.
    def foreground(px: int) -> Image.Image:
        return fit_glyph(glyph, px, 0.56)

    write_density("mipmap/ic_launcher.png", 48, launcher)
    write_density("mipmap/ic_launcher_round.png", 48, launcher_round)
    write_density("mipmap/ic_launcher_foreground.png", 108, foreground)

    # In-app mark (existing tree used 40dp, not 48).
    write_density("drawable/ic_logo_full.png", 40, lambda px: on_color(glyph, px, 0.70, BRAND_RED))
    write_density("drawable/ic_logo_mark.png", 40, lambda px: fit_glyph(glyph, px, 0.86))
    write_density("drawable/ic_stat_connected.png", 24, lambda px: fit_glyph(glyph, px, 0.86))
    write_density("drawable/ic_tile_custom.png", 24, lambda px: fit_glyph(glyph, px, 0.86))

    save_png(fit_glyph(glyph, 256, 0.82), RES / "drawable/ic_launcher_monochrome.png")

    # Unlock hero: red field, large AR, high-res so Crop looks sharp.
    hero = on_color(glyph, 1440, 0.62, BRAND_RED)
    save_png(hero, RES / "drawable-nodpi/ic_unlock_hero.png")

    print("generated ARDTT brand rasters")


if __name__ == "__main__":
    main()
