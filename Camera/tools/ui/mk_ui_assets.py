# -*- coding: utf-8 -*-
"""
生成 SonyConnect 换装所需的"自绘件"。

**自绘件只剩一个**：顶栏左上角的应用图标（辉光已改成原版直拷，见下）。
其余图标已全部换成原厂素材（`tools/ui/sony_sync_src/` → `res/drawable-nodpi/ic_sync_*.png`，
从「同步到智能手机」APK 原样拷出，**不做任何预挤压** —— 它跑在同一个 640×480 帧缓冲上，
素材本身就是按这块屏画的，再压一次反而错）。

铁律（只对自绘件成立）：**宽度按 0.75 预挤压**。
  相机 LCD 是 16:9（1280×720），帧缓冲是 4:3（640×480），被 x×2 / y×1.5 非等比拉伸。
  所以自绘的正方形图形必须先把宽度压到 75%，显示时才是正圆/正方。

顶栏应用图标的尺寸取原厂真值：`AppIconView` 70×55 @顶栏(6,5)，
可见内容 45×55（原厂 `p_16_dd_parts_sync_appicon` 就是 70×55、内容 45×55 @x13）。
45×55 显示出来是 90×82.5，接近正方形 —— 这个内容框就是"看起来不扁"的那个比例，
所以这里照抄：**画 45×55 的图形，放进 70×55 的透明画布居中**（内容 x 偏移 12）。
多出来的左右 12px 是留给 ImageView 与标题的间距的，不是留白乱画。
"""

import os
from PIL import Image, ImageDraw

S = 4                                   # 超采样倍数
C = (0xDD, 0xDD, 0xDD, 0xFF)            # 原厂常态字色 #DDDDDD（与标题同色，观感才是一套）
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "..", "app", "app", "src", "main", "res", "drawable-nodpi")
BASE_RES = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "..", "app", "app", "src", "main", "res")

ICON_W, ICON_H = 70, 55                 # 画布 = 原厂 AppIconView 真值（**保持不动**：
                                        #   标题起点 6+70+2 = 78 靠它钉住，缩小图形不该挪标题）
ART_W, ART_H = 37, 44                   # 图形内容框（见下：42 = 标题字号，用户定版）
# ★ 内容框为什么是 42 高：用户要求"把左上角软件图标变小一点，**和右边的字体等大**"。
#   右边的标题是 PfT42（42px），所以图形墨迹高就取 42 —— 两者在顶栏里同心，
#   视觉上就是"图标和字一样高"。宽度按原来的 45:55 比例等比缩下来（27×33），
#   **不顺手改胖瘦**：那个比例是上一轮用户认可的（原厂图标显示出来偏横宽）。


