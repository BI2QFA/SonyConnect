# SonyConnect 界面改造教程 —— 换装索尼原厂 UI（1:1 版）

> 对象工程：`C:\Users\93849\Desktop\SonyConnect\app`（`com.bi2qfa.sonyconnect`）
> 依据资源包：`C:\Users\93849\Desktop\SonyUI复刻包\`（下文简称"资源包"）
> 前置阅读：资源包里的 `交接文档_HANDOFF.md`（尤其第 3 节真值速查、第 9 节踩坑清单）
>
> **改造总原则：三不动。**
> ① 业务逻辑不动（FtpServer / PtpIpServer / PairingStore / RadioWrapper 全部不碰）
> ② 单 Activity + FrameLayout 叠屏 + `showScreen(SCR_*)` 的导航骨架不动
> ③ `ScalarInput.ISV_KEY_*` 的按键分发不动
> 只动**皮肤层**：素材、styles、各屏几何、底部引导。这样改完编译必过、行为不变。

---

## 0. 现状盘点（先认清你已经有什么）

我读过你的工程，底子其实很好，改造量比想象小：

| 已具备 | 说明 |
|---|---|
| 640×480 px 设计稿 | `activity_main.xml` 全部 px，注释里已引用 sonyui-kit 几何 |
| 索尼窄体字样式 | `styles.xml` 的 `FsSS/FsS/FsMS/FsML…` 已经是 0.7001 家族 |
| 单 Activity 叠屏 | `SCR_MAIN/MENU/MODE/ABOUT/PAIRING` + `showScreen()` |
| 三个弹窗 | `dlg_exit` / `dlg_default` / `dlg_exiting` 已有骨架 |
| 索尼按键 | `onKeyDown` 里用 `event.getScanCode()` 分发 `ISV_KEY_MENU/ENTER/UP/DOWN` |
| 电量控件 | 自绘 `BatteryView`（保留，索尼原厂也有电量显示） |

**差距**（也是本教程的六步）：① 真值素材没进包 ② 字号家族不全（缺 36/38/42px 的
`PFontSize` 行距参数）③ 各屏几何是"参照"而非"逐项校准" ④ 底部引导用的还是文字
`footer_menu_key`，不是图标字体 ⑤ 弹窗按钮不是真值的 240×56 @(200,340) ⑥ 选中态
没有用原厂 9-patch 选中条。

---

## 1. 第一步：把真值素材拷进工程

从资源包复制到 `app/src/main/res/drawable-nodpi/`（该目录不存在就新建）：

```
02_素材/framework-res/drawable/drawable-nodpi/p_footer.png                 底栏 640×60
02_素材/framework-res/drawable/drawable-nodpi/p_header.png                 子屏顶栏 640×60
02_素材/framework-res/drawable/drawable-nodpi/p_dialogarea.png             弹窗底板 600×416
02_素材/framework-res/drawable/drawable-nodpi/p_dialogwarning.png          警告图标 60×60
02_素材/framework-res/drawable/drawable-nodpi/p_dialogbuttonarea.png       居中弹窗 280×186
02_素材/framework-res/drawable/drawable-nodpi/p_16_dd_parts_43_cmn_focus_focused.9.png
02_素材/字体_全量/MYingHeiC-GB18030-SJ.ttf                                  中文（1.9MB）
02_素材/字体_全量/Sony_DI_Icons.ttf                                          图标字体（0.5MB）
```

外加两个**已经重建合规**的 9-patch（索尼原图 marker 是 #DD7700，AAPT2 拒收，
不能直接拷原图！资源包 `04_示例工程/SonyUIDemoLite/.../drawable-nodpi/` 里是重建好的）：

```
row_focus.9.png        49px 行选中条（页码菜单）
btn_focus.9.png        240×56 按钮 focus 态
btn_normal.9.png       240×56 按钮 normal 态
page_1.png page_1_sel.png … page_5.png        页码指示 36×44
```

⚠️ 三个坑：① 9-patch 一律用重建版，别拷固件原图；② 拷完确认四角像素透明；
③ `p_dialogarea` 内部就是纯黑 + #222 结构线，黑底上"看着什么都没有"是正常的。

字体放 `app/src/main/assets/fonts/`（目录不存在就新建）。体积敏感就把
`06_生成脚本/mk_lite_fonts.py` 里的路径改成你的工程跑一遍——它从 Java 源码自动
收集用到的字符，1.9MB → 47KB。

---

## 2. 第二步：styles.xml 补齐真值字号家族

你已有的 `FsMS(32px)/FsML(36px)` 是对的，**补上带行距的三档**（弹窗正文/按钮必须用），
直接粘进 `<resources>`：

```xml
<!-- PFontSize 真值：textScaleX 全 0.7001，行距来自 framework style bag -->
<style name="PfH36" parent="@android:style/Widget.TextView">
    <item name="android:textSize">36px</item>
    <item name="android:textScaleX">0.7001</item>
    <item name="android:lineSpacingExtra">20px</item>
    <item name="android:textColor">@color/font_normal</item>
