





set -e


JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-8.0.442.06-hotspot}"
ANDROID_HOME="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"

JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
ANDROID_JAR="$ANDROID_HOME/platforms/android-15/android.jar"

SRC="stubs-src"
OUT="build/stubs-classes"
DEST="app/libs/stubs.jar"

echo "[1/3] 编译桩源码..."
rm -rf "$OUT"
mkdir -p "$OUT"
find "$SRC" -name '*.java' > /tmp/stubs-sources.txt
"$JAVAC" -encoding UTF-8 -source 1.6 -target 1.6 -cp "$ANDROID_JAR" -d "$OUT" @/tmp/stubs-sources.txt

echo "[2/3] 打包成 stubs.jar..."
mkdir -p app/libs
"$JAR" cf "$DEST" -C "$OUT" .

echo "[3/3] 完成: $DEST"
