#!/usr/bin/env bash
# SonyConnect 相机端 APK 构建（本机链：aapt → javac → d8 → zipalign → apksigner，无 gradle）。
# 用法：bash build_cam.sh   （在任意目录调用均可）
set -e

PROJ="/c/Users/93849/Desktop/SonyConnect-2.5-camera-20260918-210833/app/app"
SDK="/c/Users/93849/AppData/Local/Android/Sdk"
BT26="$SDK/build-tools/26.0.2"
BT36="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-15/android.jar"
JDK="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin"
OUT="$PROJ/build_out"
APK="$PROJ/SonyConnect-Camera.apk"

cd "$PROJ"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "[1/6] aapt package (res + assets) ..."
"$BT26/aapt.exe" package -f \
  -M src/main/AndroidManifest.xml \
  -S src/main/res \
  -A src/main/assets \
  -I "$AJAR" \
  -J "$OUT/gen" -F "$OUT/res.zip" --auto-add-overlay

echo "[2/6] javac ..."
find src/main/java -name '*.java' > "$OUT/sources.txt"
echo "build_out/gen/R.java" >> "$OUT/sources.txt"   # aapt 生成的 R（在 gen 根）
"$JDK/javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn -Xlint:-options \
  -bootclasspath "$AJAR" \
  -classpath "$AJAR;libs/stubs.jar" \
  -d "$OUT/obj" @"$OUT/sources.txt"

echo "[3/6] d8 ..."
"$JDK/jar.exe" cf "$OUT/classes-in.jar" -C "$OUT/obj" .
"$BT36/d8.bat" --release --min-api 16 --lib "$AJAR" --lib libs/stubs.jar \
  --output "$OUT" "$OUT/classes-in.jar"
test -f "$OUT/classes.dex"

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
KS="/c/Users/93849/.android/debug.keystore"
"$JDK/java.exe" -jar "$BT26/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 16 --out "$APK" "$OUT/aligned.apk"

echo "OK: $APK"
"$BT26/aapt.exe" dump badging "$APK" | grep -i "package\|sdkVersion" | head -4