</style>
<style name="PfH38" parent="@android:style/Widget.TextView">
    <item name="android:textSize">38px</item>
    <item name="android:textScaleX">0.7001</item>
    <item name="android:lineSpacingExtra">8px</item>
    <item name="android:textColor">@color/font_normal</item>
</style>
<style name="PfH42" parent="@android:style/Widget.TextView">
    <item name="android:textSize">42px</item>
    <item name="android:textScaleX">0.7001</item>
    <item name="android:lineSpacingExtra">10px</item>
    <item name="android:textColor">@color/font_normal</item>
</style>
```

出处：`01_文档_SONY_UI_REF/03_styles.md`（framework style bag 原值）。
注意 `FsMS` 的 0.7001 与 `FsS` 的 0.7199 是 framework 里就有的差异，不要"统一"掉。

---

## 3. 第三步：底部引导换成图标字体（全 App 面貌改变最大的一步）

真机底部引导**不是图片**，是图标字体字符串：`"\uE80A\uE803"`
（U+E80A = MENU 键帽，U+E803 = 返回箭头），36px 右对齐。

改 `activity_main.xml` 的 `main_footer`：删掉 `footer_menu_key`（那个 96×48 的文字图），
换成：

```xml
<TextView android:id="@+id/footer_guide"
    android:layout_width="624px" android:layout_height="40px"
    android:layout_marginLeft="8px" android:layout_marginTop="432px"
    android:gravity="center_vertical|right"
    android:includeFontPadding="false" android:singleLine="true"
    android:textColor="#ffdddddd" android:textScaleX="0.7001"
    android:textSize="36px" />
```

`MainActivity.onCreate` 里一次性设好（其它屏复用同一实例）：

```java
((TextView) findViewById(R.id.footer_guide)).setTypeface(
        Typeface.createFromAsset(getAssets(), "fonts/Sony_DI_Icons.ttf"));
((TextView) findViewById(R.id.footer_guide)).setText("\uE80A\uE803");
```

各屏引导语不同时改 `setText()` 内容即可（语义对照表：
`01_文档_SONY_UI_REF/05_icons_guide.txt`，如 `\uE51A`=MENU、`\uE51B`=Guidé 键等）。

---

## 4. 第四步：逐屏校准几何（对照 `08_layouts_kv.txt`）

### 4.1 `screen_menu`（菜单屏 → 页码菜单 PageMenu）

这是改动最大的一屏。真值（timelapse `menu_page.xml` + `menu_page_adapter.xml`）：

```
页码指示   36×44 图标 @y76 水平居中（选中 = page_N_sel.png，有橙色下划线）
列表       606 宽 @x17 y126；行 606×49；分隔线 2px #222222
标签       42px/0.7001，marginLeft 10，垂直居中，#DDDDDD
值         38px/0.7001，marginRight 10，右对齐（有值才显示）
选中行     背景 = row_focus.9（android:background，非 src）
底栏       p_footer 640×60 @y420
```

建议照抄资源包 `04_示例工程/SonyUIDemoLite` 的 `home()` 方法（就是这套），
把 `HOME[][]` 数据源换成你 `screen_menu` 现在的菜单项，选中态存你的 `menuSel`。
列表行可以直接复用 Lite 版的 `res/layout/row_menu.xml`（606×49，双 TextView）。

### 4.2 `screen_mode`（模式屏 → SetMenu 面板）

```
整屏遮罩   #88000000
面板       518×480 图片 @x62 y0（素材 panel_menu3rd.png，见资源包 02_素材）
标题       42px @(75,29)（面板左 62 + 13）
列表       489 宽 @(82,95)，行 473×49，分隔线 2px #222222
```

### 4.3 `screen_main`（主屏）

保持你的信息结构（状态/二维码/相机列表），只统一三处：
- 头部换成 66px 高 + 双分隔线（y63 #333333 / y64 #222222），标题 42px @(78,10)，
  图标 70×55 @(6,5)（真值是 `p_16_dd_parts_XX_appicon.png` 70×55，你现在是 40×40 的
  ic_launcher——建议从 timelapse 那张改）
- 相机列表行改 ListMenu 规格：**行 601×82、分隔线 0**、标签 36px @(21,15)、
  值 32px 右对齐 paddingRight 20 + marginRight 6、选中行背景 `row_focus.9` 拉伸到 82px
- 底部提示条改 p_footer 640×60 @y420 + 图标引导

### 4.4 三个弹窗（`dlg_exit` / `dlg_default` / `dlg_exiting`）

这是上一版 Lite 踩坑最多的地方，**逐项按真值来，不要自己估**：

```
底板        p_dialogarea 600×416 @(20,4)，先铺一层 #66000000 遮罩
正文        544×180 @(48,80)，38px/0.7001 居中（style PfH38），弹窗没有标题行
按钮        240×56，文字 42px/0.7001（style PfH42）
            单按钮 @(200,340) 且恒用 btn_focus.9（原厂单按钮恒为 focus 橙）
            双按钮 @(200,274) + @(200,340)，focused 用 btn_focus、另一个 btn_normal
