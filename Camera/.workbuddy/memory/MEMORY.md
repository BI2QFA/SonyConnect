# MEMORY.md —— SonyConnect 相机端工程记忆

> 2026-09-11 更新（以 2026-09-06 会话交接文档为准）。项目 `C:\Users\93849\Desktop\SonyConnect`。
> ★ **开工必读顺序**：本文件 → `.workbuddy/docs/交接文档-2026-09-06.md`（会话交接全文，权威状态）
> → `devlog/STATUS.md`。
> ZCode 会话原文另见 `C:\Users\93849\Desktop\SonyFTP\.workbuddy\zcode-import\snapshots\02-*.md`。
> 手机端工程在 `C:\Users\93849\AndroidStudioProjects\SonyConnect`（同一项目另一半）。

## 一、当前状态（权威）

| 项 | 值 |
|---|---|
| 相机端工程 | `C:\Users\93849\Desktop\SonyConnect`（**gradle 根在其下 `app/` 子目录**） |
| 相机端 APK | `C:\Users\93849\Desktop\SonyConnect\SonyConnect.apk` |
| 手机端工程 | `C:\Users\93849\AndroidStudioProjects\SonyConnect` |
| 手机端 APK | `...\SonyConnect\app\build\outputs\apk\debug\app-debug.apk` |
| GitHub | https://github.com/BI2QFA/SonyConnect.git（main 分支，tag `1.0`，commit "Initial commit"） |
| git 本地目录 | `C:\Users\93849\Desktop\SonyConnect-git`（`Camera/` + `Phone/` + 根两个 APK + `.gitignore`；**APK 与 local.properties 被 .gitignore 排除不入库**；源码已剥离全部注释） |
| 包名（双端一致） | **`com.bi2qfa.sonyconnect`**（旧包名 `bi2qfa.sony.connect` 已废弃） |
| 版本（双端） | **1.0 / versionCode 1** |

★ **版本冻结**：用户明确要求**此后编译不再改动版本号**。改版本号前必须先问用户。
★ 相机端装新包前**必须先卸载旧版**（versionCode 15 → 1 属降级，会被拒装）。

项目定位：**不是 SonyFTP 的更新**，是全新项目；相机上旧 SonyFTP v2.0 保留共存（都占 2121，不共跑）。

## 二、★ 铁律（用户原文，任何改动不得违背）

> 你在写软件的退出流程时应该保证当收到退出命令进入退出流程时有序的关闭所有有关服务，
> 例如wifi或者ftp，释放sd卡占用保证他们全部恢复到正常状态后才完全关闭软件，
> 保证不会影响到别的系统功能

### 相机端退出链（顺序不可变）
停 FTP（强拆会话释放 SD）→ 停 ConnectServer → ［热点］关 Direct → 关 WiFi →
轮询确认（≤10s，后台）／［WiFi］若 `wifiEnabledByUs` 则关回 → `deleteTraceFiles` →
APO/NORMAL → `daFinishOnly()`；
`ExitCompletedReceiver` 兜底同序后 `killProcess`；
`onPause` 竞速（`runForcedExitCritical`）毫秒级关键清理后立即 `daFinishOnly`。

## 三、工具链与构建命令（必须照抄，勿升级）

- JDK8：`C:/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502`
- AGP 3.0.1 + Gradle 4.4.1，compileSdk 15，buildTools 26.0.2，
  **Java 1.6**（无 diamond / 无 try-with-resources / 无 lambda），minSdk/targetSdk 10
- `stubs.jar`（compileOnly，7KB，由 `build-stubs.sh` 从 `stubs-src/` 生成，
  ★ 必须 `-encoding UTF-8`）；`packagingOptions { exclude 'com/sony/**' }` —— 编译桩绝不进 APK
- 构建：`cd C:/Users/93849/Desktop/SonyConnect/app && JAVA_HOME="C:/Users/93849/AppData/Local/a6300-tools/jdk1.8.0_502" ./gradlew assembleDebug`
- ★ **桌面回归是底线，必须全绿**：
  `cd C:/Users/93849/Desktop/SonyConnect/desktop-test && ./run-tests.sh`
  → `TestMain` 21 项（提取器 + FTP 真样本）+ `ProtocolTest` 27 项（协议/预取）
  + `BatchTest` 361 项（100MSDCF 全量缩略图）
  → 样本：`C:/Users/93849/Desktop/DSC01781.ARW`、`DSC01779.JPG`、
  `C:/Users/93849/Desktop/100MSDCF/`（361 文件）