def app_icon():
    """顶栏应用图标 = **App 自己的 launcher 图标**（用户要求："改成现在的软件图标"）。

    不再自绘：直接取 `res/mipmap-xxxhdpi/ic_launcher.png`（192×192，最高清的那份），
    裁到内容框（bbox 137×152），按比例缩到顶栏的画布。

    尺寸：画布仍是原厂的 70×55（标题起点靠它保持 78），**图形 37×44**（墨迹 42×49 —— 比标题的 42px 字号略大，见下） ——
    42 就是右边标题的字号（PfT42），用户要求"和右边的字体等大"。
    这里走过两次弯路，记下来：先按"不变形"算宽度（37、后 43），用户两次都说"被挤压"。
    原因不是变形 —— 资源包 `交接文档_HANDOFF.md` 明确写着 LCD 是 x×2 / y×1.5 非等比
    拉伸，所以按比例算出来的 43×64 在**机器上是正方的**；但原厂图标显示出来是
    **90×82.5（横的）**，我做的 86×96（竖的）一比就显得窄。
    于是按原厂的槽位来：图形直接铺满 45×55（横向会比原生比例胖 ~21%，与原厂观感一致）。

    两个必须踩住的点：
      ① **比例要按"显示后"算**，不是按文件像素。帧缓冲被 x×2 / y×1.5 非等比拉伸，
         所以要让 137:152 的图形显示出来不变形，文件盒必须是
         `w/h = (137/152) × (1.5/2) = 0.676`。
         但这一版**故意不取 0.676**：45:55 = 0.818 是上一轮用户看过并认可的胖瘦
         （显示出来 1.09，与原厂那个横宽的图标观感一致），本轮只等比缩小、不改比例。
      ② 颜色统一成 #DDDDDD（与标题同色）。launcher 原图是近白 (#FAFAFA)，直接放上去
         比标题亮一档，观感是"两块不一样的白"。
    """
    src = Image.open(os.path.join(BASE_RES, "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA")
    src = src.crop(src.getbbox())
    box_w, box_h = ART_W, ART_H
    # 分两步缩（先到 3 倍目标再落到目标）：137×152 → 45×55 一步落太狠，细笔画
    # （Wi-Fi 那三道弧、右下那个链环）会被 LANCZOS 糊断；两步缩出来明显更挺。
    art = src.resize((box_w * 3, box_h * 3), Image.LANCZOS).resize((box_w, box_h), Image.LANCZOS)

    # 保留 alpha（抗锯齿边缘），把 RGB 统一成标题色
    flat = Image.new("RGBA", art.size, C)
    flat.putalpha(art.getchannel("A"))

    canvas = Image.new("RGBA", (ICON_W * S, ICON_H * S), (0, 0, 0, 0))
    canvas.paste(flat.resize((box_w * S, box_h * S), Image.LANCZOS),
                 ((ICON_W - box_w) * S // 2, (ICON_H - box_h) * S // 2))
    return canvas.resize((ICON_W, ICON_H), Image.LANCZOS)


def link_on():
    """把原厂 `connect_off`（6 个点，alpha 上限只有 85/255 = 暗的）做成**亮点**版。

    用户要求"和手机成功建立连接时主界面那六个点也要亮起"。原厂的 `connect_on` 是
    "4 个点 + 一个箭头"，那是它"传送中"页的语义，不是"六个点亮起来" —— 所以这里不换图，
    只把同一个素材的 alpha 拉满（几何完全不动，点的位置/大小与原厂一模一样）。
    """
    src = Image.open(os.path.join(OUT, "ic_sync_link_off.png")).convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    out.putalpha(src.getchannel("A").point(lambda a: min(255, int(a * 7.5))))
    r, g, b, _ = src.split()
    out = Image.merge("RGBA", (r, g, b, out.getchannel("A")))
    return out


# ===== 主屏下方那束辉光：**原版直拷**（用户定版，2026-09-13）=====
#
# 原厂「同步到智能手机」的 `p_16_dd_parts_sync_background.png`（640×298），直接拷成
# `res/drawable-nodpi/ic_sync_glow.png`，**一个字节都不动**（`tools/ui/sony_sync_src/`
# 里就有那份原件）。
#
# 走过的弯路（记下来别再走）：我先后自绘过两版 —— 一版"按原图逐行有效 alpha 重画成平滑
# 8 位渐变"、一版"平滑 + 4×4 Bayer 有序抖动"，真机上都不如原版：前者被显示后端量化成
# 几道块状横条，后者带上了自己加的网点。用户拍板："直接把辉光效果换成原版素材吧。"
# 原版那 5 级 alpha + 网点抖动是索尼照着**这块屏的实际量化**调出来的，比自己算的稳。
#
# 槽位不变：640×298 @屏(0,130)，即贴着正文区底部往上铺满（正文 66..428），
# 只在主屏显示。宽度不做 0.75 预挤压（满宽背景，原厂也没压）。


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    for name, im in (("ic_appicon.png", app_icon()),
                     ("ic_sync_link_on.png", link_on())):
        path = os.path.join(OUT, name)
        im.save(path)
        print("  %-22s %3dx%-3d  %5d B" % (name, im.size[0], im.size[1],
                                           os.path.getsize(path)))


if __name__ == "__main__":
    main()
