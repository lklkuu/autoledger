# -*- coding: utf-8 -*-
"""从参考图生成 App 全分辨率启动图标（adaptive icon + legacy 预合成）。

用法：
  python tools/gen_icons.py

背景：此前 mipmap 只有 mdpi/hdpi 两档（48/72px），高分屏设备放大显示导致桌面图标模糊。
本脚本用高清参考图（1244x1244 透明底猫娘）重建全部分辨率：
  - adaptive icon（Android 8+）：108dp 画布的前景（内容占 70%，居中于安全区）+ 纯色底
  - legacy：48dp 基准的预合成方图 + 圆形图（旧设备/设置页/通知大图标用）
"""
from PIL import Image, ImageDraw
import os

SRC = r"F:/WorkBuddy/2026-09-26-18-53-56/anime-app-icon-transparent_assets/6d098c9c-anime-app-icon-transparent.png"
RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "app", "src", "main", "res")

# 密度 → 倍率（mdpi=1 基准）
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# 源图（模块级加载：make_foreground / compose_legacy 都要用它）
src = Image.open(SRC).convert("RGBA")

# adaptive 前景内容占画布比例：安全区 72/108 ≈ 66.7%，取 70%（边缘裁极少量，主体更饱满）
FG_CONTENT_RATIO = 0.70
# legacy 预合成内容占比（预合成图没有遮罩二次裁切，可更饱满）
LEGACY_CONTENT_RATIO = 0.92


def avg_bg_color(im):
    """取四角不透明像素的平均色作为图标底色；四角全透明则取顶部中央（主体圆的边缘）。"""
    w, h = im.size
    box = max(24, w // 30)
    samples = []
    for x0, y0 in [(0, 0), (w - box, 0), (0, h - box), (w - box, h - box)]:
        region = im.crop((x0, y0, x0 + box, y0 + box))
        samples += [p for p in region.getdata() if p[3] > 128]
    if not samples:
        strip = im.crop((w // 2 - box, 0, w // 2 + box, box))
        samples = [p for p in strip.getdata() if p[3] > 128]
    if not samples:
        return (242, 174, 181)  # 兜底粉
    r = sum(p[0] for p in samples) // len(samples)
    g = sum(p[1] for p in samples) // len(samples)
    b = sum(p[2] for p in samples) // len(samples)
    return (r, g, b)


def fit_center(src, size):
    """等比缩放源图到 size x size 内（不裁切），返回新图。"""
    c = src.copy()
    c.thumbnail((size, size), Image.LANCZOS)
    return c


def make_foreground(size):
    """adaptive 前景：透明画布 + 居中主体（占 FG_CONTENT_RATIO）。"""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    content = fit_center(src, int(size * FG_CONTENT_RATIO))
    canvas.paste(content, ((size - content.width) // 2, (size - content.height) // 2), content)
    return canvas


def make_background(size, color):
    """adaptive 背景：主色纯色 + 轻微径向亮心（比纯平更有层次）。"""
    im = Image.new("RGBA", (size, size), color + (255,))
    glow = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(glow)
    r = int(size * 0.55)
    d.ellipse((size // 2 - r, size // 2 - r, size // 2 + r, size // 2 + r), fill=26)
    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    im = Image.composite(white, im, glow)
    return im


def compose_legacy(size, round_mask):
    """legacy 预合成：主色底 + 居中主体；round_mask=True 时裁圆形。"""
    im = Image.new("RGBA", (size, size), base_color + (255,))
    content = fit_center(src, int(size * LEGACY_CONTENT_RATIO))
    im.paste(content, ((size - content.width) // 2, (size - content.height) // 2), content)
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(im, (0, 0), mask)
        return out
    return im


def main():
    global base_color
    base_color = avg_bg_color(src)
    print(f"源图 {src.size}，底色 #%02X%02X%02X" % base_color)

    for name, scale in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        # adaptive：108dp 基准
        make_foreground(int(108 * scale)).save(os.path.join(d, "ic_launcher_foreground.png"))
        make_background(int(108 * scale), base_color).save(os.path.join(d, "ic_launcher_background.png"))
        # legacy：48dp 基准
        compose_legacy(int(48 * scale), round_mask=False).save(os.path.join(d, "ic_launcher.png"))
        compose_legacy(int(48 * scale), round_mask=True).save(os.path.join(d, "ic_launcher_round.png"))
        print(f"  mipmap-{name}: fg/bg {int(108*scale)}px, launcher {int(48*scale)}px")

    # 通知大图标（非 nodpi，放 drawable-xxxhdpi 一份即可，系统自行缩放）
    d = os.path.join(RES, "drawable-xxxhdpi")
    os.makedirs(d, exist_ok=True)
    big = compose_legacy(192, round_mask=True)
    big.save(os.path.join(d, "notify_large_icon.png"))
    print("  drawable-xxxhdpi/notify_large_icon.png 192px")

    # 状态栏 smallIcon：必须是**单色 alpha 蒙版**（彩色图会被系统渲染成白色色块）。
    # 做法：取主体 alpha 通道，填充纯白，输出到 drawable/（无需多分辨率，系统按 alpha 缩放）。
    d = os.path.join(RES, "drawable")
    os.makedirs(d, exist_ok=True)
    alpha = src.split()[3].resize((96, 96), Image.LANCZOS)
    silhouette = Image.new("RGBA", (96, 96), (255, 255, 255, 0))
    white = Image.new("RGBA", (96, 96), (255, 255, 255, 255))
    silhouette.paste(white, (0, 0), alpha)
    silhouette.save(os.path.join(d, "notify_small_icon.png"))
    print("  drawable/notify_small_icon.png 96px（白色剪影）")
    print("完成。")


if __name__ == "__main__":
    main()