## 四、架构与协议速查

- **FTP 2121**：只读 + REST 断点续传 + 虚拟缩略图路径
  `/.sonyconnect/th|pv/<相机绝对路径>`（RETR / SIZE / 150 三者一致，逃逸拦截返回 550）。
- **私有协议 TCP 2122**：UTF-8 行式 JSON，一问一答，**单客户端（新连接踢旧）**。
  命令：`HELLO{proto:1}` / `HEARTBEAT`（带 batteryPct + lens）/ `INFO`（全量设备信息 + thumb 状态）/
  `THUMB_BEGIN` / `THUMB_PAUSE` / `THUMB_RESUME` / `THUMB_CANCEL` /
  **`EXIT_APP`**（传输完成后自动退出：相机先回包再延迟 300ms 走退出流程）。
- **心跳 3s**（连续 3 次失联才判断开）/ **INFO 5s** 轮询。
- **缩略图管线**：`ThumbnailExtractor`（通用解析法，纯 Java）+ `ThumbPrefetcher`（LRU 64）。
- **UI**：640×480 帧缓冲被 16:9 拉伸 → 图标/二维码**预挤压 75% 宽**；
  按键按 scanCode（MENU=514 / ENTER=232 / DPAD）；
  索尼字体真值 FsSS=26px/0.7499、FsS=28px/0.7199、FsMS=32px/0.7001、FsML=36px/0.7001；
  高亮 = `row_bg_camera`（#CC3300 填充 + #DD7700 顶线）。
- **SDK 兼容**：`RadioWrapperFactory`（SDK≥16 → RadioWrapperJb / `WifiP2pExtManager`；
  <16 → RadioWrapperGb / DirectManager）；`getRootDir()`（SDK≤10 → `/android/mnt/sdcard`，
  ≥16 → `/android/storage/sdcard0`）；CameraEx / ScalarProperties 反射 + try/catch 兜底。
- **关于页**：顶部「SonyConnect 1.0」+「配套手机端 SonyConnect 1.0 使用」；
  支持上下键滚动（页脚"方向键 滚动 · MENU 返回"，每次进入回顶）；开发者行前空行分段。
- **EXIT_APP 弹窗**：`exit_ing_msg` 文案参数化（"传输完成，正在自动退出···"），
  其余与手动退出完全一致。

## 五、不可违反的技术约定

### ★ PMCA 单进程铁律（血泪教训）
给应用派生第二个 Android 进程（`Runtime.exec dalvikvm`、`android:process=":info"`）
→ 进程组被 SIGKILL、崩溃日志来不及写。这是"服务启动后自闪退"与"关于页读设备信息闪退"
困扰多轮的**真正死因**。v1.3 起改**单进程纯 Java**。派生原生二进制（telnetd）无事。

### ★ 日志落点
`filesDir/sonyconnect_log.txt`（应用私有目录）—— `/sdcard` 与
`/android/storage/sdcard0` 实测均写不进（后者 ENOENT），固件对 SD 全路径写保护。
**Trace.java 已在后续清理中删除（零引用）**，现不再写运行日志。

### ★ 无线电三定律（热点残留的根本原因）
1. `setDirectEnabled(false)` 只关功能开关、不拆组 → 必须 `WifiP2pManager.removeGroup()`。
2. `isDirectEnabled()` 走 ExtManager 回调查询，本固件应答回调迟迟不派发 → 超时返回 false（**会撒谎**）；
   真值改查 `requestGroupInfo`（`getLiveGroup()==null`）。
3. 热点启动状态机的事件接收器离开热点模式**必须注销** + `handleEvent` 顶部模式守卫，
   否则 WiFi 一开被 `WIFI_STATE_ENABLED` 唤醒重建热点 / 误报"无法启用 Direct 模式"。

### Tweak 事实（已读源码验证）
- `wifiManager.setWifiEnabled(true)` 标准调用即可自动连系统已绑定网络。
- 跳 WiFi 设置 action = `com.sony.scalar.app.wifisettings.WifiSettings`（跳前先 setWifiEnabled(true)）。

