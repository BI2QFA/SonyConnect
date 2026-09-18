# -*- coding: utf-8 -*-
"""
解 APK 里 .9.png 的 npTc/npOl 块，把 AAPT2 实际写进去的
拉伸段(xDivs/yDivs)与**内边距(padding)**打印出来。

为什么需要它：9-patch 的 padding 是**隐式**的 —— 右下两条边框上没有黑段时，
AAPT2 会把"固定边框"当成内容区，于是给宿主 View 加一大圈内边距（我这边
SetMenu 面板就被加了 (6, 85, 5, 30)，界面上表现为整块内容被推下去 85px）。
这种错在 XML 里完全看不出来，只能从编译产物里量。

npTc 布局（**实测反推**的，不是照着文档写的）：拿两个自己知道标记的文件对出来 ——
  · 我的 set_panel：写入的拉伸段是内容 x 6..510、y 85..130，padding 应为 (L6,R6,T85,B30)
  · 原厂 set_panel：从十六进制里读出 xDivs=(257,258)、yDivs=(85,86)、padding=(5,5,81,70)
  两者对得上，字段顺序是：
      [头部 1 字：was_deser, nx, ny, nc] [2 个未知字] [padding L,R,T,B]
      [1 个未知字] [xDivs nx 个] [yDivs ny 个] [colors nc 个]
  前 3 个"未知字"看着像区域颜色（0x20000000 这种 alpha 很低的黑），与布局无关，跳过。
  注意：**padding 的顺序是 L,R,T,B**（不是文档里的 L,T,R,B）——
  原厂面板算出来的 padding 就是它的固定边框，这也解释了为什么把 9-patch 当容器背景会挤偏内容。
"""
import struct
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else \
    r'C:\Users\93849\Desktop\SonyConnect\app\app\build\outputs\apk\debug\app-debug.apk'


def chunks(data):
    pos = 8
    while pos + 8 <= len(data):
        ln, typ = struct.unpack(">I4s", data[pos:pos + 8])
        yield typ.decode("latin1"), data[pos + 8:pos + 8 + ln]
        pos += 12 + ln


def scan(z, name):
    data = z.read(name)
    for typ, payload in chunks(data):
        if typ == "npTc":
            w = struct.unpack(">%dI" % (len(payload) // 4), payload[:len(payload) // 4 * 4])
            nx, ny, nc = payload[1], payload[2], payload[3]
            pad = w[3:7]                      # L, R, T, B
            p = 8
            xs = w[p:p + nx]; p += nx
            ys = w[p:p + ny]
            print("  %s" % name.split("/")[-1])
            print("      xDivs=%s yDivs=%s" % (tuple(xs), tuple(ys)))
            print("      padding L=%d R=%d T=%d B=%d" % tuple(pad))
            return
    print("  %-28s 没有 npTc" % name.split("/")[-1])


if __name__ == "__main__":
    z = zipfile.ZipFile(APK)
    names = [n for n in z.namelist() if n.endswith(".9.png")]
    print("APK 里的 9-patch（AAPT2 最终写入的几何）：")
    for n in sorted(names):
        scan(z, n)
