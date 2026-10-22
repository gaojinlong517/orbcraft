#!/usr/bin/env python3
"""
生成 orbcraft 球物品的 16x16 贴图。

为什么用脚本而不是手画：贴图要跟着 UI 的金色主色走（ACCENT #FFC24B），
手改一次颜色等于重画一遍；脚本里改一个常量，重新跑就同步了。

渲染方式：先在 4 倍分辨率（64x64）上画带抗锯齿的形状，再降采样到 16x16。
Minecraft 物品贴图是硬像素风，但直接按 16x16 画圆形会有严重锯齿，
超采样能拿到「还是像素感、但边缘干净」的结果。
"""

from PIL import Image, ImageDraw, ImageFilter
import math
import os

SS = 4                 # 超采样倍数
SIZE = 16
BIG = SIZE * SS

# —— 调色（与 BuildScreenGui 的设计 token 同源）——
GOLD_CORE = (255, 233, 168)   # 球心高光
GOLD = (255, 194, 75)         # ACCENT 金
VIOLET = (124, 77, 255)       # 中段紫
DEEP = (46, 22, 92)           # 边缘深紫
OUTLINE = (16, 8, 34)         # 外描边，保证在任意背景上都看得清

OUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "fabric", "src", "main", "resources", "assets", "orbcraft",
    "textures", "item", "orb.png")


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def ramp(t):
    """
    从球心到边缘的配色。t=0 是球心，t=1 是球缘。
    分三段：金心 -> 紫 -> 深紫，让球在 16x16 里仍有体积感而不是一个色块。
    """
    if t < 0.30:
        return lerp(GOLD_CORE, GOLD, t / 0.30)
    if t < 0.68:
        return lerp(GOLD, VIOLET, (t - 0.30) / 0.38)
    return lerp(VIOLET, DEEP, (t - 0.68) / 0.32)


def main():
    img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
    px = img.load()

    cx = cy = (BIG - 1) / 2.0
    r_inner = (SIZE / 2.0 - 0.5) * SS      # 球体半径（留半格给描边）
    r_edge = r_inner + 0.85 * SS           # 描边外沿

    for y in range(BIG):
        for x in range(BIG):
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy)

            if d > r_edge:
                continue  # 球外，保持透明

            # —— 描边：单独一层，避免渐变把边缘染色稀释掉
            if d > r_inner + 0.28 * SS:
                px[x, y] = OUTLINE + (255,)
                continue

            t = min(1.0, d / r_inner)

            # —— 球体着色
            col = ramp(t)

            # —— 左上镜面高光：把光源固定在 (dx=-1, dy=-1) 方向，
            #     这样球拿在手上、掉在地上、飞在空中都是同一个朝向，不会闪
            nx, ny = dx / (r_inner + 1e-9), dy / (r_inner + 1e-9)
            light = max(0.0, -(nx * 0.72 + ny * 0.72))     # 单位向量点乘
            # 高光只出现在靠内的区域（t<0.55），边缘不受光，体积感才对
            spec = (light ** 2.4) * (1.0 - min(1.0, t / 0.55)) * 0.85
            col = lerp(col, (255, 255, 255), spec)

            # —— 外圈轻微压暗：模拟菲涅尔，让球看起来是圆的不是扁的
            rim = max(0.0, (t - 0.80) / 0.20) * 0.45
            col = lerp(col, DEEP, rim)

            px[x, y] = col + (255,)

    # 降采样到 16x16（LANCZOS 在缩小时保留边缘而不糊成一团）
    small = img.resize((SIZE, SIZE), Image.LANCZOS)

    # 描边降采样后会被稀释成半透明，这里把最外一圈非透明像素的 alpha 拉满，
    # 否则球在游戏里边缘会发灰
    sp = small.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = sp[x, y]
            if a > 0:
                sp[x, y] = (r, g, b, 255 if a >= 110 else a)

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    small.save(OUT_PATH, "PNG")

    print("写出:", OUT_PATH)
    print("尺寸:", small.size, "模式:", small.mode)


if __name__ == "__main__":
    main()