### CameraEx 反射读镜头
```
Class.forName("com.sony.scalar.hardware.CameraEx")
  → open(int, CameraEx$OpenOptions) 传 (0, null)   // 只有静态工厂，私有构造，无 getInstance
  → getLensInfo() → public 字段 LensName → release()
```
`getLensInfo()` 返回 null = **没有安装镜头**；`LensName` 是镜头名称。

### 设备信息读取
- ScalarProperties 纯 Java：`getString("model.name")`、`getString("model.serial.code")`、
  `getFirmwareVersion()`、`getString("version.platform")`（Java API 版本，整串如 `2.3`）、
  `getInt("sys.dest")`（**地区兜底**：1=COMMON / 2=CHINA）。
- ★★ **地区主路 = OpenMemories-Tweak 那条原生路**（用户定版："用 tweak 的路线"）：
  `NativeInfo.region()` → `libsonyinfo.so` → OSAL 消息 **BACKUP_SENSER(0x3E0166)**
  功能号 5 读 backup **preset data** → 校验 0x0C 处 `BK2`/`BK4` → 取 **0xC0** 处的串。
  本机固件镜像里的值是 **`CX79101_CN2`**（`nflasha2_unpacked/Backup.bin` 偏移
  0xC0，已逐字节核对；同分区有 `updater/dat4` = 平台里的 `/setting/updater/dat4`，
  即该分区挂作 `/setting`）。preset data 881121 字节 > OSAL 消息 0x130 内联缓冲，
  所以对面回指针、由 `native/mem.c` mmap **`/dev/mem`** 取回 —— 这条读路依赖
  `/dev/mem` 可读。
- **原生库出包**（改 `.c` 之后必做，详细命令见 `devlog/STATUS.md` 坑速查第 1 条）：
  ndk-build（NDK r23c / armeabi-v7a / android-16 / `APP_STL=none`）→ 只把
  `libsonyinfo.so` 拷进 `app/src/main/jniLibs/armeabi-v7a/`；**空 stub
  `libosal_uipc.so` 绝不进包**（它只为链接，运行时靠相机真库解析）。
  核对：`llvm-readelf -sW` 里三个 `osal_*` 必须是 `UND`。
- ⚠️ **原生调用的风险**：2026-09-06 真机出现过"原生调用带崩整个进程"，
  两次进程隔离（`Runtime.exec dalvikvm`／`:info` 独立进程）都被相机进程组策略杀掉，
  当时处置是删掉 .so 退回纯 Java。现在重新引入，针对性防护是
  `clean_jstring`（按 0x20 边界读、遇 NUL 即止、逐字节清洗可打印 ASCII）+
  Java 侧 `try/catch(Throwable)`。**`try/catch` 挡不住原生段错误** ——
  真闪退就把 `DeviceInfo.getRegion()` 的主路调用去掉，退回 `sys.dest` 兜底。
- ★★ **属性键一律以本机为准，不要跨机型照抄**。本机的权威键表 = 把
  `6300fw/extracted/dump/nflasha16_unpacked_unpacked/framework/com.sony.scalar.sysutil.Property.odex`
  的字符串表 dump 出来（正则 `[a-z][a-z0-9._]{4,40}` 且含 `.`，约 90 条）。
  已核过：`sys.dest` / `version.platform` / `model.*` 在；ma1co 桩里的
  `dest.info` / `version.api`（`PROP_DEST_INFO`）**在这台 A6300 上不存在** ——
  取不到不报错，只会在界面上永远显示 "—"。
- 存储容量：`getRootDir().getTotalSpace()` / `getFreeSpace()`（**不缓存**，换卡要刷新）。
- 原生库里的另外两条（BACKUP(0x3E014D) 按 ID 取属性）：`0x003e0005`→model_name、
  `0x00e70003`→serial_number（4 字节 hex）。**已不再使用**（型号/序列号现在走
  ScalarProperties，纯 Java 更稳），保留在 `native/backup.c` + `sonyinfo.c` 里作参考，
  JNI 符号包名已改成当前的 `com.bi2qfa.sonyconnect`。
- 电量：`ACTION_BATTERY_CHANGED` 粘性广播；`getBatteryRemainMin` 在本机恒 -1。

## 六、★ 实锤过的坑（改代码前必读）

