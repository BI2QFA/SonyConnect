# -*- coding: utf-8 -*-
"""比对两支候选字体的 Latin 与中文观感，决定要不要把 Latin 与 CJK 合并成一支。"""
import os
from PIL import Image, ImageDraw, ImageFont

F = r'C:\Users\93849\Desktop\SonyUI复刻包\02_素材\字体_全量'
OUT = r'C:\Users\93849\Desktop\SonyConnect\tools\ui\diag'
S = u'SonyConnect  87%  Wi-Fi 设置  退出应用程序吗？'
FONTS = ['UniversOTS-SJ.ttf', 'MYingHeiC-GB18030-SJ.ttf', 'HeiseiKakuGothW5-SJ.ttf']

out = Image.new('RGB', (1900, 3 * 90 + 20), (0, 0, 0))
d = ImageDraw.Draw(out)
for i, n in enumerate(FONTS):
    f = ImageFont.truetype(os.path.join(F, n), 42)
    t = Image.new('RGBA', (1800, 70), (0, 0, 0, 255))
    ImageDraw.Draw(t).text((5, 8), S, font=f, fill=(221, 221, 221, 255))
    t = t.resize((int(t.size[0] * 0.7001), t.size[1]), Image.LANCZOS)
    out.paste(t.convert('RGB'), (0, i * 90))
    d.text((5, i * 90 + 70), n, fill=(255, 90, 90))
out.resize((int(1900 * 1.4), int((3 * 90 + 20) * 1.4)), Image.LANCZOS).save(os.path.join(OUT, 'latin_cmp.png'))
print('ok')