警告图标    p_dialogwarning 60×60 @(290,20)   ← 0x12200>>8=290, 0x1400>>8=20
进度条      轨道 431×24 @(104,294) #DDDDDD；进度 427×20 @(106,295) #DD6600
```

对照你现有的 `btn_exit_ok/btn_exit_cancel/btn_default_ok`：把它们的
layout_width/height 改成 240px/56px、marginLeft 200px、marginTop 按上表，
textSize 换 `@style/PfH42`。背景 selector 换成 btn_focus/btn_normal 二态：

```xml
<!-- res/drawable/btn_selector.xml -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true"  android:drawable="@drawable/btn_focus"/>
    <item android:state_pressed="true"   android:drawable="@drawable/btn_focus"/>
    <item android:drawable="@drawable/btn_normal"/>
</selector>
```

（你代码里用 `setSelected()` 标当前按钮的话，这个 selector 直接生效。）

### 4.5 `screen_pairing` / `screen_about`

只统一头部（同 4.3 的 66px 头部）和底部引导，正文区保持你的现有布局。
About 的上下滚动你已经做了（`ISV_KEY_UP/DOWN` → `smoothScrollBy`），保留。

---

## 5. 第五步：代码层三条铁律（Lite 版两次返工的教训）

1. **代码里设字号必须带单位**：`setTextSize(TypedValue.COMPLEX_UNIT_PX, 42)`。
   `setTextSize(42f)` 是 sp，相机密度 ≠ 1，会放大（SonyMenuEngine 第一版就栽在这）
2. **横向压缩用 `TextView.setTextScaleX(0.7001f)`**，绝不用 `View.setScaleX()`——
   后者绕 View 中心缩放整个视图，右对齐/左对齐的文字全都会错位
3. **布局一律 px**；新加 View 时 `LinearLayout.LayoutParams`/`MarginLayoutParams`
   的 margin 本来就是 px，不用换算

建议把资源包 `SonyUIDemoLite/.../MainActivity.java` 里的四个小工具
`tv()/lp()/bar()/img()` 抄成你的 `SonyUi.java`（~40 行），新屏全都用它写，天然合规。

---

## 6. 第六步：编译 + 像素验收

```powershell
$env:JAVA_HOME="C:\Users\93849\AppData\Local\a6300-tools\jdk1.8.0_502"
Set-Location "C:\Users\93849\Desktop\SonyConnect\app"
& "C:\Users\93849\.gradle\wrapper\dists\gradle-4.4.1-bin\26t5m7ky3elzmp75s2y953glp\gradle-4.4.1\bin\gradle.bat" assembleDebug --console=plain *> build.log
```

- 你工程 `packagingOptions` 里的 `exclude 'com/sony/**'` 和 `stubs.jar` 都别动
  （`ScalarInput` 就是 stubs 里的，运行时用框架真类）
- 验收流程（HANDOFF 第 8 节）：实机截图 → 按映射反拉伸成 640×480 → 与合成图算 MAE，
  验收线 MAE < 12/255（录屏压缩本底）
- 每改一屏拍一张，逐元素核对：先位置（差整 10/20px 的一般是 margin 没校准），
  再字号（差 1.66 倍 = sp/px 混用），再边缘线（被拉伸 = 9-patch marker 错）

---

## 7. 排查速查表（哪不对 → 查哪）

| 症状 | 原因 | 解法 |
|---|---|---|
| 文字比真机大 1.66 倍 | sp 当 px | `COMPLEX_UNIT_PX` |
| 文字左右漂移 | 用了 `View.setScaleX` | 换 `TextView.setTextScaleX` |
| 选中条边缘发毛/错位 | 9-patch marker 全圈黑 | 用资源包重建版（mkassets 技术） |
| AAPT2 报 9-patch 错误 | 拷了固件原图（#DD7700 marker）/ 四角不透明 | 只用重建版；四角必须透明 |
| 图标引导显示成方框 | assets 里没有字体 / Typeface 没设 | 查 `assets/fonts/` 与 setTypeface |
| 弹窗按钮位置怪 | 自己估的坐标 | 照本教程 4.4 真值表 |
| aapt 报 resource not found | 布局路径写成了 `--file` | 必须作位置参数传 |

---

## 8. 一页纸总结

```
拷素材（18 张 + 2 字体） → styles 补 PfH36/38/42 → 底部引导换图标字体
→ menu 屏改 49px 页码菜单 → mode 屏改 SetMenu 面板 → main 屏行改 82px ListMenu
→ 三个弹窗按真值重排（240×56 @(200,340)，警告图标 @(290,20)）
→ 代码三条铁律 → 编译 → 截图 MAE 验收
不动：FtpServer/PtpIpServer/PairingStore/RadioWrapper、showScreen 骨架、ISV 按键分发
```

教程中所有数字的出处都在资源包 `01_文档_SONY_UI_REF/`（布局=`08_layouts_kv.txt`、
字号=`03_styles.md`、颜色=`02_colors.md`、选择器=`10_selectors.md`、
弹窗布局 dump 原文=`交接文档_HANDOFF.md` 第 3.3 节）。
