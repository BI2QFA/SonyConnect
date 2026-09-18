#!/usr/bin/env bash
# 桌面真验证（JDK8）：编译工程内纯 Java 类 + 测试入口，对真样本跑完整断言
# 用法：./run-tests.sh
#
# ★ 这里的 javac **没有 -cp**（空 classpath，只有 JDK 自身）。所以凡是加进
#   源列表的工程类，都必须零 Android 依赖 —— PtpCameraHandler 能用
#   Platform 接口注入平台能力、PairingStore 只收一个 File，就是为了满足这条。
#
# ★★ 内核源码强制 `-source 1.6 -target 1.6`：相机端固化工具链是
#   AGP 3.0.1 + compileSdk 15 + **JavaVersion.VERSION_1_6**，所以桌面这道门
#   必须用同一个源码级编译，否则会出现"桌面全绿、装包才炸"的假绿 —— 例如
#   Java 1.6 不允许内部类里出现静态方法，而默认源码级会放行它。
#   测试文件不在相机上跑，用默认源码级即可，所以分两次 javac。
#
# ★★ 编译器**固定**用下面这个 JDK8，不跟随环境里的 JAVA_HOME：让环境决定
#   编译器就等于这道门随机放行（曾经用 JDK21 编译过，1.6 的问题全被吞掉）。
#   要换编译器请显式给 A6300_JDK。
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
