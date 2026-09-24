# -*- coding: utf-8 -*-
"""
相机端 SonyConnect 换装预览合成器（640×480，与相机帧缓冲 1:1）。

作用有两个：
  1. 先把每一屏合成出来看一眼，**认可之后才动工程**（资源包错误记录 #29 的教训：
     不预览就写工程，错误只会在真机上暴露）；
  2. 合成图就是后续 MAE 验收的基准图。

这里用的几何常量与素材，与随后写进 activity_main.xml / styles.xml 的完全一致。
9-patch 走真实的 marker 拉伸算法 —— 也就是顺便验证重建的 .9.png 是否合格。

字体的横向压缩 0.7001 用"渲染后按宽度缩放"实现，与 Android 的 TextView.setTextScaleX 等价。
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFont

BASE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BASE, "..", "..", "app", "app", "src", "main", "res", "drawable-nodpi")
ASSETS = os.path.join(BASE, "..", "..", "app", "app", "src", "main", "assets")
FONTS = r"C:\Users\93849\Desktop\SonyUI复刻包\02_素材\字体_全量"
OUT = os.path.join(BASE, "preview")

W, H = 640, 480                          # 帧缓冲真值

# ---- 原厂真值（出处：资源包 01_文档/08_layouts_kv.txt、02_colors.md、03_styles.md）----
SCALE = 0.7001                           # 全 UI 横向压缩
C_TEXT = (0xDD, 0xDD, 0xDD, 255)         # RESID_FONTSTYLE_STD_NORMAL
C_VAL = (0xDD, 0x66, 0x11, 255)          # 值列橙 —— **只留给配对码（PFontSize.H52W48）**
C_SEL = (0xDD, 0x66, 0x00, 255)          # 选中/焦点
C_DIS = (0x77, 0x77, 0x77, 255)          # 禁用
C_LINE = (0x22, 0x22, 0x22, 255)         # 分隔线
C_LINE2 = (0x33, 0x33, 255 & 0xFF, 255)  # 顶栏分隔线
C_FRAME = (0x55, 0x55, 0x55, 255)        # 主屏外框细线（示例图的元素，全 UI 唯一非原厂件）

HEADER_H = 66
FOOTER_H = 52
FOOTER_Y = 428                           # 640×52 @y428 → 428+52=480
# 正文高度**算出来**，不写死 362。
# ★ 这里踩过一个真坑：工程里各屏正文原本写死 362（66+362+52=480），删页码指示行时
#   顺手拆了菜单屏中间那层 362px 容器，算式少了一项 → 底栏被顶上去、屏幕底下多出
#   一条 30px 黑边。**预览图当时是对的**，因为 footer() 直接画在常量 FOOTER_Y=428 上，
#   根本不知道工程里的高度是拼出来的 —— 于是"预览通过、真机失败"。
#   现在工程里底栏一律靠 height=0+weight=1 锚底，预览这边把正文高度也改成算出来的，
#   两边都不再依赖手写算式。
BODY_H = H - HEADER_H - FOOTER_H         # 362
GUIDE_H = 36                             # PFontSize.H36W27
LIST_X, LIST_W, ROW49, DIV = 17, 606, 49, 2
PANEL_X, PANEL_W = 62, 518
SET_LIST_X, SET_LIST_W, SET_ROW = 82, 489, 49     # 列表 489 宽 @x82，行 473 由行布局自己定
ROW82_W, ROW82_H = 601, 82
BTN_W, BTN_H = 240, 56                   # 弹窗按钮真值
BTN68_H = 68                             # ScalarA 主屏按钮

# 图标字体里的私有区字形（Sony_DI_Icons）：
#   E80A = MENU 键帽   E803 = 返回箭头   E801 = 中键（实心圆）   E800 = 垃圾桶（机身 C2）
KEYS = {"menu": u"\uE80A", "back": u"\uE803", "center": u"\uE801",
        "enter": u"\uE801", "trash": u"\uE800"}
# 应用自身不内置字面（实机上"换字面"看不出差别，白占 750KB），走相机系统的字体回退链：
# Latin 由 UniversOTS-SJ 出，中文由 MYingHeiC-GB18030-SJ 出 —— 预览照抄这条链。
FONT_FIRMWARE = r"C:\Users\93849\Desktop\SonyUI复刻包\02_素材\字体_全量"
F_LATIN = os.path.join(FONT_FIRMWARE, "UniversOTS-SJ.ttf")
F_CJK = os.path.join(FONT_FIRMWARE, "MYingHeiC-GB18030-SJ.ttf")
F_ICON = os.path.join(ASSETS, "fonts", "icons.ttf")

_cache = {}


def font(path, px):
    k = (path, px)
    if k not in _cache:
        _cache[k] = ImageFont.truetype(path, px)
    return _cache[k]


def _is_cjk(ch):
    """该字符走中文字面（MYingHeiC）还是拉丁字面（UniversOTS）。
    注意把**全角标点**（U+FF00..FFEF：？：，）和中文标点（U+3000..303F）都算中文 ——
    只写死几个符号会漏掉"？"，于是它掉进拉丁字面渲染成方框（预览里踩过一次）。"""
    o = ord(ch)
    return (0x2E80 <= o <= 0x9FFF) or (0xF900 <= o <= 0xFAFF) \
        or (0x3000 <= o <= 0x303F) or (0xFF00 <= o <= 0xFFEF) \
        or o in (0x00B7, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2026)


def text(s, px, color=C_TEXT, scale=SCALE, icon=False, gap=8):
    """多行文字：按 '\\n' 分行，行距用原厂 lineSpacingExtra（38px 档 = 8px）。"""
    if u"\n" not in s:
        return _text_line(s, px, color, scale, icon)
    lines = [_text_line(t, px, color, scale, icon) for t in s.split(u"\n")]
    lines = [t for t in lines if t.size[0] > 1 or t.size[1] > 1]
    if not lines:
        return Image.new("RGBA", (1, 1), (0, 0, 0, 0))
    w, h = max(t.size[0] for t in lines), sum(t.size[1] for t in lines) + gap * (len(lines) - 1)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    y = 0
    for t in lines:
        out.paste(t, ((w - t.size[0]) // 2, y), t)
        y += t.size[1] + gap
    return out


def _text_line(s, px, color, scale, icon):
    """
    渲染一段文字，返回裁到墨迹的 RGBA 图。

    两个字面：ASCII 走 UniversOTS（索尼窄体拉丁面），中日韩走 MYingHeiC。
    两个必须踩住的点：
      ① 字面按**逐字符**判定，空格跟着前一个字面走 —— 曾经按"每段首字符"判定，
         于是" 已配对"因为首字符是空格而整段用了拉丁字体，中文全变豆腐块；
      ② 各段必须在**同一基线**上拼接：每段画进同高的临时画布（同一绘制原点），
         再用所有段墨迹的并集做纵向窗口统一切出来。按段各自的 bbox 下沿对齐会错位。
    """
    if not s:
        return Image.new("RGBA", (1, 1), (0, 0, 0, 0))
    if icon:
        runs = [(s, F_ICON)]
    else:
        runs = []
        for ch in s:
            path = (runs[-1][1] if runs else F_LATIN) if ch == u" " \
                else (F_CJK if _is_cjk(ch) else F_LATIN)
            if runs and runs[-1][1] is path:
                runs[-1] = (runs[-1][0] + ch, path)
            else:
                runs.append((ch, path))

    pad, h3 = px, px * 4
    tiles, y0, y1 = [], None, None
    for run, path in runs:
        f = font(path, px)
        adv = int(round(f.getlength(run))) + 1
        tmp = Image.new("RGBA", (adv + 2 * pad, h3), (0, 0, 0, 0))
        ImageDraw.Draw(tmp).text((pad, pad), run, font=f, fill=color)
        bb = tmp.getbbox()
        tiles.append((tmp, bb, adv))
        if bb:
            y0 = bb[1] if y0 is None else min(y0, bb[1])
            y1 = bb[3] if y1 is None else max(y1, bb[3])
    if y0 is None:
        return Image.new("RGBA", (1, 1), (0, 0, 0, 0))

    out = Image.new("RGBA", (sum(t[2] for t in tiles) + 2 * pad, y1 - y0), (0, 0, 0, 0))
    x = 0
    for tmp, bb, adv in tiles:
        if bb:
            piece = tmp.crop((bb[0], y0, bb[2], y1))
            out.paste(piece, (x + bb[0] - pad, 0), piece)
        x += adv
    out = out.crop(out.getbbox())
    w, h = out.size
    if scale != 1.0:
        out = out.resize((max(1, int(round(w * scale))), h), Image.LANCZOS)
    return out


def put(img, s, x, y, px, color=C_TEXT, align="l", scale=SCALE, icon=False):
    """align: l 左对齐(x=左) / r 右对齐(x=右) / c 居中(x=中心)"""
    t = text(s, px, color, scale, icon)
    if align == "r":
        x -= t.size[0]
    elif align == "c":
        x -= t.size[0] // 2
    img.paste(t, (int(x), int(y)), t)
    return t.size


def put_c(img, s, cx, cy, px, color=C_TEXT, scale=SCALE, icon=False):
    """以 (cx,cy) 为中心放文字（弹窗正文/按钮文字用，原厂是居中）。"""
    t = text(s, px, color, scale, icon)
    img.paste(t, (int(cx - t.size[0] / 2), int(cy - t.size[1] / 2)), t)
    return t.size


def asset(name):
    return Image.open(os.path.join(RES, name)).convert("RGBA")


def png(img, name, x, y):
    img.paste(img_ := asset(name), (int(x), int(y)), img_)


# ---------------- 9-patch 真实拉伸 ----------------
def _black(p):
    return p[3] == 255 and p[0] == 0 and p[1] == 0 and p[2] == 0


def _segs(marks):
    out, run = [], None
    for i, m in enumerate(list(marks) + [False]):
        if m and run is None:
            run = i
        elif not m and run is not None:
            out.append((run, i))
            run = None
    return out


def _axis(src_n, segs, dst_n):
    if not segs:
        segs = [(0, src_n)]
    total = float(sum(b - a for a, b in segs))
    extra = dst_n - src_n
    out, pos, acc = [], 0, 0.0
    for a, b in segs:
        out += list(range(pos, a))
        seglen = b - a
        newlen = seglen + int(round(extra * (acc + seglen) / total) - round(extra * acc / total))
        acc += seglen
        newlen = max(1, newlen)
        for i in range(newlen):
            out.append(a + (i * seglen) // newlen)
        pos = b
    out += list(range(pos, src_n))
    while len(out) < dst_n:
        out.append(src_n - 1)
    return out[:dst_n]


def patch(name, w, h):
    """按 9-patch marker 把 .9.png 拉伸到 w×h（内容区，不含 1px 边框）。

    边框上的 marker 是**文件坐标**，内容图去掉 1px 边框后坐标要 **-1** 才是内容行号
    （aapt 也是这么算的：文件行 82..89 → 可拉伸的内容行 81..88）。
    漏掉这个 -1 会让拉伸区多圈进一行 —— 面板那条"按键栏上方的分割线"正好落在边界上，
    就会被我拉成一条粗灰带（真机上就是这么出的粗白条）。"""
    im = asset(name)
    iw, ih = im.size
    px = im.load()
    sx = [(a - 1, b - 1) for a, b in _segs(_black(px[x, 0]) for x in range(iw))]
    sy = [(a - 1, b - 1) for a, b in _segs(_black(px[0, y]) for y in range(ih))]
    content = im.crop((1, 1, iw - 1, ih - 1))
    return _remap(content, sx, sy, w, h)


def _remap(content, sx, sy, w, h):
    cw, ch = content.size
    xs, ys = _axis(cw, sx, w), _axis(ch, sy, h)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src, dst = content.load(), out.load()
    rowcache = {}
    for j, sy_ in enumerate(ys):
        if sy_ not in rowcache:
            rowcache[sy_] = [src[i, sy_] for i in range(cw)]
        row = rowcache[sy_]
        for i, sx_ in enumerate(xs):
            dst[i, j] = row[sx_]
    return out


def frame(img, box, color=C_FRAME):
    d = ImageDraw.Draw(img)
    d.rectangle(list(box), outline=color)


# ==========================================================================
# 各屏
# ==========================================================================
def put_mc(img, s, x, cy, px, color=C_TEXT, scale=SCALE, icon=False):
    """左对齐 x、纵向以 cy 为**墨迹中心**（模拟 TextView 的 center_vertical）。"""
    t = text(s, px, color, scale, icon)
    img.paste(t, (int(x), int(cy - t.size[1] / 2.0)), t)
    return t.size


def put_rc(img, s, x, cy, px, color=C_TEXT, scale=SCALE, icon=False):
    """右对齐 x、纵向以 cy 为墨迹中心。"""
    t = text(s, px, color, scale, icon)
    img.paste(t, (int(x - t.size[0]), int(cy - t.size[1] / 2.0)), t)
    return t.size


def chrome(img, title, x=0, w=W, icon=None):
    """顶栏（ScalarA 系统应用族：bg_header66，黑底 + 底边 #333333 细线）。

    主界面与配对页用 {@link header_app}：应用图标 + 软件标题 + 右上电量，
    图标与标题**共用一条横轴**（都在 y=33）。这里保留的是"只有标题"那版
    （选项菜单 / 已配对设备 / 关于），标题同样以 y=33 为墨迹中心。
    """
    ic = asset("bg_header66.png").resize((w, HEADER_H), Image.LANCZOS)
    img.paste(ic, (x, 0), ic)
    put_mc(img, title, x + 20, 33, 42)


def header_app(img, pct=87, x=0, w=W, wifi=False):
    """主界面/配对页的顶栏：应用图标 70×55 @(6,5) + 标题 @x78 + 电量右对齐。

    wifi=True 时在**电量左边**补一颗原厂 Wi-Fi 指示（48×32 =
    `s_16_dd_parts_scalar_prin_wifi_header.png` 原样，不预挤压）。位置出处：
    原厂 `parts_titlebar_wifi.xml` —— 顶栏里 [标题 484 @10][wifi 48×32 @552]，
    也就是"标题与电量之间、贴着电量左边"那一颗。工程里由
    `refreshWifiIcon()` 在 phase==PH_RUNNING（连接就绪）时显示。

    ⚠️ 这里的 y 常量**不参与布局**，只用来画预览图；工程里靠
    LinearLayout(gravity=center_vertical) 保证图标与标题同心，把"对齐"这件事
    交给布局系统而不是靠算 margin（换字体/改行距都还能对得上）。
    """
    ic = asset("bg_header66.png").resize((w, HEADER_H), Image.LANCZOS)
    img.paste(ic, (x, 0), ic)
    img.paste(asset("ic_appicon.png"), (x + 6, 5))   # 70×55 = 原厂 AppIconView 真值
    put_mc(img, "SonyConnect", x + 78, 33, 42)
    icon = "bat_%d.png" % (4 if pct > 75 else 3 if pct > 50 else 2 if pct > 25 else 1)
    ix = x + w - 12 - 37
    png(img, icon, ix, 17)
    # 电量百分比：右对齐到图标左边 8px。它的**左边界**就是 Wi-Fi 图标该停的地方 ——
    # 工程里 [wifi_icon][battery_text][battery_icon] 是同级顺序排列，wifi 恒在整组左边，
    # 靠布局自己算；预览这边必须用实测文字宽度，写死坐标会像刚才那样压在百分比上。
    # 电量百分比：**正常白**（与电池图标同色），不是禁用灰 —— 用户要求。
    pct_w = put_rc(img, "%d%%" % pct, ix - 8, 33, 28, C_TEXT, 0.7199)[0]
    if wifi:
        png(img, "ic_wifi_header.png", ix - 8 - pct_w - 8 - 48, 17)


def footer(img, guide, x=0, w=W):
    """guide: [(图标字体串, 说明), ...] 右对齐排列；说明给空串就只画图标。"""
    f = asset("bg_footer52.png").resize((w, FOOTER_H), Image.LANCZOS)
    img.paste(f, (x, FOOTER_Y), f)
    cx = x + w - 8
    for glyphs, label in reversed(guide):
        if label:
            sz = put(img, label, cx, FOOTER_Y + 8, GUIDE_H, C_TEXT, "r")
            cx -= sz[0] + 6
        if glyphs:
            sz = put(img, glyphs, cx, FOOTER_Y + 4, GUIDE_H, C_TEXT, "r", icon=True)
            cx -= sz[0] + 14


def footer_icons(img, glyphs, x=0, w=W):
    """**只有图标、没有文字**的底栏 —— 菜单族的原厂做法。

    真机照片（原厂菜单屏）放大 6 倍看得很清楚：底栏就两个裸键帽图标，
    左=确定、右=返回箭头，一个中文字都没有。App 自有界面才是"图标 + 文案"
    （原厂主屏写的是"选项 / 退出设置"，有 guide_text_p1/emt 两个字符串资源）。
    """
    f = asset("bg_footer52.png").resize((w, FOOTER_H), Image.LANCZOS)
    img.paste(f, (x, FOOTER_Y), f)
    cx = x + w - 10
    n = len(glyphs)
    for i, g in enumerate(reversed(glyphs)):
        sz = put(img, g, cx, FOOTER_Y + 4, GUIDE_H, C_TEXT, "r", icon=True)
        cx -= sz[0] + (2 if i < n - 1 else 16)   # 图标之间**挨着**（原版如此）


def main_btn(img, s, x, y, focused, w=264, h=56):
    p = patch("btn_focus.9.png" if focused else "btn_normal.9.png", w, h)
    img.paste(p, (int(x), int(y)), p)
    put_c(img, s, x + w / 2.0, y + h / 2.0, 42, C_TEXT)


def hotspot_block(img, top, ssid="DIRECT-xy:ILCE-6300", psk="1234567890"):
    """**热点信息块**：左=热点名称与密码（32px 单行），右=二维码 + 说明。

    主界面与配对页**逐像素相同**（同一块 640×480 屏、同一份数据），所以画法只有一份。
    文字宽 400 —— 实测 "热点名称：DIRECT-xy:ILCE-6300" 在 32px/0.7001 下 332px，余量 17%；
    二维码 160×213 @x452（预挤压，显示出来是正方形），说明行止于 266 < 按钮行 270。
    """
    put(img, u"热点名称：" + ssid, 24, top + 76, 32, C_TEXT, scale=0.7001)
    put(img, u"热点密码：" + psk, 24, top + 136, 32, C_TEXT, scale=0.7001)
    qr = _fake_qr(21).resize((160, 213), Image.NEAREST)
    img.paste(qr, (452, top + 24))
    put_c(img, u"扫码加入热点", 452 + 80, top + 240 + 13, 26, C_DIS, 0.7499)


def status_block(img, top, status):
    """**服务状态块**：38px 无行距（FontSize.H38）+ 上内边距 20，6 行止于 260 < 270。

    第一行是状态行：处于配对模式时末尾带"（配对模式）"（用户定版，见 buildMainStatusText）。
    """
    y = top + 20
    for s in (status or "状态：运行中").split("\n"):
        put(img, s, 28, y, 38, C_TEXT)
        y += 44


def screen_main(img, mode, phone, state, block="diagram", pct=87, status=None):
    """mode: '' / 'hotspot' / 'wifi';  state: 'on'/'off';  block: diagram/hotspot/status

    整幅 640 宽（原厂没有外框）：顶栏 66 + 正文 362 + 底栏 52。
    内容区上下的两条 #333333 细线来自素材本身（顶栏底边 / 底栏顶边）。
    图标区坐标全部照「同步到智能手机」的 main_layout_panel_default.xml（减去 66）。
    """
    header_app(img, pct, wifi=(mode != ""))

    top = HEADER_H

    if block == "diagram":
        # 相机 140×140 @屏(80,100)；手机 140×140 @屏(420,100)；点阵 180×40 @屏(230,157)
        png(img, "ic_sync_camera.png", 80, top + 34)
        png(img, "ic_sync_phone_%s.png" % ("on" if state == "on" else "off"), 420, top + 34)
        # 点阵恒为 connect_off 的 6 个暗点（原版照片里手机已连上时也是 6 个点；
        # connect_on 的"4 点 + 箭头"是原厂传送中页用的，主屏不换）
        # 点阵：没连上是原厂那 6 个暗点，连上了**同一张图拉亮**（用户要"六个点亮起"）
        png(img, "ic_sync_link_%s.png" % ("on" if state == "on" else "off"), 230, top + 91)
        cap = "连接方式"
        if mode == "hotspot":
            cap = "相机热点"
        elif mode == "wifi":
            cap = "Wi-Fi已连接" if state == "on" else "Wi-Fi未连接"
        # 信息行：240×46 @(28,185) / @(370,185)，各自居中；墨迹中心 = top+185+23
        put_c(img, cap, 28 + 120, top + 208, 42, C_TEXT)
        put_c(img, phone or "未连接", 370 + 120, top + 208, 42,
              C_TEXT if phone else C_DIS)
    elif block == "hotspot":
        hotspot_block(img, top)
    elif block == "status":
        status_block(img, top, status)

    # --- 按钮行：264×56 @x49 / x327（原厂真值），整幅居中即左右各留 49 ---
    bx = (W - (264 * 2 + 14)) // 2
    by = top + 270
    if mode == "hotspot":
        b1 = "热点信息"
    elif mode == "wifi":
        b1 = "Wi-Fi 设置"
    else:
        b1 = "连接方式"
    main_btn(img, b1, bx, by, True)
    main_btn(img, "服务状态", bx + 264 + 14, by, False)

    # --- 下方那束辉光：640×298 @(0,130)，贴正文底往上铺满（原厂槽位）---
    # 画在按钮之后、底栏之前 = 与工程里"main_body 最后一个子节点"的层序一致。
    png(img, "ic_sync_glow.png", 0, 130)

    # --- 底栏：垃圾桶键打开选项菜单，MENU 直接退出软件（用户定版）---
    footer(img, [(KEYS["trash"], "选项"), (KEYS["menu"], "退出")])


def _fake_qr(n):
    """预览用的占位二维码（真机上是 QrCode.java 现算的）。
    注意 PIL 里 fill 必须给元组：给整数会被当成打包色值，画出来是红的。"""
    blk, wht = (0, 0, 0), (255, 255, 255)
    img = Image.new("RGB", (n + 8, n + 8), wht)
    d = ImageDraw.Draw(img)
    seed = [12345]

    def rnd():
        seed[0] = (seed[0] * 1103515245 + 12345) & 0x7FFFFFFF
        return seed[0]

    for y in range(n):
        for x in range(n):
            if rnd() % 100 < 45:
                d.point((x + 4, y + 4), fill=blk)
    for ox, oy in ((0, 0), (n - 7, 0), (0, n - 7)):
        d.rectangle([ox + 4, oy + 4, ox + 10, oy + 10], fill=blk)
        d.rectangle([ox + 5, oy + 5, ox + 9, oy + 9], fill=wht)
        d.rectangle([ox + 6, oy + 6, ox + 8, oy + 8], fill=blk)
    return img


def list_rows(img, rows, y):
    """49px 行 + 2px 分隔线的通用列表（选项菜单 / 已配对设备共用同一套几何）。

    ★ 值列是**白色**，不是橙色。原厂菜单屏的照片直接量过：值列("原始""每次")最亮
      像素均值 RGB=(252,251,248)，标签列 (255,255,255) —— 两者同一个色相，都没有橙味。
      之前用 #DD6611 是把 ScalarA 系统应用族的"值列橙"错安到了框架菜单上。
    """
    for i, (a, b) in enumerate(rows):
        if i == 0:
            r = patch("row_focus.9.png", LIST_W, ROW49)
            img.paste(r, (LIST_X, y), r)
        put(img, a, LIST_X + 10, y + 8, 42, C_TEXT)
        if b:
            put(img, b, LIST_X + LIST_W - 10, y + 6, 38, C_TEXT, "r")
        y += ROW49
        if i < len(rows) - 1:
            ImageDraw.Draw(img).rectangle([LIST_X, y, LIST_X + LIST_W - 1, y + DIV - 1], fill=C_LINE)
            y += DIV
    return y


def screen_menu(img):
    """选项菜单：**没有页码指示那条**（只有一页，原厂那条 36×44 的"1"已按用户要求删掉），
    列表 302 高、上下各留 30px 居中 → y = 66+30 = 96。"""
    header_app(img, 87, wifi=True)
    list_rows(img, [("连接设置", "未设置"), ("进入配对模式", ""), ("已配对设备", "无"),
                    ("关于", ""), ("退出应用程序", "")], HEADER_H + 30)
    footer_icons(img, [KEYS["menu"], KEYS["back"]])   # MENU 键帽 + 返回箭头（无文字）


def screen_paired(img, devices=(("Redmi 14R 5G", "3f9a1c07"),
                                ("XQ-DC72", "a71b04c9"),
                                ("SM-A556E", "1d6f0b2a"))):
    """已配对设备：**与选项菜单同一套样式**（同一 PageMenu 几何），内容换成设备清单。
    ListView 自带滚动 —— 设备很多时靠它，所以这里只画 3 行示意（列表高 302 能放 5 行）。

    值列写**手机的设备码前 8 位**（用户要求：原来"ENTER 解除"那个位置，只写码不加字）——
    同型号两台手机靠它区分。解除的操作提示在底栏（中键图标）。
    显示只截前 8 位（相机端 shortCode()），协议与配对表里仍是完整 16 位。

    一台都没有时（devices 传空）：工程里那**唯一一行**是"无已配对设备"（值列空）——
    文案是用户定版的，原先写成"（尚无已配对设备）"。"""
    header_app(img, 87, wifi=True)
    if devices:
        rows = [(d[0], d[1]) for d in devices]
    else:
        rows = [(u"无已配对设备", "")]
    list_rows(img, rows, HEADER_H + 30)
    # 底栏：[中键圆点] 解除绑定   [MENU 键帽 + 返回箭头]
    # 圆点单看没有含义，所以这一项带文字；后者是菜单族惯用的图标对。
    footer(img, [(KEYS["center"], u"解除绑定"), (KEYS["menu"] + KEYS["back"], "")])   # MENU 键帽 + 返回箭头（无文字）


def screen_mode(img, can_back=True):
    """连接设置 = 原厂 menu_set.xml 的 SetMenu 弹窗（几何逐项对齐 dump 出来的真值）：
    遮罩 #88000000；面板 518×480 @x62 只是装饰件；标题 46 高 @面板(13,29)；
    列表 489×302 @面板(20,95) paddingRight 11；行 473×49 带前导单选圆点 21×28 + 标签 marginLeft 16；
    按键引导 500×40 @(70,413) 右对齐。

    **它是浮层**：底下那一屏要透出来（原厂就是悬在菜单之上，能看见下层菜单），
    所以先把下层菜单画了再压遮罩、再画面板。

    can_back：MENU 后面那块是"返回箭头"还是"退出"两个字 —— 判据是**有没有上一层级**
    （用户定版的 MENU 铁律）。首启直接弹出来时它就是第一层，MENU = 退出软件。

    面板素材的 4 条线由 9-patch 的拉伸标记决定落点（patch() 读的就是文件里的标记）：
    内容行 22 / 79 原样，89 落在 409（按键栏上方那条分割线），134 落在 454（按键栏下方那条）。"""
    screen_menu(img)                       # 下层：选项菜单（浮层不把它盖成黑的）
    # 遮罩必须 alpha 合成：ImageDraw 的 fill 是**直接覆盖**，会把下层菜单涂黑
    img.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 0x88)))
    p = patch("set_panel.9.png", PANEL_W, 480)
    img.paste(p, (PANEL_X, 0), p)
    put(img, "连接设置", PANEL_X + 13, 29 + 8, 42, C_TEXT)
    items = [(u"连接到 Wi-Fi 网络", True), (u"使用相机热点", False)]   # Wi-Fi 设置那行已删
    y = 95
    for i, (label, radio) in enumerate(items):
        if i == 0:
            r = patch("row_focus.9.png", 473, SET_ROW)
            img.paste(r, (SET_LIST_X, y), r)
        png(img, "radio_on.png" if radio else "radio_off.png",
            SET_LIST_X, y + (SET_ROW - 28) // 2)
        put(img, label, SET_LIST_X + 21 + 16, y + 8, 42, C_TEXT)
        y += SET_ROW
        if i < len(items) - 1:
            ImageDraw.Draw(img).rectangle(
                [SET_LIST_X, y, SET_LIST_X + 489 - 11 - 1, y + DIV - 1], fill=C_LINE)
            y += DIV
    cx = 70 + 500
    for g in (([KEYS["back"]] if can_back else []) + [KEYS["menu"]]):
        sz = put(img, g, cx, 413, GUIDE_H, C_TEXT, "r", icon=True)
        cx -= sz[0] + 2
    if not can_back:
        # 图标字体的私有区里没有汉字，所以"退出"两个字是另一个控件（工程里是两个 TextView）
        sz = put(img, u"退出", cx, 413 + 2, GUIDE_H, C_TEXT, "r")
        cx -= sz[0] + 6


def screen_pairing(img, code="314 159", left=137, block="code", status=None, can_back=False):
    """配对模式：**界面结构与主界面一致**（上下两条 #333333 细线、应用图标+软件名+
    右上电量、底部按键引导都不变），正文换成三块互斥内容 + 一排常驻按钮。

    设备清单**不在这一页** —— 配对与"已配对设备"是两个界面（用户要求）。
    字号按用户"字放大一点"调过：码 52（原厂最大档）、倒计时 44、提示 34。
    倒计时结束后**自动换一张新码继续**（不再有"配对已结束"这一态）。

    ★ 用户定版：配对页也要有**与主界面同款的两颗按钮**，所以正文块与主界面同构 ——
      block="code"（默认）6 位码 + 倒计时 + 提示；"hotspot" 热点信息；"status" 服务状态。
      第 0 块整块在按钮行（270）以上居中：三行合计约 176px，上下各留 47。
      服务状态块的第一行在配对模式下带"（配对模式）"。
    """
    header_app(img, 87, wifi=True)
    if block == "code":
        cd_h, hint_h = 50, 38
        blk_h = 65 + 26 + cd_h + 16 + hint_h
        y = HEADER_H + (270 - blk_h) // 2
        put_c(img, code, W / 2.0, y + 65 / 2.0, 52, C_VAL, 0.8846)
        y += 65 + 26
        put_c(img, u"此配对码有效期剩余 %d 秒" % left, W / 2.0, y + cd_h / 2.0, 44, C_TEXT)
        y += cd_h + 16
        put_c(img, u"在手机端输入配对码", W / 2.0, y + hint_h / 2.0, 34, C_DIS, 0.7001)
    elif block == "hotspot":
        hotspot_block(img, HEADER_H)
    elif block == "status":
        status_block(img, HEADER_H, status)

    # 常驻按钮行：与主界面同款同坐标（左键文案随连接方式变，这里预览热点模式）
    bx = (W - (264 * 2 + 14)) // 2
    main_btn(img, "热点信息", bx, HEADER_H + 270, True)
    main_btn(img, "服务状态", bx + 264 + 14, HEADER_H + 270, False)

    # 底栏按键引导：**垃圾桶键 = 菜单**（带"选项"字样，与主界面同款），
    # MENU 后面那块**随 MENU 的实际作用变**（用户定版：功能变了提示也要变）：
    #   · can_back=True（用户自己从菜单"进入配对模式"进来的，本机有设备）→ 返回箭头；
    #   · can_back=False（本机没有已配对设备、应用把配对页立成家）→ "退出"两个字。
    if can_back:
        footer(img, [(KEYS["trash"], u"选项"), (KEYS["menu"] + KEYS["back"], "")])
    else:
        footer(img, [(KEYS["trash"], u"选项"), (KEYS["menu"], u"退出")])


def screen_pairing_renewed(img):
    """倒计时走完 → **自动换一张新码**继续配对（用户要求），不是停掉配对。"""
    screen_pairing(img, code="908 442", left=180)


def screen_about(img):
    """关于：照原厂那种"整块居中"的正文版式（用户给的参考图）。
    三行 —— 应用名/版本、开发者、**本机设备码**（运行期由 updateAboutText() 填，
    取的就是配对表落盘的 16 位小写 hex，样例里用一个像样的假码）。"""
    header_app(img, 87, wifi=True)
    put_c(img, u"SonyConnect 2.0\n开发者：BI2QFA\n本机设备码：3f9a1c07",
          W / 2.0, HEADER_H + BODY_H / 2.0 - 18, 38, C_TEXT)
    footer_icons(img, [KEYS["menu"], KEYS["back"]])


def wrap_lines(s, px, max_w):
    """把一行按**渲染后宽度**拆成多行 —— 复现 TextView 在屏幕边缘的自动折行。

    ★ 这一步是必须的：预览工具原来只会"画字"，一行超出画布就被裁掉，于是预览图看着
      "文字被屏幕边缘挡上了"，而真机是会折行的（TextView 宽度受 ScrollView 约束，
      长路径这种没有空格的词也会被 emergency-break 拆开）。预览不折行 = 预览撒谎。
    """
    out = []
    cur = u""

    def w(t):
        return text(t, px, C_TEXT, SCALE).size[0]

    for ch in s:
        if ch == chr(10):
            out.append(cur)
            cur = u""
            continue
        if cur and w(cur + ch) > max_w:
            out.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        out.append(cur)
    return out


def screen_log(img, lines=None):
    """调试日志屏（隐藏入口：关于页连按十下确定键）。正文 26px（`FsH26`，多行无行距）
    铺在 ScrollView 里 —— 一屏约 13 行，超出靠滚动；进屏自动滚到最底（最新一条）。
    这里画的是"刚连上手机"那种典型回合，用来核对 26px 下一行能放多少字。"""
    if lines is None:
        lines = [
            u"09-13 18:01:02.113 I/Life onCreate：上次连接方式=hotspot",
            u"09-13 18:01:02.240 I/Mode applyMode：hotspot",
            u"09-13 18:01:02.301 I/Radio 请求打开 Wi-Fi 总开关",
            u"09-13 18:01:03.502 I/Radio 事件 WIFI_STATE_CHANGED wifiState=3",
            u"09-13 18:01:04.118 I/Radio Direct 状态 0 → 3",
            u"09-13 18:01:05.077 I/Radio 热点就绪：SSID=DIRECT-xxxx:ILCE-6300 IP=192.168.122.1",
            u"09-13 18:01:05.180 I/Net 服务端启动：协议端口=15740 文件端口=15741 UDP探测已开",
            u"09-13 18:01:28.664 I/Net 协议端口接入 /192.168.122.2 conn#1",
            u"09-13 18:01:28.690 I/Net Init Cmd Req：手机=Redmi 14R 5G 设备码=2ed6052d970d5ce2 协议版本=65536",
            u"09-13 18:01:28.702 I/Pair 配对成功：手机=Redmi 14R 5G",
            u"09-13 18:01:28.711 I/UI 切屏 配对页 → 主界面",
            u"09-13 18:01:29.020 I/Net 命令 0x1002 LIST_DIR(列目录)",
            u"09-13 18:01:29.044 I/File 列目录 /DCIM/100MSDCF → 361 项",
            u"09-13 18:01:29.310 I/Net 命令 0x1003 GET_OBJECT(取对象)",
            u"09-13 18:01:29.412 I/Net 开始传输 /DCIM/100MSDCF/DSC00001.ARW kind=1 offset=0 len=24985600",
            u"09-13 18:01:31.220 I/Net 命令 0x1010 THUMB_BEGIN(缩略图预取)",
            u"09-13 18:01:31.226 I/Thumb 预取开始：共 361 张",
        ]
    header_app(img, 87, wifi=True)
    y = HEADER_H + 4
    x, px, max_w = 8, 26, W - 16          # 与布局一致：ScrollView 左右 padding 各 8px
    for ln in lines:
        for seg in wrap_lines(ln, px, max_w):
            if y + px > FOOTER_Y:         # 画到内容区底就停（真机这里可以继续滚）
                break
            put(img, seg, x, y, px, C_TEXT)
            y += px
        if y + px > FOOTER_Y:
            break
    footer_icons(img, [KEYS["menu"], KEYS["back"]])


def dlg_footer(img):
    """弹窗的按键引导条：**与其余各屏同款同高**（bg_footer52 640×52 贴底 = y428）。

    ★ 用户指出"弹窗的提示栏被加高了"：原厂对话框那份 p_footer 是 640×60 @y420，
      比正常状态的底栏高 8px 也上移 8px，一眼就看出来不齐。现在与正常底栏逐项相同。
      浮层的 MENU 只会"关掉浮层、回到它压着的那一屏"，所以后面恒是返回箭头。"""
    png(img, "bg_footer52.png", 0, FOOTER_Y)
    cx = W - 10
    for g in (KEYS["back"], KEYS["menu"]):          # 右→左：返回箭头、MENU 键帽
        sz = put(img, g, cx, FOOTER_Y + 4, GUIDE_H, C_TEXT, "r", icon=True)
        cx -= sz[0] + 2


def screen_exiting(img):
    """正在退出 / 正在切换模式：**全屏黑底 + 四边外框**（用户："我要的是那个带四边边框的
    全屏退出界面，下面没有提示栏的那个"）。

    外框就是最开始那一版主界面用的那圈 1px 细线（`panel_frame.xml`，600×468 @(20,6)），
    底栏那条 60px 的带子**不画**。正文一句提示整屏居中（原厂那几块纯黑屏什么字都没有，
    留着是为了让用户知道"正在退出"，不是卡死）。"""
    # 四边外框：按相机原生"格式化完成。"那张照片实测 —— **左右各 32px、上 9px、下 71px**，
    # 即**竖向不居中**（框贴上方，下面留一大条；用户："这个框并不是居中屏幕的"）。
    # "下面没有提示栏" —— 底栏那条 60px 的带子不画。
    frame(img, (32, 9, 607, 408))
    # 字中心 (320, 174)：按相机原生那张实测（比屏幕中心高 66px），与布局里
    # "48 高定框 + marginTop 150" 等价
    put_c(img, "正在退出···", W / 2.0, 174, 38)


def screen_dialog(img, kind="confirm"):
    """弹窗真值（原厂 cmn_parts_layout_5w_cmn_dialog_string_with_icon_two_button）：
    底板 p_dialogarea 600×416 @(20,4)、警告图标 p_dialogwarning 60×60 @(290,20)、
    正文 544×180 @(48,80) 38px 居中、按钮 240×56 @(200,274)/(200,340)，
    并用"带图标"那款（用户点名要的）——双按钮确认恒带感叹号图标；
    单按钮通知里只有警告性质的给图标。底栏 640×60 @y420。"""
    img.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 0x66)))
    # 面板 600×424 @(20,4)：底栏改成 52px 贴底之后面板要长到 y428 才接得上（工程同值）
    _p = asset("dlg_area.png").resize((600, 424), Image.LANCZOS)
    img.paste(_p, (20, 4), _p)
    msg_cx, msg_cy = 320, 170           # 正文框 544×180 @(48,80) 的中心
    if kind == "warn":
        png(img, "dlg_warn.png", 290, 20)
        put_c(img, "配对码已连续错误 5 次\n已自动更换新的配对码", msg_cx, msg_cy + 10, 38)
        d = patch("btn_focus.9.png", BTN_W, BTN_H)
        img.paste(d, (200, 340), d)
        put_c(img, "确定", 200 + BTN_W // 2, 340 + BTN_H // 2, 42)
    elif kind == "mode_prompt":
        # 首启：**先弹"请选择连接方式"，确定之后才是选择界面**（用户要求）。
        # 单按钮通知、恒在 (200,340)；浮层底下就是那块连接设置面板（先立屏再提示）。
        put_c(img, "请选择连接方式", msg_cx, msg_cy, 38)
        d = patch("btn_focus.9.png", BTN_W, BTN_H)
        img.paste(d, (200, 340), d)
        put_c(img, "确定", 200 + BTN_W // 2, 340 + BTN_H // 2, 42)
    elif kind == "pairing_prompt":
        # 首启引导第②步：选完连接方式后**先提示需要配对**，按确定才进配对流程。
        # 单按钮通知（无警告图标 —— 这不是错误），弹在配对页之上，所以先把配对页画出来。
        # 按钮位置照原厂单按钮弹窗恒在 (200,340)。
        put_c(img, "请先与一台手机配对", msg_cx, msg_cy, 38)
        d = patch("btn_focus.9.png", BTN_W, BTN_H)
        img.paste(d, (200, 340), d)
        put_c(img, "确定", 200 + BTN_W // 2, 340 + BTN_H // 2, 42)
    else:
        png(img, "dlg_warn.png", 290, 20)
        put_c(img, "确定要退出 SonyConnect 吗？", msg_cx, msg_cy + 10, 38)
        d = patch("btn_focus.9.png", BTN_W, BTN_H)
        img.paste(d, (200, 274), d)
        put_c(img, "确定", 200 + BTN_W // 2, 274 + BTN_H // 2, 42)
        n = patch("btn_normal.9.png", BTN_W, BTN_H)
        img.paste(n, (200, 340), n)
        put_c(img, "取消", 200 + BTN_W // 2, 340 + BTN_H // 2, 42)
    dlg_footer(img)


def sheet(names, cols=4, scale=0.5):
    """总览图：所有预览半尺寸拼一张，便于一眼看全。"""
    tw, th = int(W * scale), int(H * scale)
    rows = (len(names) + cols - 1) // cols
    out = Image.new("RGB", (cols * tw, rows * th), (24, 24, 24))
    for i, n in enumerate(names):
        p = os.path.join(OUT, n + ".png")
        if not os.path.exists(p):
            continue
        out.paste(Image.open(p).convert("RGB").resize((tw, th), Image.LANCZOS),
                  ((i % cols) * tw, (i // cols) * th))
    p = os.path.join(OUT, "_总览.png")
    out.save(p)
    print("  %s" % p)


def check_screen_heights():
    """结构自检：每个 base 屏的直接子 View 高度合计必须正好 480。

    ★ 这条检查是被真机照片逼出来的。菜单屏删掉页码指示行时，我把中间那层 362px 的
      容器一起拆了，于是 `66 + 30 + 302 + 52 = 450` —— 底栏上浮、屏幕底下多出一条
      30px 黑边。**当时预览图是对的**：footer() 直接画在常量 FOOTER_Y=428 上，
      根本不知道工程里那些高度是拼出来的 —— 于是"预览通过、真机失败"。
      现在两边都不依赖手写算式：工程里底栏靠 height=0+weight=1 锚底，
      预览这边则**直接读工程的布局文件**验算式，这种错在预览环节就会红。

    规则：子 View 里凡有 layout_weight 的，说明它吃掉剩余高度（底栏由布局锚底），
    该屏跳过不验；其余情况必须加起来正好等于屏高。
    """
    import xml.etree.ElementTree as ET
    ns = "{http://schemas.android.com/apk/res/android}"
    lay = os.path.join(BASE, "..", "..", "app", "app", "src", "main", "res", "layout")
    tree = ET.parse(os.path.join(lay, "activity_main.xml"))
    # 顶栏是 <include layout="@layout/screen_header"/>，高度写在被包含的那个文件里 ——
    # 不把它的高度查出来，每个屏都会因为"子 View 高度不是 px"被整屏跳过，检查就废了。
    inc_h = {}
    for f in os.listdir(lay):
        if f.endswith(".xml") and f != "activity_main.xml":
            r = ET.parse(os.path.join(lay, f)).getroot()
            v = r.get(ns + "layout_height") or ""
            if v.endswith("px"):
                inc_h["@layout/" + f[:-4]] = int(v[:-2])
    bad = []
    for scr in tree.getroot():
        h = scr.get(ns + "layout_height")
        if h not in ("match_parent", "-1"):
            continue                      # 只验"满屏"的容器
        kids = list(scr)
        if not kids or any(k.get(ns + "layout_weight") for k in kids):
            continue                      # 有 weight → 高度由布局分配，不验算式
        total = 0
        fixed = True
        spans = []
        for k in kids:
            kh = k.get(ns + "layout_height") or ""
            if k.tag == "include":
                kh = ("%dpx" % inc_h[k.get("layout")]) if k.get("layout") in inc_h else ""
            if not kh.endswith("px"):
                fixed = False
                break
            hh = int(kh[:-2])
            total += hh
            # 外边距也占位置：弹窗面板是 4px marginTop + 416 高，底栏 60 贴底，正好 480
            for m in ("layout_marginTop", "layout_marginBottom"):
                mv = k.get(ns + m) or ""
                if mv.endswith("px"):
                    total += int(mv[:-2])
            # 每个子 View 的纵向占位（用来判"是否互相叠着"）
            mt = int((k.get(ns + "layout_marginTop") or "0px")[:-2] or 0)
            mb = int((k.get(ns + "layout_marginBottom") or "0px")[:-2] or 0)
            g = k.get(ns + "layout_gravity") or ""
            if "bottom" in g:
                top = H - hh - mb
            elif "center" in g and "center_horizontal" not in g:
                top = (H - hh) / 2.0
            else:
                top = mt
            spans.append((top, top + hh))
        # ★ 子 View **互相叠着**的屏不验算式：退出屏就是"外框 (32,9)-(607,408)" +
        #   "正文框 48 高 @150" —— 正文本来就画在外框里面，相加当然不等于 480。
        #   这条检查的用途是"底栏别浮起来"，叠放式布局跟它无关。
        overlapped = False
        for i in range(len(spans)):
            for j in range(i + 1, len(spans)):
                a0, a1 = spans[i]
                b0, b1 = spans[j]
                if a0 < b1 and b0 < a1:
                    overlapped = True
        if overlapped:
            continue
        if fixed and total != H:
            bad.append("%s: 子 View 固定高度(+外边距) 合计 %d ≠ %d" % (scr.get(ns + "id"), total, H))
    if bad:
        raise SystemExit("布局结构自检失败（底栏会浮起来）：\n  " + "\n  ".join(bad))
    print("  结构自检：各屏高度合计 = %d ✔" % H)


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    check_screen_heights()   # 先验工程的结构算式，再画图（详见函数注释）
    jobs = [
        ("01_main_hotspot", lambda im: screen_main(im, "hotspot", "Redmi 14R 5G", "on")),
        ("02_main_wifi_ok", lambda im: screen_main(im, "wifi", "Redmi 14R 5G", "on")),
        ("03_main_wifi_ng", lambda im: screen_main(im, "wifi", None, "off")),
        ("04_main_unset", lambda im: screen_main(im, "", None, "off")),
        ("05_main_hotspot_info", lambda im: screen_main(
            im, "hotspot", "Redmi 14R 5G", "on", "hotspot")),
        ("05b_main_status", lambda im: screen_main(
            im, "hotspot", "Redmi 14R 5G", "on", "status",
            # 与 buildMainStatusText() 的运行态**逐行一致**（热点模式共 5 行）——
            # 样例比真实情况多一行，预览就会显得"文字钻到按钮底下"，白吓一跳
            status="状态：运行中\n手机端：Redmi 14R 5G\n连接方式：相机热点\n"
                   "热点名称：DIRECT-xy:ILCE-6300\n热点密码：1234567890")),
        ("06_menu", screen_menu),
        ("07_paired", screen_paired),
        ("07b_paired_empty", lambda im: screen_paired(im, ())),
        ("08_mode", screen_mode),
        ("08b_mode_first_level", lambda im: screen_mode(im, can_back=False)),
        ("09_pairing", screen_pairing),
        ("09a_pairing_manual", lambda im: screen_pairing(im, can_back=True)),
        ("09f_mode_prompt", lambda im: (screen_mode(im),
                                        screen_dialog(im, "mode_prompt"))[0]),
        ("09b_pairing_renewed", screen_pairing_renewed),
        ("09c_pairing_prompt", lambda im: (screen_pairing(im),
                                           screen_dialog(im, "pairing_prompt"))[0]),
        ("09d_pairing_hotspot_info", lambda im: screen_pairing(im, block="hotspot")),
        ("09e_pairing_status", lambda im: screen_pairing(
            im, block="status",
            status="状态：启动中…（配对模式）\n连接方式：相机热点\n"
                   "热点名称：DIRECT-xy:ILCE-6300\n热点密码：1234567890")),
        ("10_about", screen_about),
        ("10b_log", screen_log),
        ("11_dlg_confirm", lambda im: screen_dialog(im, "confirm")),
        ("12_dlg_warn", lambda im: screen_dialog(im, "warn")),
        ("13_exiting", screen_exiting),
    ]
    for name, fn in jobs:
        im = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        fn(im)
        path = os.path.join(OUT, name + ".png")
        im.convert("RGB").save(path)
        print("  %s" % path)
    sheet([n for n, _ in jobs])


if __name__ == "__main__":
    main()
