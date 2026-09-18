# -*- coding: utf-8 -*-
"""
9-patch 校验 / 重建（索尼 UI 换装的命门）。

背景：索尼固件里的 *.9.png 用的是 #DD7700 当 marker（甚至还整圈不透明），
AAPT2 直接拒收： "top-left corner pixel must be either opaque white or transparent"。
所以任何要进工程的 .9.png 都必须先过 check()；不合格就用 rebuild() 重画。

用法：
    python patch9.py check  <png> [<png> ...]
    python patch9.py rebuild <src> <dst> [--sx a,b] [--sy c,d] [--pad l,t,r,b]
                             [--no-pad]

9-patch 文件格式（aapt 读法）：
    左上角像素必须透明或纯白
    上边框：纯黑段 = 可横向拉伸区间
    左边框：纯黑段 = 可纵向拉伸区间
    右边框：纯黑段 = 内容 padding 的上下边界
    下边框：纯黑段 = 内容 padding 的左右边界
    边框像素只允许 透明(0,0,0,0) 或 纯黑(0,0,0,255)，其它颜色一律报错
"""

import sys
from PIL import Image


def _classify(p):
    r, g, b, a = p
    if a == 0:
        return "T"          # transparent
    if (r, g, b) == (0, 0, 0) and a == 255:
        return "K"          # pure black marker
    if (r, g, b) == (255, 255, 255) and a == 255:
        return "W"          # opaque white
    return "X"              # illegal


def _segments(marks):
    """把 ['T','K','K','T',...] 变成 [(起, 止), ...]（止为开区间），只取 K 段。"""
    segs, run = [], None
    for i, m in enumerate(marks + ["T"]):
        if m == "K" and run is None:
            run = i
        elif m != "K" and run is not None:
            segs.append((run, i))
            run = None
    return segs


def check(path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    top = [_classify(px[x, 0]) for x in range(w)]
    bot = [_classify(px[x, h - 1]) for x in range(w)]
    lft = [_classify(px[0, y]) for y in range(h)]
    rgt = [_classify(px[w - 1, y]) for y in range(h)]
    corner = _classify(px[0, 0])
    bad = sum(b.count("X") for b in (top, bot, lft, rgt))
    inner = im.crop((1, 1, w - 1, h - 1))
    ok = bad == 0 and corner in ("T", "W")
    print("%-28s %4dx%-4d corner=%s bad=%3d %s" % (
        path.split("/")[-1], w, h, corner, bad, "OK" if ok else "!! REJECT"))
    if not ok:
        return False
    print("        stretchX=%s  stretchY=%s" % (
        _segments(top), _segments(lft)))
    print("        padding = left/right %s, top/bottom %s"%(
        _segments(bot), _segments(rgt)))
    print("        content=%dx%d  colors=%d" % (inner.size + (len(inner.getcolors(65536) or []),)))
    return True


def rebuild(src, dst, sx=None, sy=None, pad=None, content_pad=0):
    """
    索尼重构法（资源包 mk_lite_patch9.py 的做法，实机验证过）：
      ① 原图整体贴到 (1,1)，四周留 1px 边框
      ② 边框默认全透明
      ③ 只在可拉伸区间画纯黑 marker
      ④ 四角保持透明 —— 整圈涂黑会把边框线也拉伸，边缘线就错位了
    sx/sy: 可拉伸区间 (起, 止)，像素坐标相对原图；不给就取"去掉四角圆角后的整段"
    pad:   内容 padding (l, t, r, b)；不给则填满（无 padding）
    """
    im = Image.open(src).convert("RGBA")
    w, h = im.size
    if sx is None:
        sx = (0, w)
    if sy is None:
        sy = (0, h)
    if pad is None:
        pad = (0, 0, 0, 0)

    out = Image.new("RGBA", (w + 2, h + 2), (0, 0, 0, 0))
    out.paste(im, (1, 1))
    o = out.load()

    for x in range(sx[0], sx[1]):                    # 上边框：横向可拉伸
        o[x + 1, 0] = (0, 0, 0, 255)
    for y in range(sy[0], sy[1]):                    # 左边框：纵向可拉伸
        o[0, y + 1] = (0, 0, 0, 255)
    l, t, r, b = pad
    if l or r:                                       # 下边框：左右 padding
        for x in range(l, w - r):
            o[x + 1, h + 1] = (0, 0, 0, 255)
    if t or b:                                       # 右边框：上下 padding
        for y in range(t, h - b):
            o[w + 1, y + 1] = (0, 0, 0, 255)

    out.save(dst)
    print("rebuild -> %s  %dx%d  stretchX=%s stretchY=%s pad=%s" % (
        dst.split("/")[-1], w + 2, h + 2, sx, sy, pad))
    return check(dst)


if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "check":
        bad = [p for p in a[1:] if not check(p)]
        sys.exit(1 if bad else 0)
    elif a and a[0] == "rebuild":
        kw = {}
        i = 3
        while i < len(a):
            if a[i] == "--sx":
                kw["sx"] = tuple(int(v) for v in a[i + 1].split(","))
            elif a[i] == "--sy":
                kw["sy"] = tuple(int(v) for v in a[i + 1].split(","))
            elif a[i] == "--pad":
                kw["pad"] = tuple(int(v) for v in a[i + 1].split(","))
            i += 2
        rebuild(a[1], a[2], **kw)
    else:
        print(__doc__)
