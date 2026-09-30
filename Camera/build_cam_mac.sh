#!/usr/bin/env bash
# SonyConnect 相机端 APK 构建 —— macOS 版。
#
# ★★ 正式发布请走 Gradle 链（与 2.0 同源、有实机背书）：
#      bash build_cam_mac.sh gradle
#    （Gradle 4.4.1 + AGP 3.0.1 + aapt2 + dx + cruncherEnabled=false + v1-only
#     签名 —— 产物与上游 Windows 机同规格。依赖 JDK8 / build-tools 26.0.2 /
#     platforms/android-15，本脚本会自动引导安装。）
#
# 默认（无参数）= **命令行备用链**：aapt → javac → dx → zipalign → apksigner，
# 与上游 build_cam.sh（Windows 备用链）同规格，参数逐项对齐。
#
# ★★★ 兼容口径（**索尼官方样本实测**，2026-09-21 定版，不得越线）：
#   索尼原厂在相机上发布的 SmartRemote 与全部 PlayMemories 相机应用 =
#   **minSdkVersion 10 + targetSdkVersion 10（或缺省）+ DEX 035**。
#   ★★ 签名必须 **v1-only**（2026-09-25 实测定论）：相机 flash 的 app 分区转储里
#     21 个已安装应用全部 v1-only、无一含 APK Signing Block；带 v2 块的包
#     （apksigner 的默认产物）装到相机上会在 Installing 阶段被拒，PMCA 报
#     "Communication error 100: Error completed"。
#   ★ 历史教训：2.5 一度用 `d8 --min-api 16` + 新 JDK + aapt 默认 crunch 构建，
#     APK 在 SDK10 老机型上"装上后一启动就闪退"；v2 签名块直接装不上。
#     不要用 d8 的 min-api>10、不要用新于 8 的 JDK 编相机端、不要开 PNG crunch。
#
# 两条链产物末尾都跑同一套自检（签名块/minSdk/DEX 版本/无 stub/PNG 未重编码），
# 任一项不符直接失败退出 —— 保证两条链产出的都是"相机认"的包。
#
# 用法：
#   bash build_cam_mac.sh            # 备用链（不依赖 Gradle）
#   bash build_cam_mac.sh gradle     # 正式链（Gradle + aapt2）
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$HERE/app"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT26="$SDK/build-tools/26.0.2"
AJAR_DIR="$SDK/platforms/android-15"
AJAR="$AJAR_DIR/android.jar"
OUT="$PROJ/build_out_mac"
APK="$PROJ/SonyConnect-Camera.apk"
MODE="${1:-}"

