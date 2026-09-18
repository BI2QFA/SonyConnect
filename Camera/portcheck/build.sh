#!/usr/bin/env bash
# PortCheck 构建脚本（与 build.bat 完全等价的 bash 版；aapt -> javac -> d8 -> zipalign -> apksigner）
set -e
export PATH="/usr/bin:/bin:$PATH"
cd "$(dirname "$0")"

SDK="C:/Users/93849/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/26.0.2"
AJAR="$SDK/platforms/android-15/android.jar"
D8="$SDK/build-tools/36.0.0/d8.bat"
JDK="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
OUT=build_out
APK=PortCheck.apk

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/gen"

echo "[1/5] aapt package"
"$BT/aapt.exe" package -f -m -M AndroidManifest.xml -S res -I "$AJAR" \
    -J "$OUT/gen" -F "$OUT/res.zip"

echo "[2/5] javac"
find src -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
    -bootclasspath "$AJAR" -classpath "$OUT/gen" -d "$OUT/obj" @"$OUT/sources.txt"

echo "[3/5] jar + d8"
"$JDK/bin/jar.exe" cf "$OUT/classes-in.jar" -C "$OUT/obj" . -C "$OUT/gen" .
"$D8" --release --min-api 16 --lib "$AJAR" --output "$OUT" "$OUT/classes-in.jar"
test -f "$OUT/classes.dex"

echo "[4/5] assemble + align"
cp "$OUT/res.zip" "$OUT/unsigned.apk"
( cd "$OUT" && "$BT/aapt.exe" add unsigned.apk classes.dex >/dev/null )
"$BT/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] sign"
if [ ! -f debug.keystore ]; then
  "$JDK/bin/keytool.exe" -genkeypair -keystore debug.keystore -alias androiddebugkey \
      -storepass android -keypass android \
      -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
fi
"$JDK/bin/java.exe" -jar "$BT/lib/apksigner.jar" sign \
    --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 16 --out "$APK" "$OUT/aligned.apk"

echo
echo "OK: $(pwd)/$APK"
"$BT/aapt.exe" dump badging "$APK" | grep -iE "package|sdkVersion"
