# -*- coding: utf-8 -*-
"""
把真机照片里的 LCD 归正到 518×480 面板坐标，逐元素量它的逻辑位置。

为什么费这个劲：用户说"设置的弹窗错位"，但手机拍的斜面照片上眼睛估不准 ——
先按面板的四条边做直线拟合求交点，再用这四个角做投影校正，才能把"错位多少像素"
量成数字。

已知锚点（面板素材真值）：
  左边框亮线  逻辑 x ≈ 63.5（面板左 62 + 素材内 1.5）
  右边框亮线  逻辑 x ≈ 575.5
  素材里第一条亮横线  逻辑 y = 23
  底部亮线（底部渐变的最后一行为最亮）逻辑 y = 480
"""
import sys
from PIL import Image

PHOTO = sys.argv[1] if len(sys.argv) > 1 else \
    r'C:\Users\93849\Documents\xwechat_files\wxid_5tiigybptsho22_e65e\temp\RWTemp\2026-09\9e20f478899dc29eb19741386f9343c8\f7ae4cf6a496946099fd331aa7db9b60.jpg'
OUT = r'C:\Users\93849\Desktop\SonyConnect\tools\ui\diag'


def fit(pts):
    """最小二乘拟合 y = a + b*x（pts 为 (x,y)）"""
    n = len(pts)
    sx = sum(p[0] for p in pts); sy = sum(p[1] for p in pts)
    sxx = sum(p[0] * p[0] for p in pts); sxy = sum(p[0] * p[1] for p in pts)
    d = n * sxx - sx * sx
    if d == 0:
        return None
    b = (n * sxy - sx * sy) / d
    a = (sy - b * sx) / n
    return a, b


def inter_xy(vline, hline):
    """竖线 x = a1 + b1*y 与横线 y = a2 + b2*x 的交点"""
    a1, b1 = vline
    a2, b2 = hline
    # x = a1 + b1*(a2 + b2*x) → x(1 - b1*b2) = a1 + b1*a2
    x = (a1 + b1 * a2) / (1 - b1 * b2)
    y = a2 + b2 * x
    return x, y


def main():
    im = Image.open(PHOTO).convert("L")
    W, H = im.size
    px = im.load()

    def bright(x, y):
        return px[x, y] > 120

    # 左边框：y 600..1100 每行找 140..175 内最左的亮点
    left = []
    right = []
    for y in range(620, 1100, 10):
        xs = [x for x in range(140, 178) if bright(x, y)]
        if xs:
            left.append((xs[0], y))
        xs = [x for x in range(1065, 1100) if bright(x, y)]
        if xs:
            right.append((xs[-1], y))
    # 上亮线：x 250..1000 每列找 500..580 内最上的亮点
    top = []
    bot = []
    for x in range(250, 1010, 20):
        ys = [y for y in range(495, 585) if bright(x, y)]
        if ys:
            top.append((x, ys[0]))
        ys = [y for y in range(1120, 1180) if bright(x, y)]
        if ys:
            bot.append((x, ys[-1]))

    # 竖线拟合用 x = a + b*y → 交换坐标
    fl = fit([(p[1], p[0]) for p in left])
    fr = fit([(p[1], p[0]) for p in right])
    ft = fit(top)
    fb = fit(bot)
    print("左边框 x=%.1f+%.5f*y   (%d 点)" % (fl[0], fl[1], len(left)))
    print("右边框 x=%.1f+%.5f*y   (%d 点)" % (fr[0], fr[1], len(right)))
    print("上亮线 y=%.1f+%.5f*x   (%d 点)" % (ft[0], ft[1], len(top)))
    print("下亮线 y=%.1f+%.5f*x   (%d 点)" % (fb[0], fb[1], len(bot)))

    TL = inter_xy(fl, ft)
    TR = inter_xy(fr, ft)
    BR = inter_xy(fr, fb)
    BL = inter_xy(fl, fb)
    print("四角 TL=%s TR=%s BR=%s BL=%s" % (tuple(round(v, 1) for v in TL),
                                            tuple(round(v, 1) for v in TR),
                                            tuple(round(v, 1) for v in BR),
                                            tuple(round(v, 1) for v in BL)))

    im2 = Image.open(PHOTO).convert("RGB")
    # PIL QUAD 顺序：NW, SW, SE, NE
    quad = (TL[0], TL[1], BL[0], BL[1], BR[0], BR[1], TR[0], TR[1])
    pan = im2.transform((518, 480), Image.QUAD, quad, Image.BICUBIC)
    pan.save(OUT + r"\photo_panel.png")

    # 归一化坐标 → 逻辑坐标：x 0..518 ↔ 63.5..575.5；y 0..480 ↔ 23..480
    def LX(n):
        return 63.5 + n * (512.0 / 518.0)

    def LY(n):
        return 23.0 + n * (457.0 / 480.0)

    g = pan.convert("L")
    gp = g.load()
    rgb = pan.load()
    print("\n归一化图里量到的元素（已换算成逻辑坐标）：")
    print("  亮横线(>150 列):")
    y = 0
    while y < 480:
        n = sum(1 for x in range(4, 514, 2) if gp[x, y] > 110)
        if n > 150:
            y0 = y
            while y < 480 and sum(1 for x in range(4, 514, 2) if gp[x, y] > 110) > 150:
                y += 1
            print("    归一 %3d..%3d  → 逻辑 y %.0f..%.0f" % (y0, y - 1, LY(y0), LY(y - 1)))
        y += 1

    print("  橙色焦点条:")
    ys = [y for y in range(480)
          if sum(1 for x in range(4, 514, 4)
                 if rgb[x, y][0] > 110 and rgb[x, y][0] - rgb[x, y][2] > 60) > 60]
    if ys:
        print("    归一 %3d..%3d  → 逻辑 y %.0f..%.0f" % (ys[0], ys[-1], LY(ys[0]), LY(ys[-1])))
        y = (ys[0] + ys[-1]) // 2
        xs = [x for x in range(0, 518)
              if rgb[x, y][0] > 110 and rgb[x, y][0] - rgb[x, y][2] > 60]
        print("    条的左右边界 → 逻辑 x %.0f..%.0f" % (LX(xs[0]), LX(xs[-1])))
    pan.save(OUT + r"\photo_panel.png")


if __name__ == "__main__":
    main()
