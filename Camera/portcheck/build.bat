@echo off
rem Build PortCheck.apk with the local Android SDK (no gradle needed)
rem 管线与 SonyConnect/sonyui-demo 已验收版本一致：aapt -> javac -> d8 -> zipalign -> apksigner
setlocal
cd /d %~dp0

set SDK=C:\Users\93849\AppData\Local\Android\Sdk
set BT=%SDK%\build-tools\26.0.2
set AJAR=%SDK%\platforms\android-15\android.jar
set D8=%SDK%\build-tools\36.0.0\d8.bat
set JAVA_HOME_DIR=%ProgramFiles%\Eclipse Adoptium\jdk-21.0.12.101-hotspot
set OUT=build_out
set APK=PortCheck.apk

del /q %OUT%\* 2>nul
rmdir /s /q %OUT%\obj 2>nul
mkdir %OUT%\obj %OUT%\gen 2>nul

echo [1/5] aapt package ...
"%BT%\aapt.exe" package -f -m -M AndroidManifest.xml -S res -I "%AJAR%" -J %OUT%\gen -F %OUT%\res.zip || goto :err

echo [2/5] javac ...
dir /s /b src\*.java > %OUT%\sources.txt
"%JAVA_HOME_DIR%\bin\javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn -bootclasspath "%AJAR%" -classpath "%OUT%\gen" -d %OUT%\obj @%OUT%\sources.txt || goto :err

echo [3/5] dex ...
"%JAVA_HOME_DIR%\bin\jar.exe" cf %OUT%\classes-in.jar -C %OUT%\obj . -C %OUT%\gen . || goto :err
call "%D8%" --release --min-api 16 --lib "%AJAR%" --output %OUT% %OUT%\classes-in.jar || goto :err
if not exist %OUT%\classes.dex goto :err

echo [4/5] assemble + align ...
copy /b %OUT%\res.zip %OUT%\unsigned.apk >nul
cd %OUT%
"%BT%\aapt.exe" add unsigned.apk classes.dex >nul || goto :err
"%BT%\zipalign.exe" -f 4 unsigned.apk aligned.apk || goto :err
cd ..

echo [5/5] sign ...
if not exist debug.keystore (
  "%JAVA_HOME_DIR%\bin\keytool.exe" -genkeypair -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000
)
"%JAVA_HOME_DIR%\bin\java.exe" -jar "%BT%\lib\apksigner.jar" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --min-sdk-version 16 --out %APK% %OUT%\aligned.apk || goto :err

echo.
echo OK: %CD%\%APK%
"%BT%\aapt.exe" dump badging %APK% | findstr /i "package sdkVersion"
exit /b 0

:err
echo BUILD FAILED
exit /b 1
