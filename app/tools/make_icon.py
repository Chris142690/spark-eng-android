# -*- coding: utf-8 -*-
"""把 GPT 设计的图标做成 Android 启动图标资源。

输入：一张正方形 RGB 图标（黑底满幅）。
输出（写入 android/res/）：
  mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png            传统方形图标（启动器自行裁形）
  mipmap-{...}dpi/ic_launcher_round.png                  圆形图标（旧版启动器/圆形主题用）
  mipmap-{...}dpi/ic_launcher_foreground.png             自适应图标前景（108dp 画布，内容居中 72dp 安全区）
  mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml  自适应图标描述
  values/colors.xml                                      ic_launcher_background

只用 Pillow，不引入额外依赖。
"""
import os
import sys

from PIL import Image, ImageDraw

SRC = sys.argv[1] if len(sys.argv) > 1 else None
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")

# density -> (legacy, foreground)
SIZES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

BG = "#000000"  # 由 detect_bg() 按图标四角颜色覆盖，避免自适应的背景层与图不同色


def rounded(im, size, radius_ratio=0.22):
    """方图 + 圆角，供传统启动器（不裁形时也不会是硬直角）。"""
    out = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    res = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    res.paste(out, (0, 0), mask)
    return res


def circular(im, size):
    out = im.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    res = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    res.paste(out, (0, 0), mask)
    return res


def foreground(im, canvas):
    """108dp 画布：图占中间 72dp（66.7%），四周留白交给启动器裁形。"""
    inner = int(canvas * 72.0 / 108.0)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    art = im.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    off = (canvas - inner) // 2
    out.paste(art, (off, off), art)
    return out


def detect_bg(im):
    """取四角平均色当自适应图标背景：黑底图标得 #000000，白底图标得 #ffffff。"""
    w, h = im.size
    k = max(2, w // 40)
    pts = []
    for bx, by in ((0, 0), (w - k, 0), (0, h - k), (w - k, h - k)):
        pts.append(im.crop((bx, by, bx + k, by + k)).resize((1, 1), Image.LANCZOS).getpixel((0, 0)))
    r = sum(p[0] for p in pts) // 4
    g = sum(p[1] for p in pts) // 4
    b = sum(p[2] for p in pts) // 4
    return "#%02x%02x%02x" % (r, g, b)


def main():
    if not SRC or not os.path.exists(SRC):
        print("用法: python make_icon.py <图标.png>")
        return 2
    im = Image.open(SRC).convert("RGB")
    w, h = im.size
    if w != h:
        side = min(w, h)
        im = im.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side))
    bg = detect_bg(im)
    print("background: %s" % bg)
    for dens, (legacy, fg) in SIZES.items():
        d = os.path.join(RES, "mipmap-%s" % dens)
        os.makedirs(d, exist_ok=True)
        rounded(im, legacy).save(os.path.join(d, "ic_launcher.png"))
        circular(im, legacy).save(os.path.join(d, "ic_launcher_round.png"))
        foreground(im, fg).save(os.path.join(d, "ic_launcher_foreground.png"))
        print("mipmap-%s: %d/%d/%d" % (dens, legacy, legacy, fg))

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(anydpi, name + ".xml"), "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@color/ic_launcher_background" />\n'
                    '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                    '</adaptive-icon>\n')
        print("mipmap-anydpi-v26/%s.xml" % name)

    vals = os.path.join(RES, "values")
    os.makedirs(vals, exist_ok=True)
    colors = os.path.join(vals, "colors.xml")
    with open(colors, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="ic_launcher_background">%s</color>\n</resources>\n' % bg)
    print("values/colors.xml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