1. **build-stubs.sh 必须 `-encoding UTF-8`**（否则 GBK 读 UTF-8 中文注释报 64 个错）；
   相机 Java 1.6 语法限制（无 diamond / 无 try-with-resources / 无 lambda）。
2. **电源拨杆竞速**（关机后久不能开机）：已完整分析（`runForcedExitCritical` 后台环 /
   终局加固的 `removeGroup` / `getLiveGroup` 同步 await 最长 8s）。
   修复方案曾实施，**被用户要求回退**，当前保持原行为；方向记录在 devlog 第二十三/二十四轮。
3. **设备信息缓存不随换卡刷新**。
4. **`EXIT_APP` 300ms 窗口极小概率丢包**（已容错）。
5. **自动退出后立即重连可能撞服务重启窗口**。
6. **固件升级可能破坏 ScalarProperties 键 / CameraEx 反射** —— 升级后需重新核对。
7. 历史教训：ThumbnailExtractor 旧版有部分照片缩略图/预览读不出
   → v2.3 重写为通用解析法（IFD 全链候选 → FFD8/FFD9 + SOF 校验 → 宽度分类；
   JPG 大预览尾 1MB 完整标记段扫描）。
8. 工具类坑：Windows 无 `strings`；`.bat` 调 `.bat` 必须写 `call`；
   JDK21 下 `dx.bat` 静默失败 → 用 build-tools 的 `d8`；`d8` 只吃 jar 不吃目录；
   `apksigner.bat` 在 `cmd //c` 下静默失败 → `java -jar lib/apksigner.jar`。
9. ★ Python 转义：`\b` 会被吃成 0x08 退格，`\U` 报错；
   字节级改文件用 `bytes([92])` 表示反斜杠。
10. 相对路径容易踩空 → **一律用绝对路径**。
11. ★ **`getRootDir()` 可能落在一个"存在但不是挂载点"的空目录**，此时
    `getTotalSpace()` 报的是**父文件系统**的大小 —— 看着像真值其实是错的。
    所以 SD 容量做了 `total<=0` / `>4TiB` 的合理性拦截，但"报父 fs"挡不住，
    只能靠人眼和相机菜单比。见 devlog `2026-09-13-相机固定信息与SD容量上报.md`。
12. ★ **`Platform` 是接口**：加方法后**两个实现者都要改**
    （`MainActivity.CameraPlatform` 真机实现 + `desktop-test/ProtocolTest.StubPlatform`
    测试桩），漏一个桌面回归就红 —— 这正是它该拦的事。
13. ★ **数值型 JSON 成员必须走 `SJson.member` 的 long 重载**（不加引号）：
    手机端用 `org.json` 的 `optLong`/`optInt` 读，写成字符串会**静默**回落成 -1，
    界面永远显示 "—" 且不报错。

## 七、清理记录

删除：`Trace.java`（零引用）、`menu_key_glyph` / `row_bg_hollow` drawable、
`qr_caption` 字符串、`FsMSS` / `ConnectTheme` 零引用样式。

## 八、相机 telnet

`python tools/telnet_cam.py "cmd"`（192.168.31.25:23；相机 busybox **无** am/pm/logcat/id/head）。

## 九、纪律

- **一次只做一个阶段**，自测通过再进下一阶段。
- 每版记录 `devlog/YYYY-MM-DD.md` 并同步 `devlog/STATUS.md`。
- ★ **不要猜**。用户明确批评过"你先不要自己瞎猜 你看看 tweak 项目怎么做的" ——
  先读固件/源码/日志，再动手。

## 十、GitHub 推送

`cd Desktop/SonyConnect-git && git add -A && git commit && git push`
（凭据已配置，上次直接推送成功。）

## 十一、待实施：PTP/IP 重构（2026-09-12 方案已提交，等用户审批）

**方案文档**：`docs/PTP-IP重构方案.md`（相机端工程内）。
**状态**：方案阶段，**未开始写代码**；用户要审批后再动手。

用户新要求（原文要点）：双端增加配对模式 + 设备记忆；双端在检测到本机无设备码时生成一个
固定设备码（仅后端辨别用）；双端各有配对模式与正常连接模式；要考虑配对/连接过程中突然断线；
**协议与文件传输全部重写**，协议端口用 **PTP/IP**，文件传输不再用 FTP 改 TCP；
保持原有全部功能正常；注意协议与文件传输的安全性。