# ===== 工具链引导（两条链共用；已就位则全部跳过）=====
# JDK8：dx/apksigner 的正确运行环境（不要用新 JDK —— dx 在新 JDK 下会静默失败）
JDK8=""
if [ -n "$JAVA_HOME" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "1.8'; then
    JDK8="$JAVA_HOME"
elif [ -x "$HOME/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin/java" ]; then
    JDK8="$HOME/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home"
else
    CAMTOOLS="$HOME/Library/Android/cam-tools"
    if [ ! -x "$CAMTOOLS/jdk8u504-b01/Contents/Home/bin/java" ]; then
        echo "[boot] 下载 JDK8（temurin 8u504，清华镜像）..."
        mkdir -p "$CAMTOOLS"
        ( cd "$CAMTOOLS" && curl -sL -o jdk8.tar.gz \
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/mac/OpenJDK8U-jdk_x64_mac_hotspot_8u504b01.tar.gz" \
            && tar xzf jdk8.tar.gz && rm -f jdk8.tar.gz )
    fi
    JDK8="$CAMTOOLS/jdk8u504-b01/Contents/Home"
fi
export JAVA_HOME="$JDK8"

# build-tools 26.0.2：dx/apksigner 的 2.0 时代基准档
if [ ! -x "$BT26/dx" ]; then
    echo "[boot] 下载 build-tools 26.0.2 ..."
    mkdir -p "$SDK/build-tools"
    T="$(mktemp -d)"
    ( cd "$T" && curl -sL -o bt.zip \
        "https://dl.google.com/android/repository/build-tools_r26.0.2-macosx.zip" \
        && unzip -q bt.zip )
    # 该年代的包解出来目录名是 android-8.1.0（官方打包怪癖），归位改名
    rm -rf "$BT26" && mv "$T/android-8.1.0" "$BT26" && rm -rf "$T"
fi

# platforms/android-15（Android 4.0.3；zip 内目录名为 android-4.0.4，官方本名）
if [ ! -f "$AJAR" ]; then
    echo "[boot] 下载 platforms/android-15 ..."
    mkdir -p "$SDK/platforms"
    T="$(mktemp -d)"
    ( cd "$T" && curl -sL -o p15.zip \
        "https://dl.google.com/android/repository/android-15_r03.zip" \
        && unzip -q p15.zip )
    rm -rf "$AJAR_DIR" && mv "$T/android-4.0.4" "$AJAR_DIR" && rm -rf "$T"
fi

JAVA="$JDK8/bin/java"; JAVAC="$JDK8/bin/javac"; JAR="$JDK8/bin/jar"

# ===== 自检（官方兼容口径，两条链共用）=====
self_check() {
    echo "===== self-check: sony-official compatibility contract ====="
    # 不得含 APK Signing Block（v2/v3 签名块）—— 相机拒装的直接原因
    if LC_ALL=C grep -qa "APK Sig Block 42" "$APK"; then
        echo "✗ APK 含 v2/v3 签名块 —— 相机会拒装（PMCA: Communication error 100）"; exit 1
    fi
    echo "OK: 无 APK Signing Block（v1-only 签名）"
    BADGING="$("$BT26/aapt" dump badging "$APK")"
    echo "$BADGING" | grep -q "sdkVersion:'10'"        || { echo "✗ minSdkVersion 不是 10"; exit 1; }
    echo "$BADGING" | grep -q "targetSdkVersion:'10'"  || { echo "✗ targetSdkVersion 不是 10"; exit 1; }
    DEXV="$("$BT26/dexdump" -f "$APK" 2>/dev/null | grep -o "DEX version '[0-9]*'" | head -1)"
    [ "$DEXV" = "DEX version '035'" ] || { echo "✗ DEX 版本不是 035（得到：$DEXV）"; exit 1; }
    echo "OK: DEX 035"
    if unzip -l "$APK" | grep -q "com/sony/"; then echo "✗ APK 里混入了 com/sony 编译桩"; exit 1; fi
    echo "OK: 无编译桩泄漏"
    # PNG 未被重编码：抽一张与源码比哈希
    SRC_MD5="$(md5 -q "$PROJ/src/main/res/drawable-nodpi/ic_sync_camera.png")"
    APK_MD5="$(unzip -p "$APK" "res/drawable-nodpi-v4/ic_sync_camera.png" | md5 -q)"
    [ "$SRC_MD5" = "$APK_MD5" ] || { echo "✗ PNG 被重编码了（crunch 未关？）"; exit 1; }
    echo "OK: PNG 1:1 未重编码"
    echo "OK: $APK"
    echo "$BADGING" | grep -E "^package|sdkVersion|targetSdkVersion"
    echo "$DEXV"
}

# ===== 正式链：Gradle（aapt2）=====
if [ "$MODE" = "gradle" ]; then
    echo "[gradle] JAVA_HOME=$JAVA_HOME"
    ( cd "$HERE" && sh gradlew assembleDebug )
    cp "$PROJ/build/outputs/apk/debug/app-debug.apk" "$APK"
    self_check
    exit 0
fi

# ===== 备用链：与上游 build_cam.sh 同规格 =====
KS="$HOME/.android/debug.keystore"
cd "$PROJ"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex" "$OUT/lib/armeabi-v7a"

echo "[1/6] aapt package (res + assets, --no-crunch) ..."
# ★ --no-crunch：对齐 Gradle 的 cruncherEnabled=false。开着 crunch 会把所有 PNG
#   重编码，是"与 2.0 不等价"的可疑来源之一。
"$BT26/aapt" package -f \
  -M src/main/AndroidManifest.xml \
  -S src/main/res \
  -A src/main/assets \
  -I "$AJAR" \
  -J "$OUT/gen" -F "$OUT/res.zip" --auto-add-overlay --no-crunch

echo "[2/6] javac (JDK8, Java 1.6) ..."
find src/main/java -name '*.java' > "$OUT/sources.txt"
echo "$OUT/gen/R.java" >> "$OUT/sources.txt"   # aapt 生成的 R（在 gen 根）
"$JAVAC" -encoding UTF-8 -source 1.6 -target 1.6 -nowarn -Xlint:-options \
  -bootclasspath "$AJAR" \
  -classpath "$AJAR:libs/stubs.jar" \
  -d "$OUT/obj" @"$OUT/sources.txt"

echo "[3/6] dx (min-sdk 10) ..."
"$JAR" cf "$OUT/classes-in.jar" -C "$OUT/obj" .
# ★ 用 dx 而不是 d8：2.0 时代的 dexer 就是 dx，--min-sdk-version 10 与 manifest 一致。
#   若必须用 d8，参数只能是 `--min-api 10`，绝不能用 min-api 16。
"$JAVA" -Xmx1024M -Xss1m -Djava.ext.dirs="$BT26/lib" -jar "$BT26/lib/dx.jar" \
  --dex --min-sdk-version=10 --output="$OUT/dex" "$OUT/classes-in.jar"
test -f "$OUT/dex/classes.dex" || { echo "✗ dx 没有产出 classes.dex"; exit 1; }
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"

echo "[4/6] assemble ..."
cp "$OUT/res.zip" "$OUT/unsigned.apk"
( cd "$OUT" && "$BT26/aapt" add unsigned.apk classes.dex >/dev/null )
# 原生库（libsonyinfo.so）按 APK 的 lib/ 布局塞进去
cp src/main/jniLibs/armeabi-v7a/*.so "$OUT/lib/armeabi-v7a/"
( cd "$OUT" && "$BT26/aapt" add unsigned.apk lib/armeabi-v7a/libsonyinfo.so >/dev/null )

echo "[5/6] zipalign ..."
"$BT26/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign ..."
# ★ 与 ~/.android/debug.keystore 同钥：相机端覆盖安装要求签名一致
#   （换钥 = 先卸载 = 抹掉相机上的配对数据，绝对不行）
if [ ! -f "$KS" ]; then
    mkdir -p "$HOME/.android"
    "$JDK8/bin/keytool" -genkeypair -keystore "$KS" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
# ★★ 必须 v1-only（--v2-signing-enabled false）：见文件头"2026-09-25 实测定论"。
#    apksigner 默认会给 minSdk10 的包加 v2 块，必须显式关掉。
"$JAVA" -jar "$BT26/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 10 --v2-signing-enabled false --out "$APK" "$OUT/aligned.apk"

self_check
