#!/usr/bin/env python3
"""Build launcher assets from the RadioStar source artwork.

Source: docs/idea/new app icon.jpeg (local, gitignored). The script removes
only the flat background for adaptive icons; it never crops the strawberry or
headphones. Legacy icons keep the full square artwork with rounded corners.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs" / "idea" / "new app icon.jpeg"
RES = ROOT / "app" / "app" / "src" / "main" / "res"
BACKGROUND = (209, 186, 172, 255)
LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ADAPTIVE_SIZE = 432


def subject_layer(image: Image.Image) -> Image.Image:
    """Return the artwork with the flat background removed."""
    rgba = image.convert("RGBA")
    background = Image.new("RGBA", rgba.size, BACKGROUND)
    distance = ImageChops.difference(rgba.convert("RGB"), background.convert("RGB"))
    distance = distance.convert("L")
    # Keep anti-aliased edges, drop JPEG noise in the flat background.
    alpha = distance.point(lambda value: 0 if value <= 10 else min(255, int((value - 10) * 255 / 12)))
    rgba.putalpha(alpha)
    return rgba


def fit_centered(image: Image.Image, canvas_size: int, content_ratio: float) -> Image.Image:
    bbox = image.getbbox()
    if bbox is None:
        return Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    trimmed = image.crop(bbox)
    limit = int(canvas_size * content_ratio)
    scale = min(limit / trimmed.width, limit / trimmed.height)
    resized = trimmed.resize(
        (max(1, round(trimmed.width * scale)), max(1, round(trimmed.height * scale))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    canvas.alpha_composite(
        resized,
        ((canvas_size - resized.width) // 2, (canvas_size - resized.height) // 2),
    )
    return canvas


def rounded(image: Image.Image, radius_ratio: float) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    draw = ImageDraw.Draw(mask)
    radius = int(min(image.size) * radius_ratio)
    draw.rounded_rectangle((0, 0, image.size[0] - 1, image.size[1] - 1), radius=radius, fill=255)
    result = image.convert("RGBA")
    result.putalpha(mask)
    return result


def circle(image: Image.Image) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, image.size[0] - 1, image.size[1] - 1), fill=255)
    result = image.convert("RGBA")
    result.putalpha(mask)
    return result


def main() -> int:
    source = Image.open(SOURCE).convert("RGBA")
    subject = subject_layer(source)

    foreground = fit_centered(subject, ADAPTIVE_SIZE, 0.62)
    foreground.save(RES / "drawable-nodpi" / "ic_launcher_foreground.png")
    foreground.save(RES / "drawable-nodpi" / "ic_launcher_foreground_v31.png")
    foreground.save(RES / "drawable" / "ic_launcher_static_foreground.png")

    monochrome = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    white = Image.new("RGBA", foreground.size, (255, 255, 255, 255))
    monochrome.paste(white, (0, 0), foreground.getchannel("A"))
    monochrome.save(RES / "drawable-nodpi" / "ic_launcher_monochrome.png")

    for density, size in LEGACY_SIZES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        square = source.resize((size, size), Image.LANCZOS)
        rounded(square, 0.22).save(
            folder / "ic_launcher.webp", "WEBP", quality=95, method=6
        )
        circle(square).save(
            folder / "ic_launcher_round.webp", "WEBP", quality=95, method=6
        )
        rounded(square, 0.22).save(folder / "ic_launcher_static.png", "PNG")

    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#D1BAAC</color>\n'
        '    <color name="teal_200">#80CBC4</color>\n'
        '    <color name="teal_700">#00796B</color>\n'
        "</resources>\n"
    )
    (RES / "drawable" / "ic_launcher_background_v31.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <solid android:color="#D1BAAC" />\n'
        "</shape>\n"
    )
    (RES / "drawable-v31" / "ic_launcher_background_v31.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <solid android:color="#D1BAAC" />\n'
        "</shape>\n"
    )
    print("launcher icons written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
