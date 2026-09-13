
















set -e

JDK8="${A6300_JDK:-C:/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502}"
JAVAC="$JDK8/bin/javac"
JAVA="$JDK8/bin/java"

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/app/src/main/java"
PKG="$APP/com/bi2qfa/sonyconnect"
OUT="$HERE/build"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/5] 编译内核（Java 1.6 源码级，与相机端 build.gradle 一致）..."
"$JAVAC" -version
"$JAVAC" -source 1.6 -target 1.6 -Xlint:-options -encoding UTF-8 -d "$OUT" \
    "$PKG/FtpServer.java" \
    "$PKG/ThumbnailExtractor.java" \
    "$PKG/ThumbPrefetcher.java" \
    "$PKG/SJson.java" \
    "$PKG/Rand.java" \
    "$PKG/AppLog.java" \
    "$PKG/PtpCodec.java" \
    "$PKG/PairingStore.java" \
    "$PKG/PtpIpServer.java" \
    "$PKG/PtpCameraHandler.java"

echo "[1b/5] 编译测试入口..."
"$JAVAC" -encoding UTF-8 -cp "$OUT" -d "$OUT" \
    "$HERE/TestMain.java" \
    "$HERE/ProtocolTest.java" \
    "$HERE/PairingStoreTest.java" \
    "$HERE/BatchTest.java" \
    "$HERE/AppLogTest.java"

echo "[2/5] FTP/缩略图/EXIF（真样本）..."
"$JAVA" -cp "$OUT" TestMain

echo "[3/5] PTP/IP 线格式 + 加密套件 + 真 socket 会话 + 配对全流程..."
"$JAVA" -cp "$OUT" ProtocolTest

echo "[4/5] 配对表 / 配对窗口 / 设备码 边界..."
"$JAVA" -cp "$OUT" com.bi2qfa.sonyconnect.PairingStoreTest

echo "[4b/5] 调试日志环形缓冲（AppLog）..."
"$JAVA" -cp "$OUT" com.bi2qfa.sonyconnect.AppLogTest

echo "[5/5] 100MSDCF 全量缩略图回归..."
"$JAVA" -cp "$OUT" com.bi2qfa.sonyconnect.BatchTest "C:/Users/93849/Desktop/100MSDCF"