### ★ 关键发现：相机固件本来就有 PTP/IP 实现
在 `6300fw/extracted/dump/nflasha15_unpacked_unpacked/lib/` 中查证到
`libInfraPtpControl.so` 含 `PtptIp` 类：`InitSocket`/`AcceptDataSocket`/`AcceptEventSocket`/
`ReadSocket`/`WriteSocket`/`CloseSocket`/`SendInitEventAck`/`CheckInitiatorConnection`/`CheckHostInfo`，
符号 `socket`/`bind`/`listen` 齐全，且 `PTPT_*` 有 IP 与 USB 双通道分流
（USB 侧走 `/dev/usb/sicd_data`、`/dev/usb/sicd_event`）。
→ ①**15740 端口有被固件抢占的真实风险，端口不能写死**（候选 15740/15741/25740 回退）；
   ② 我们有一个同机可对照的 PTP/IP 参考实现，语义向它对齐；
   ③ **不要复用**固件的 `PtptIp`（面向 PC 遥控，插不进去也加不了配对），只做语义对齐。
   ④ 注意：**不要在 `nflasha16`（Android 系统分区）里找 PtptIp —— 找不到，它在 nflasha15 的 lib**。

### 现有实现盘点（改造面）
- 控制协议：自研行式 JSON TCP **2122**（`ConnectServer.java` ↔ `ConnectClient.kt`）
- 文件传输：自研只读 FTP TCP **2121**（`FtpServer.java` ↔ `FtpRepository.kt` + commons-net）
- 缩略图：FTP 虚拟路径 `/.sonyconnect/th|pv/<相对路径>`
- 设备记忆：**手机端只存 `"型号|序列号"` 字符串，无密钥无双向**；**相机端完全不记设备**
- 调用点实测：手机端 `FtpRepository.` 25 处 + `ConnectClient.` 16 处，集中在
  `ConnectionCenter`/`ThumbStore`/`DownloadService`/`FilesScreen`/`Scanner`/`DeviceStore`；
  相机端 `FtpServer`/`ConnectServer`/`MainActivity`/`ProtocolTest`

### 方案要点（详见文档）
- 控制连接 PTP/IP 15740 + 事件连接 + **数据连接独立**（大文件不阻塞心跳，断线可续）
- ★ **PTP/IP 的 `Init Command Request` 自带 GUID 字段 —— 用户要的"设备码"正好落在规范原生位置**
- 操作码走厂商扩展区 `0x9000+`；`GET_OBJECT` 带 offset+length（同时替代 FTP RETR 与 REST，
  并承载缩略图 th/pv）
- 配对三阶段 `PAIR_BEGIN → PAIR_EXCHANGE → PAIR_COMMIT`，**只有 COMMIT 是落库点**，
  任何一步断开双方都不留残留
- 单客户端语义改为：同 deviceId 踢旧；不同 deviceId 拒绝（**绝不打断进行中的传输**）
- 未认证阶段只回最小身份应答（deviceId 摘要 + 名字 + 是否可配对），保住扫描能力
- 加密：配对码（8 位 Base32）→ prekey 交换 → 长期密钥 K → 每次会话 nonce 派生 K_enc/K_mac，
  AES-128-CBC + HMAC-SHA256 Encrypt-then-MAC，帧序号抗重放
- ★ **API 16 约束（已实测校正）**：`AES/GCM` 本机**实测可用**，但按决策 8 **仍走 CBC+HMAC**，
  GCM 只作 `cryptoSuite` 备选位；ECDH 退路 DH 2048；PBKDF2 自实现（只依赖 `Mac`）
- ★ **单端口多连接**（v2 相较 v1 的改进）：手机对 15740 另开连接，服务端按首包类型判角色
  （`0x01`→控制 / `0x03`→事件 / 厂商 `DATA_OPEN`→数据），少一个端口协商回合
- ★ **元数据内联 / 数据通道定界**：LIST_DIR / STAT / DEVICE_INFO 内联在 Operation Response，
  控制连接保持纯请求-响应；只有 GET_OBJECT（缩略图/预览/原文件）走数据通道
