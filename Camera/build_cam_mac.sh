#!/usr/bin/env bash
# SonyConnect 相机端 APK 构建 —— macOS 版（与 build_cam.sh 同链：aapt → javac → d8 → zipalign → apksigner，无 gradle）。
# 依赖：Android SDK build-tools 36.0.0、Android Studio JBR（javac/jar/java）、API 15 平台包。
# 用法：bash build_cam_mac.sh [android-15目录]   （缺省自动下载 https://dl.google.com/android/repository/android-15_r03.zip）
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)/app"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.2"
[ -d "$BT" ] || BT="$SDK/build-tools/36.0.0"
JDK="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME="$JDK"   # d8/apksigner 的壳脚本靠 JAVA_HOME 找 java
OUT="$PROJ/build_out_mac"
APK="$PROJ/SonyConnect-Camera.apk"

# API 15 平台包（Android 4.0.3；zip 内目录名为 android-4.0.4，这是官方包的本名）
AJAR="${1:-}"
if [ -z "$AJAR" ]; then
    CAND="$(ls -d /tmp/cam-sdk/android-4.0.4 2>/dev/null || true)"
    if [ -n "$CAND" ] && [ -f "$CAND/android.jar" ]; then
        AJAR="$CAND/android.jar"
    else
        mkdir -p /tmp/cam-sdk
        curl -sS -o /tmp/cam-sdk/android-15.zip \
            "https://dl.google.com/android/repository/android-15_r03.zip"
        ( cd /tmp/cam-sdk && unzip -q -o android-15.zip )
        AJAR="/tmp/cam-sdk/android-4.0.4/android.jar"
    fi
fi

JAVAC="$JDK/bin/javac"; JAR="$JDK/bin/jar"; JAVA="$JDK/bin/java"
AAPT="$BT/aapt"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
KS="$HOME/.android/debug.keystore"

cd "$PROJ"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/lib/armeabi-v7a"

echo "[1/6] aapt package (res + assets) ..."
"$AAPT" package -f \
    -M src/main/AndroidManifest.xml \
    -S src/main/res \
    -A src/main/assets \
    -I "$AJAR" \
    -J "$OUT/gen" -F "$OUT/res.zip" --auto-add-overlay

echo "[2/6] javac ..."
find src/main/java -name '*.java' > "$OUT/sources.txt"
echo "$OUT/gen/R.java" >> "$OUT/sources.txt"   # aapt 生成的 R（在 gen 根）
"$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn -Xlint:-options \
    -bootclasspath "$AJAR" \
    -classpath "$AJAR:libs/stubs.jar" \
    -d "$OUT/obj" @"$OUT/sources.txt"

echo "[3/6] d8 ..."
"$JAR" cf "$OUT/classes-in.jar" -C "$OUT/obj" .
"$D8" --release --min-api 16 --lib "$AJAR" --lib libs/stubs.jar \
    --output "$OUT" "$OUT/classes-in.jar"
test -f "$OUT/classes.dex"

echo "[4/6] assemble ..."
cp "$OUT/res.zip" "$OUT/unsigned.apk"
( cd "$OUT" && "$AAPT" add unsigned.apk classes.dex >/dev/null )
# 原生库（libsonyinfo.so）按 APK 的 lib/ 布局塞进去
cp src/main/jniLibs/armeabi-v7a/*.so "$OUT/lib/armeabi-v7a/"
( cd "$OUT" && "$AAPT" add unsigned.apk lib/armeabi-v7a/libsonyinfo.so >/dev/null )

echo "[5/6] zipalign ..."
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign ..."
# ★ 覆盖安装要求签名一致：本机用 ~/.android/debug.keystore（与 Windows 机不同钥，
#   装到相机前需先卸旧版，会抹掉相机上的配对数据 —— 换回原 Windows 钥签即可免卸载）
if [ ! -f "$KS" ]; then
    mkdir -p "$HOME/.android"
    "$JAVA" -jar "$BT/lib/apksigner.jar" --version >/dev/null 2>&1 || true
    "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 16 --out "$APK" "$OUT/aligned.apk"

echo "OK: $APK"
"$AAPT" dump badging "$APK" | grep -i "package\|sdkVersion\|native-code" | head -5
