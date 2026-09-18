# -*- coding: utf-8 -*-
"""生成 PortCheck 的 48x48 图标（不依赖 PIL：手写 PNG）。"""
import zlib, struct, os

W = H = 48
OUT = r'C:\Users\93849\Desktop\SonyConnect\portcheck\res\drawable\ic_port.png'

BG = (0x22, 0x22, 0x22)
FG = (0xDD, 0x66, 0x00)   # 索尼焦点橙 #ffdd6600
BORDER = (0x55, 0x55, 0x55)

# 相机把 4:3 帧缓冲横向拉伸 4/3 到 16:9 → 内容按 75% 宽预压，实机才显示为正方形。
# 内容盒：宽 36 × 高 48，居中于 48×48 画布。
CX0, CX1 = 6, 42
CY0, CY1 = 2, 46

rows = []
for y in range(H):
    row = bytearray()
    for x in range(W):
        if x < 2 or y < 2 or x >= W - 2 or y >= H - 2:
            r, g, b = BORDER
        elif CX0 <= x < CX1 and CY0 <= y < CY1:
            # 内容盒内画“三排端口”图案
            rw = CX1 - CX0
            rh = CY1 - CY0
            u = (x - CX0) * 100 // rw          # 0..99
            v = (y - CY0) * 100 // rh
            band = v // 34                      # 三排
            hole = (u // 34) % 3                # 每排三个孔位
            on = (u % 34) < 20                  # 孔的实心段
            r, g, b = FG if (band <= 2 and hole <= 2 and on) else BG
        else:
            r, g, b = BG
        row += bytes((r, g, b, 255))
    rows.append(bytes(row))

raw = b''.join(b'\x00' + r for r in rows)


def chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))


png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw, 9))
       + chunk(b'IEND', b''))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, 'wb').write(png)
print('wrote', OUT, len(png), 'bytes')
