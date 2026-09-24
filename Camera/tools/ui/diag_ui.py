# -*- coding: utf-8 -*-
"""换装预览的诊断：把可疑部件单独放大渲染出来看。"""
import os
import sys
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mk_ui_preview as P

D = os.path.join(P.BASE, "diag")


def dump_rows(im, y0, y1, tag):
    px = im.load()
    print("-- %s: rows %d..%d 中非黑像素的水平范围" % (tag, y0, y1))
    for y in range(y0, y1):
        xs = [x for x in range(P.W) if sum(px[x, y][:3]) > 60]
        if xs:
            print("   y=%3d  x=%d..%d  n=%d  color=%s" % (y, xs[0], xs[-1], len(xs), px[xs[len(xs)//2], y]))


def save(im, name, zoom=3):
    if zoom != 1:
        im = im.resize((im.size[0] * zoom, im.size[1] * zoom), Image.NEAREST)
    p = os.path.join(D, name)
    im.convert("RGB").save(p)
    print("   ->", p)


def main():
    if not os.path.isdir(D):
        os.makedirs(D)

    print("=== ① s82_focus.9 拉伸到 601x82 ===")
    save(P.patch("s82_focus.9.png", P.ROW82_W, P.ROW82_H), "a_s82_focus.png", 1)
    print("=== ② row_focus.9 拉伸到 606x49 ===")
    save(P.patch("row_focus.9.png", P.LIST_W, P.ROW49), "b_row_focus.png", 1)

    print("=== ③ SetMenu 面板拉伸到 518x480 ===")
    pan = P.patch("set_panel.9.png", P.PANEL_W, 480)
    save(pan, "c_set_panel.png", 1)
    px = pan.load()
    print("   逻辑 x=258 的纵向剖面（每 16 行取一点）:")
    for y in range(0, 480, 16):
        print("     y=%3d %s" % (y, px[258, y]))

    print("=== ④ 配对页提示行文字（原尺寸 2 倍）===")
    s = u"在手机端输入此码 · 剩余 137 秒 · 已配对 1 台"
    t = P.text(s, 28, P.C_DIS, 0.7199)
    print("   渲染尺寸", t.size)
    save(t, "d_hint_text.png", 2)

    print("=== ⑤ 主屏 wifi 未连接 的标题带 ===")
    im = Image.new("RGBA", (P.W, P.H), (0, 0, 0, 255))
    P.screen_main(im, "wifi", None, "off")
    dump_rows(im, 255, 292, "caption band")
    save(im.crop((60, 240, 580, 300)), "e_caption.png", 2)


if __name__ == "__main__":
    main()
