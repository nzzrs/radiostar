#!/usr/bin/env python3
"""Build launcher assets from the RadioStar source artwork.

Source: docs/idea/new app icon.jpeg (local, gitignored). The source is already
a square, so the launcher assets keep the whole frame and only rescale it.
Legacy icons get rounded corners; adaptive icons place the same square inside
the safe zone so a launcher mask never cuts the artwork. A subject-only
silhouette is still used for the Android themed (monochrome) icon.
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
# Keep the strawberry and headphones at the same visual size as before, inside
# the adaptive icon safe area. The flat beige margin may extend under the
# launcher mask, but no part of the artwork is trimmed.
ADAPTIVE_SUBJECT_RATIO = 0.62


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


def place_image(
    image: Image.Image,
    canvas_size: int,
    subject_bbox: tuple[int, int, int, int],
    subject_ratio: float,
) -> Image.Image:
    """Place the whole image so its subject fills the requested ratio.

    The image is never trimmed. Only flat background may fall outside the
    canvas, which lets the adaptive mask round the icon without cutting the
    strawberry or the headphones.
    """
    subject_size = max(subject_bbox[2] - subject_bbox[0], subject_bbox[3] - subject_bbox[1])
    scale = (canvas_size * subject_ratio) / subject_size
    resized = image.resize(
        (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
        Image.LANCZOS,
    )
    subject_center_x = (subject_bbox[0] + subject_bbox[2]) / 2 * scale
    subject_center_y = (subject_bbox[1] + subject_bbox[3]) / 2 * scale
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    canvas.paste(
        resized,
        (
            round(canvas_size / 2 - subject_center_x),
            round(canvas_size / 2 - subject_center_y),
        ),
        resized,
    )
    return canvas


def full_square_around_subject(image: Image.Image, canvas_size: int, subject_ratio: float) -> Image.Image:
    subject = subject_layer(image)
    bbox = subject.getbbox()
    if bbox is None:
        return Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    return place_image(image, canvas_size, bbox, subject_ratio)


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
    subject_bbox = subject.getbbox()

    # In-app logo keeps the cut-out subject. Launcher layers use the whole
    # square artwork, scaled so no part of the subject is clipped.
    subject_foreground = (
        place_image(subject, ADAPTIVE_SIZE, subject_bbox, ADAPTIVE_SUBJECT_RATIO)
        if subject_bbox is not None
        else Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE), (0, 0, 0, 0))
    )
    subject_foreground.save(RES / "drawable-nodpi" / "ic_launcher_foreground.png")

    artwork = full_square_around_subject(source, ADAPTIVE_SIZE, ADAPTIVE_SUBJECT_RATIO)
    artwork.save(RES / "drawable-nodpi" / "ic_launcher_artwork.png")
    artwork.save(RES / "drawable-nodpi" / "ic_launcher_foreground_v31.png")
    artwork.save(RES / "drawable" / "ic_launcher_static_foreground.png")

    monochrome = Image.new("RGBA", subject_foreground.size, (255, 255, 255, 0))
    white = Image.new("RGBA", subject_foreground.size, (255, 255, 255, 255))
    monochrome.paste(white, (0, 0), subject_foreground.getchannel("A"))
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
