#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从一张方形插画生成 Android / iOS 全尺寸应用图标。

用法:
    python3 tools/make_icons.py <源图路径> [项目根目录]

    <源图路径>   必需。方形插画（非正方形会居中裁切）
    [项目根目录]  可选，默认取本脚本所在目录的上一级

产出:
    - Android 自适应图标：背景 = 同图放大虚化，前景 = 约 62% 居中 + 边缘羽化
      （保证主体完整落在安全区内）
    - Android 传统图标：各密度 ic_launcher / ic_launcher_round（低版本与 OEM 兜底）
    - Play 商店 512、以及 iOS 全套尺寸（不透明、不预圆角）

依赖:
    pip install Pillow
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

# ---- 路径：全部由命令行参数/脚本位置推导，不要在此硬编码本机路径 ----
_DEFAULT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else _DEFAULT_ROOT
RES = os.path.join(ROOT, "app", "src", "main", "res")
ART = os.path.join(ROOT, "art", "icons")
SRC = sys.argv[1] if len(sys.argv) > 1 else None


def load_base():
    """新源图已无水印且人物居中：不再做防水印裁切，仅居中裁正方形。"""
    if not SRC:
        raise SystemExit(__doc__.strip() + "\n\n错误：缺少源图路径参数。")
    im = Image.open(SRC).convert("RGB")
    w, h = im.size
    side = min(w, h)
    left, top = (w - side) // 2, (h - side) // 2
    return im.crop((left, top, left + side, top + side))


def cover(im, size):
    """缩放并居中裁剪成 size×size"""
    w, h = im.size
    scale = max(size / w, size / h)
    nw, nh = max(size, int(w * scale + 0.5)), max(size, int(h * scale + 0.5))
    im2 = im.resize((nw, nh), Image.LANCZOS)
    left, top = (nw - size) // 2, (nh - size) // 2
    return im2.crop((left, top, left + size, top + size))


def make_foreground(base, canvas):
    """前景：图缩到安全区(约62%)居中，边缘羽化融入背景"""
    inner = int(canvas * 0.62)
    img = base.resize((inner, inner), Image.LANCZOS).convert("RGBA")
    feather = max(6, int(inner * 0.06))
    mask = Image.new("L", (inner, inner), 0)
    d = ImageDraw.Draw(mask)
    d.rectangle([feather, feather, inner - feather, inner - feather], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(feather / 2))
    img.putalpha(mask)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    off = (canvas - inner) // 2
    out.paste(img, (off, off), img)
    return out


def make_background(base, canvas):
    """背景：同图 cover 填充 + 高斯模糊，颜色与前景边缘自然衔接"""
    bg = cover(base, canvas).filter(ImageFilter.GaussianBlur(canvas * 0.06))
    return bg.convert("RGBA")


def circle_mask(im):
    size = im.size[0]
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def rounded_corners(im, radius_ratio=0.24):
    """给图标加圆角（圆角外透明），还原原图的圆角气质。"""
    im = im.convert("RGBA")
    size = im.size[0]
    r = int(size * radius_ratio)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def save(im, path, fmt="PNG"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if fmt == "JPEG":
        im.convert("RGB").save(path, "JPEG", quality=92)
    else:
        im.save(path, fmt)
    print("  ->", os.path.relpath(path, ROOT))


def main():
    base = load_base()
    print("源图处理后尺寸:", base.size)
    print("项目根目录:", ROOT)

    # ---------- Android 自适应图标（前景/背景分层） ----------
    ADAPTIVE = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]
    for density, size in ADAPTIVE:
        save(make_foreground(base, size), f"{RES}/mipmap-{density}/ic_launcher_foreground.png")
        save(make_background(base, size), f"{RES}/mipmap-{density}/ic_launcher_background.png")

    adaptive_xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )
    os.makedirs(f"{RES}/mipmap-anydpi-v26", exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(f"{RES}/mipmap-anydpi-v26/{name}", "w", encoding="utf-8") as f:
            f.write(adaptive_xml)
        print("  -> mipmap-anydpi-v26/" + name)

    # ---------- Android 传统图标（低版本/OEM 兜底） ----------
    LEGACY = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
    for density, size in LEGACY:
        sq = rounded_corners(cover(base, size))
        save(sq, f"{RES}/mipmap-{density}/ic_launcher.png")
        save(circle_mask(cover(base, size)), f"{RES}/mipmap-{density}/ic_launcher_round.png")

    # ---------- Play 商店 / 其它尺寸（带圆角） ----------
    for size, name in [(512, "play-512.png"), (1024, "android-1024.png"), (192, "android-192.png"), (144, "android-144.png")]:
        save(rounded_corners(cover(base, size)), f"{ART}/android/{name}")

    # ---------- iOS 全套（不透明、不预圆角） ----------
    IOS = [1024, 180, 167, 152, 120, 87, 80, 76, 60, 58, 40, 29, 20]
    for size in IOS:
        save(cover(base, size), f"{ART}/ios/AppIcon-{size}.png")

    print("DONE")


if __name__ == "__main__":
    main()