- ★ **数据帧格式定稿**：`Length(4)|Type=0x0A(4)|TransactionID(4)|Seq(8)|Ciphertext(N)|MAC(32)`；
  `MAC=HMAC-SHA256(K_mac, Length‖Type‖TransactionID‖Seq‖Ciphertext)`；
  `IV=sessionSalt(8)‖Seq(8)`（每帧唯一，杜绝 CBC IV 重用）；明文 64KiB 定长分块；Seq 单调抗重放
- ★ **包类型值已更正**（v1 曾写错）：Start Data=0x09 / Data=0x0A / End Data=0x0B / Cancel=0x0C；
  Init Cmd Req=0x01 / Ack=0x02 / Event Req=0x03 / Ack=0x04 / Fail=0x05 / Op Req=0x06 / Op Resp=0x07 / Event=0x08。
  §4 线格式定位为"实现依据非已验证事实"，P1 起手须对 CIPA DC-X005 原文 + 固件库 `strings` 交叉验证

### 已定案 8 项决策（附录 B，2026-09-12）
1. PTP/IP 保真度 = **方案 A**（传输层 + 厂商操作码 `0x9000+`，不做完整对象模型）
2. 版本号 = **解冻升 2.0 / versionCode 2**（协议不兼容，双端同升）
3. 数据通道加密 = **默认开**，配对时可协商明文
4. 配对码 = **8 位 Base32**（剔 `0/O/1/I/L`，~40bit）+ 二维码辅助（相机屏显示、手机手输为主）
5. 控制端口 = 候选回退 **`[15740, 15741, 25740]`**（P0 实测 15740 空闲，首选可用）
6. P0 前置实测 = ✅ 已完成，只测量未改码 → `docs/P0-实测结论.md`
7. 相机端菜单文案 = 新增「**配对模式**」一行
8. 加密套件 = **AES-128-CBC + HMAC-SHA256，Encrypt-then-MAC**（每帧 64 位序号）

### §14 待用户回答的 6 个问题（**当前卡点**）
| # | 问题 | 我的建议 |
|---|---|---|
| Q1 | P1 起手方式：桌面先跑通 / 先出线格式契约 / 先搭相机骨架 | 桌面先（纯 Java 1.6 内核 + 重写 `ProtocolTest` 测绿，再落双端） |
| Q2 | 过渡策略：双协议并存 vs 直接硬切 | 并存到 P6 再删（风险最低，每阶段可装机自测） |
| Q3 | 手机端设备记忆：多台相机可选 vs 只记最近一台 | 多台可选（配对表本是多行结构，成本≈0） |
| Q4 | 解除配对：相机端是否也要能删已配对手机 | 要（配对屏加「清空配对表」，防换手机后黑名单无法自救） |
| Q5 | 事件连接：P5 可选实现 vs 不做 | P5 可选实现；P1–P4 继续轮询（沿用 3s/5s，零回归风险） |
| Q6 | 版本号何时升 2.0：P5 统一升 vs 一开工就升 | P5 升（P1–P4 开发期仍 1.0，避免"版本号相同协议不同"的迷惑期） |

### 实施顺序
**P0 前置实测 → ✅ 已完成**；下一步 **P1 协议内核**
（相机端 `PtpIpServer.java` + 手机端 `PtpIpClient.kt` / `PtpCodec.kt`
+ `ProtocolTest` 重写为 PTP/IP 版 ≥27 项）
→ P2 设备码+配对 → P3 数据通道+文件传输 → P4 缩略图迁移 → P5 退出链+安全加固 → P6 联调清理。
每阶段门禁：`TestMain`(21) + `ProtocolTest`(≥27) + `BatchTest`(361) 必须全绿（P1 要重写 ProtocolTest）。

### 控制面厂商操作码表（`0x9000+`，实现依据）
`0x9001` PAIR_BEGIN / `0x9002` PAIR_EXCHANGE / `0x9003` PAIR_COMMIT / `0x9004` PAIR_ABORT /
`0x9010–0x9011` AUTH_CHALLENGE-RESPONSE / `0x9012` PING / `0x9013` DEVICE_INFO /
`0x9020` LIST_DIR / `0x9021` STAT / `0x9022` GET_OBJECT / `0x9023` THUMB_QUEUE_BEGIN /
`0x9024–0x9026` THUMB_* / `0x9030` EXIT_APP / `0x9040` DATA_OPEN（厂商包类型）/ `0x9041` EV_THUMB_PROGRESS。
响应码 `0x2001` OK … `0x200A` CryptoRequired。
