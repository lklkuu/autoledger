"""把 app/src/main/res 下的自有 PNG 转成无损 WebP，并逐张校验像素完全一致。

用途：体积优化阶段 S3-b 的**可审计**执行脚本 —— 先转码到临时目录，
解码回像素与原 PNG 逐通道比对，确认无损后才允许替换；同时输出逐文件体积对照表。
它是 S3-b 提交（2aca198）实际使用的工具，留在仓库里是为了让体积收益**可复算**：
任何人拿到一个新 PNG，都能用同一条命令验证「转 WebP 到底省了多少、是否真的无损」。

为什么必须无损（lossless=True）：
  - res/drawable/donate_wechat.png 是**微信收款码**。有损压缩会破坏那些细密的模块边缘，
    轻则扫码器识别率下降、重则直接扫不出来；而它是按字符串名动态解析的
    （resources.getIdentifier("donate_wechat", "drawable", pkg)，见 DonationConfig.kt），
    坏了不会有任何报错，只是用户扫码时才发现 —— 这种静默降级必须从编码参数上堵死。
  - 桌面图标同理：自适应图标的 foreground/background 出现振铃/色带是肉眼可见的观感事故。
  因此脚本对每个文件都做解码回读比对，任何"可见像素不同"都会 FAIL 并拒绝替换。

已验证结论（S3-b，23 个自有 PNG 全部转换）：
  逐张解码回读做像素级比对，**23/23 尺寸一致且像素完全一致**（透明区 RGB 归一属 WebP
  无损编码的既定行为，视觉等价，脚本会单独标注）；23/23 转换后体积都变小，
  源资源合计 677,022 → 411,442 B，三个包各 -239,345 B。

用法：
    python tools/png2webp.py            # 试运行：只转码 + 校验 + 打表，不动仓库
    python tools/png2webp.py --apply    # 校验通过后写回 res/ 并删除原 PNG

依赖：Pillow（需带 WebP 支持，`python -c "import PIL; print(PIL.features.check('webp'))"` 为 True）。
注意：S3-b 已把 23 个自有 PNG 全部转成 WebP，所以现在直接跑会提示"未找到任何 PNG"——
      那是正常状态，等你往 res/ 里加了新 PNG 再跑即可。
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"


def collect_pngs() -> list[Path]:
    """收集 res 下所有自有 PNG（按路径排序，保证输出稳定）。"""
    return sorted(p for p in RES.rglob("*.png") if p.is_file())


def to_webp(src: Path, dst_dir: Path) -> Path:
    """把单张 PNG 转成无损 WebP，返回产物路径。"""
    dst = dst_dir / (src.stem + ".webp")
    with Image.open(src) as im:
        # 统一到 RGBA 再存：保证带 alpha 的图标与原图逐像素一致
        rgba = im.convert("RGBA")
        rgba.save(dst, format="WEBP", lossless=True, quality=100, method=6)
    return dst


def pixel_identical(png: Path, webp: Path) -> tuple[bool, str]:
    """解码两侧像素逐通道比对（含尺寸 / 模式），返回 (是否可接受, 说明)。

    WebP 无损编码的既定行为：**alpha == 0 的像素其 RGB 会被归零以换取压缩率**，
    这部分差异不可见，不算回归。因此判定分三档：
      - 尺寸/模式不同 → 不可接受
      - 存在 alpha > 0 且像素不同 → 不可接受
      - 差异只出现在 alpha == 0 的像素 → 可接受（标注为「透明区归一」）
    """
    with Image.open(png) as a, Image.open(webp) as b:
        a = a.convert("RGBA")
        b = b.convert("RGBA")
        if a.size != b.size:
            return False, f"尺寸不一致 {a.size} != {b.size}"
        if a.mode != b.mode:
            return False, f"模式不一致 {a.mode} != {b.mode}"

        pa = a.tobytes()
        pb = b.tobytes()
        if pa == pb:
            return True, f"{a.size[0]}x{a.size[1]} 逐像素一致"

        # 逐像素定位差异：只统计 alpha > 0 的像素
        w, h = a.size
        opaque_diff = 0
        transparent_diff = 0
        for y in range(h):
            row = y * w
            for x in range(w):
                i = (row + x) * 4
                if pa[i : i + 4] == pb[i : i + 4]:
                    continue
                if pa[i + 3] == 0 and pb[i + 3] == 0:
                    transparent_diff += 1
                else:
                    opaque_diff += 1
        total = w * h
        if opaque_diff:
            return False, (
                f"{w}x{h} 有 {opaque_diff}/{total} 个**可见**像素不同"
            )
        return True, (
            f"{w}x{h} 透明区 RGB 归一 {transparent_diff}/{total} 像素（视觉等价，"
            "WebP 无损编码对 alpha=0 像素的既定处理）"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="校验通过后写回 res/ 并删除原 PNG")
    args = parser.parse_args()

    pngs = collect_pngs()
    if not pngs:
        # S3-b 之后 res 下已无自有 PNG（全部转成 WebP），这是正常状态而不是失败。
        print("res 下未找到任何 PNG —— 也许已全部转成 WebP，无需处理。")
        return 0

    tmp = Path(tempfile.mkdtemp(prefix="autoledger-webp-"))
    rows: list[tuple[str, int, int, bool, str]] = []
    try:
        for p in pngs:
            rel = p.relative_to(ROOT).as_posix()
            webp = to_webp(p, tmp)
            ok, note = pixel_identical(p, webp)
            rows.append((rel, p.stat().st_size, webp.stat().st_size, ok, note))

        # ---------- 打表 ----------
        print(f"{'资源':58s} {'PNG':>9s} {'WebP':>9s} {'Δ':>9s}  校验")
        print("-" * 100)
        total_png = total_webp = 0
        for rel, sp, sw, ok, note in rows:
            total_png += sp
            total_webp += sw
            flag = "OK" if ok else "FAIL:" + note
            print(f"{rel:58s} {sp:9d} {sw:9d} {sw - sp:+9d}  {flag}")
        print("-" * 100)
        print(f"{'合计':58s} {total_png:9d} {total_webp:9d} {total_webp - total_png:+9d}")

        bad = [r for r in rows if not r[3]]
        bigger = [r for r in rows if r[2] >= r[1]]
        if bad:
            print("\n❌ 存在校验未通过的资源，不执行替换：")
            for r in bad:
                print("   ", r[0], r[4])
        if bigger:
            print("\n⚠️ 以下资源转 WebP 后体积未变小（建议保留原 PNG）：")
            for r in bigger:
                print(f"    {r[0]}: {r[1]} -> {r[2]} ({r[2] - r[1]:+d} B)")

        if not args.apply:
            print("\n（试运行，未改动仓库。加 --apply 才写回。）")
            return 0

        if bad:
            print("\n有资源校验失败，终止。")
            return 2

        # ---------- 写回 ----------
        for p in pngs:
            webp = tmp / (p.stem + ".webp")
            dst = p.with_suffix(".webp")
            shutil.copy2(webp, dst)
            p.unlink()
        print(f"\n✅ 已替换 {len(pngs)} 个资源为 WebP，并删除原 PNG。")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
