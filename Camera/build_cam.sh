#!/usr/bin/env bash
# SonyConnect 相机端 APK 构建（**命令行备用链**：aapt → javac → dx → zipalign → apksigner）。
#
# ★★ 正式发布请走 Gradle 链（与 2.0 同源、有实机背书）：
#      cd <本目录>
#      JAVA_HOME="C:/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502" ./gradlew assembleDebug
#    （Gradle 4.4.1 + AGP 3.0.1 + aapt2 + dx + cruncherEnabled=false，产物与 2.0 同规格）
#    本脚本是"不依赖 Gradle"的等价备份，参数与 2.0 时代对齐，改这里必须同步改 Gradle 那边。
#
# ★★★ 兼容口径（**索尼官方样本实测**，2026-09-21 定版，不得越线）：
#   索尼原厂在相机上发布的 SmartRemote 与全部 19 个 PlayMemories 相机应用 =
#   **minSdkVersion 10 + targetSdkVersion 10（或缺省）+ DEX 035**。
#   本脚本末尾有自检：任何一项不符直接失败退出。
#   ★ 历史教训：2.5 一度用 `d8 --min-api 16` + JDK21 + aapt 默认 crunch 构建，
#     APK 在 SDK10 老机型上"装上后一启动就闪退"（2.0 同源链的包正常）。
#     不要再用 d8 的 min-api>10、不要用 JDK 21 编相机端、不要开 PNG crunch。
#
# 用法：bash build_cam.sh   （默认构建本目录下的 app 模块；可用 PROJ 环境变量覆盖成别的模块目录）
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="${PROJ:-$HERE/app}"               # 模块目录（其下是 src/main、libs/stubs.jar）
SDK="/c/Users/93849/AppData/Local/Android/Sdk"
BT26="$SDK/build-tools/26.0.2"          # aapt/dx/zipalign/apksigner（2.0 时代那一档）
AJAR="$SDK/platforms/android-15/android.jar"
# JDK8：与 2.0 的 Gradle 链同一支；★ 不要用系统默认的 JDK21（dx 在 JDK21 下静默失败）
JAVA_HOME_WIN="C:/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502"
JDK_HOME="/c/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502"
JDK="$JDK_HOME/bin"
export JAVA_HOME="$JAVA_HOME_WIN"
# ★ PATH 前缀必须写 MSYS 风格（/c/...）：写 "C:/..." 时 shell 的 java 查找不会命中它，
#   结果还是调用系统 PATH 上的 JDK21（踩过——那时 dx 会静默失败）。
export PATH="$JDK_HOME/bin:$PATH"
OUT="$PROJ/build_out"
APK="$PROJ/SonyConnect-Camera.apk"

cd "$PROJ"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "[1/6] aapt package (res + assets, --no-crunch) ..."
# ★ --no-crunch：对齐 Gradle 的 cruncherEnabled=false。开着 crunch 会把所有 PNG
#   重编码（2.5 那次构建的包就是这样），是"与 2.0 不等价"的可疑来源之一。
"$BT26/aapt.exe" package -f \
  -M src/main/AndroidManifest.xml \
  -S src/main/res \
  -A src/main/assets \
  -I "$AJAR" \
  -J "$OUT/gen" -F "$OUT/res.zip" --auto-add-overlay --no-crunch

echo "[2/6] javac (JDK8, Java 1.6) ..."
find src/main/java -name '*.java' > "$OUT/sources.txt"
echo "build_out/gen/R.java" >> "$OUT/sources.txt"   # aapt 生成的 R（在 gen 根）
"$JDK/javac.exe" -encoding UTF-8 -source 1.6 -target 1.6 -nowarn -Xlint:-options \
  -bootclasspath "$AJAR" \
  -classpath "$AJAR;libs/stubs.jar" \
  -d "$OUT/obj" @"$OUT/sources.txt"

echo "[3/6] dx (min-sdk 10) ..."
"$JDK/jar.exe" cf "$OUT/classes-in.jar" -C "$OUT/obj" .
# ★ 用 dx 而不是 d8：2.0 时代的 dexer 就是 dx，--min-sdk-version 10 与 manifest 一致。
#   若必须用 d8，参数只能是 `--min-api 10`，绝不能用 min-api 16。
# ★★ 不走 `dx.bat`：它靠 `tools/lib/find_java.bat` 定位 java，而现代 SDK 安装里
#    根本没有那个文件 → `java_exe` 未定义 → **静默 exit 0 什么都不产出**（踩过）。
#    这里按 dx.bat 的内部逻辑直接调 dx.jar。输入/输出用**相对路径**（cwd 已在模块目录），
#    免得 MSYS 与 Windows 的路径风格打架。
"$JDK/java.exe" -Xmx1024M -Xss1m -Djava.ext.dirs="$BT26/lib" -jar "$BT26/lib/dx.jar" \
  --dex --min-sdk-version=10 --output=build_out/dex build_out/classes-in.jar
