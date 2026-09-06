#!/usr/bin/env bash
set -e

JAVA_HOME="${JAVA_HOME:-C:/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main/java"
OUT="$HERE/build"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/4] 编译..."
"$JAVAC" -encoding UTF-8 -d "$OUT" \
    "$APP/com/bi2qfa/sonyconnect/FtpServer.java" \
    "$APP/com/bi2qfa/sonyconnect/ThumbnailExtractor.java" \
    "$APP/com/bi2qfa/sonyconnect/SJson.java" \
    "$APP/com/bi2qfa/sonyconnect/ConnectServer.java" \
    "$APP/com/bi2qfa/sonyconnect/ThumbPrefetcher.java" \
    "$HERE/TestMain.java" \
    "$HERE/ProtocolTest.java" \
    "$HERE/BatchTest.java"

echo "[2/4] FTP/缩略图/EXIF（真样本）..."
"$JAVA" -cp "$OUT" TestMain

echo "[3/4] 私有协议/预取..."
"$JAVA" -cp "$OUT" ProtocolTest

echo "[4/4] 100MSDCF 全量缩略图回归..."
"$JAVA" -cp "$OUT" com.bi2qfa.sonyconnect.BatchTest "C:/Users/93849/Desktop/100MSDCF"