test -f "$OUT/dex/classes.dex" || { echo "✗ dx 没有产出 classes.dex"; exit 1; }
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"

echo "[4/6] assemble ..."
cp "$OUT/res.zip" "$OUT/unsigned.apk"
( cd "$OUT" && "$BT26/aapt.exe" add unsigned.apk classes.dex >/dev/null )
# 原生库（libsonyinfo.so）按 APK 的 lib/ 布局塞进去
mkdir -p "$OUT/lib/armeabi-v7a"
cp src/main/jniLibs/armeabi-v7a/*.so "$OUT/lib/armeabi-v7a/"
( cd "$OUT" && "$BT26/aapt.exe" add unsigned.apk lib/armeabi-v7a/libsonyinfo.so >/dev/null )

echo "[5/6] zipalign ..."
"$BT26/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign ..."
# ★ 与 ~/.android/debug.keystore 同钥：相机端覆盖安装要求签名一致
#   （换钥 = 先卸载 = 抹掉相机上的配对数据，绝对不行）
# ★★ 必须 v1-only（--v2-signing-enabled false）：2026-09-25 实测定论 ——
#   相机 flash 的 app 分区转储里 21 个已安装应用（索尼官方 19 个 PlayMemories、
#   Tweak、最早的 bi2qfa.sony.ftp）全部 v1-only、无一含 APK Signing Block；
#   带 v2 块的包（apksigner 的默认产物）装到相机上会在 Installing 阶段被拒，
#   PMCA 报 "Communication error 100: Error completed"。apksigner 默认会给
#   minSdk10 的包加 v2 块，必须显式关掉。
KS="/c/Users/93849/.android/debug.keystore"
"$JDK/java.exe" -jar "$BT26/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 10 --v2-signing-enabled false --out "$APK" "$OUT/aligned.apk"

# ===== 官方兼容口径自检（任一不符即失败）=====
echo "===== self-check: sony-official compatibility contract ====="
# ★ 不得含 APK Signing Block（v2/v3 签名块）—— 相机拒装的直接原因
if python -c "
import sys
d=open(sys.argv[1],'rb').read()
sys.exit(1 if b'APK Sig Block 42' in d else 0)
" "$APK" 2>/dev/null; then
  echo "OK: 无 APK Signing Block（v1-only 签名）"
else
  echo "✗ APK 含 v2/v3 签名块 —— 相机会拒装，必须 --v2-signing-enabled false"; exit 1
fi
BADGING=$("$BT26/aapt.exe" dump badging "$APK")
echo "$BADGING" | grep -q "sdkVersion:'10'"        || { echo "✗ minSdkVersion 不是 10"; exit 1; }
echo "$BADGING" | grep -q "targetSdkVersion:'10'"  || { echo "✗ targetSdkVersion 不是 10"; exit 1; }
DEXV=$("$BT26/dexdump.exe" -f "$APK" 2>/dev/null | grep -o "DEX version '[0-9]*'" | head -1)
[ "$DEXV" = "DEX version '035'" ] || { echo "✗ DEX 版本不是 035（得到：$DEXV）"; exit 1; }
if unzip -l "$APK" | grep -q "com/sony/"; then echo "✗ APK 里混入了 com/sony 编译桩"; exit 1; fi
# PNG 未被重编码：抽一张与源码比哈希
SRC_MD5=$(md5sum src/main/res/drawable-nodpi/ic_sync_camera.png | cut -d' ' -f1)
APK_MD5=$(unzip -p "$APK" "res/drawable-nodpi-v4/ic_sync_camera.png" | md5sum | cut -d' ' -f1)
[ "$SRC_MD5" = "$APK_MD5" ] || { echo "✗ PNG 被重编码了（--no-crunch 未生效？）"; exit 1; }

echo "OK: $APK"
echo "$BADGING" | grep -E "^package|sdkVersion|targetSdkVersion"
echo "$DEXV"
