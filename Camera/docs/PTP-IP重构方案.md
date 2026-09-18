# SonyConnect 双端 · PTP/IP 重写完整方案 v2.0

> 起草/定案：2026-09-12 · 基线：双端 **1.0 / versionCode 1**（GitHub tag `1.0`）· 目标：**2.0 / versionCode 2**
> 前置实测：`docs/P0-实测结论.md`（✅ 已完成，只测量未改码）
> 本文档为**实施依据**，取代 v1 讨论稿（v1 的原始分析已并入本文，不再单独保留）。
> **本轮仍未写任何生产代码。** P1 起手方式待你指定后开工。

---

## 0. 一页速览

| 维度 | 定案 |
|---|---|
| 协议 | PTP/IP（CIPA DC-X005）传输层 + 厂商操作码 `0x9000+`（**方案 A**） |
| 控制端口 | **15740 首选**（P0 实测空闲），候选回退 `[15740, 15741, 25740]` |
| 连接模型 | **单端口多连接**：控制 / 数据两类连接，靠首包类型判别；事件连接保留待用 |
| 文件传输 | 数据通道 + `Start/Data/End Data` 帧，**TCP 取代 FTP**，offset 续传语义不变 |
| 加密 | `AES-128-CBC` + `HMAC-SHA256`，**Encrypt-then-MAC**，每帧 64 位序号（GCM 实测可用，记 `cryptoSuite` 备选） |
| 设备身份 | 双端各持 **8 字节随机 deviceId**（16 位十六进制），落 `Init Command Request` 的 GUID 字段 |
| 配对 | 三阶段 `PAIR_BEGIN → PAIR_EXCHANGE → PAIR_COMMIT`，**COMMIT 是唯一落库点**，断线即回滚 |
| 配对码 | **8 位 Base32**（~40bit，剔 `0/O/1/I/L`）+ 二维码辅助；**相机屏显示、手机手输为主** |
| 版本 | **解冻升 2.0 / versionCode 2**（协议不兼容，必须双端同升） |
| 交付 | P1 协议内核 → P2 设备码+配对 → P3 数据通道+文件传输 → P4 缩略图 → P5 退出链+加固 → P6 联调清理 |
| 验收底线 | 桌面 `TestMain` 21 + `ProtocolTest` ≥27（重写）+ `BatchTest` 361，**每阶段全绿，不许绿变红** |

---

## 1. 目标与非目标

### 1.1 目标（你提的原始要求，逐条对应）

| # | 要求 | 本方案落点 |
|---|---|---|
| 1 | 双端增加**配对模式** | §4.4 两种模式、§6 配对协议、§11 P2 |
| 2 | 双端**设备记忆**（双方记住已配对设备） | §5 设备码 + 配对表（双端各存一份） |
| 3 | 无设备码时**自动生成固定设备码**（仅后端辨别用） | §5.1：8B 随机，持久固定，不上主界面 |
| 4 | 手机端配对模式 + 正常连接模式 | §4.4 |
| 5 | 相机端配对模式 + 正常连接模式 | §4.4（选项菜单新增「配对模式」行） |
| 6 | 考虑**配对/连接过程中突然断开** | §6.3 原子性 + §10 断线矩阵（12 种场景） |
| 7 | **协议与文件传输全部重写**，适配新配对/连接模式 | §3、§4、§9 |
| 8 | 协议端口使用 **PTP/IP** | §4.2（15740） |
| 9 | 文件传输**不再用 FTP，改 TCP** | §9（数据通道） |
| 10 | **保持原来所有功能正常** | §11 每阶段门禁 + §12 测试策略 |
| 11 | 注意**协议与文件传输的安全性** | §8 安全设计（认证 / 加密 / 抗重放 / 抗降级） |

### 1.2 非目标（明确不做，防过度工程）

- **不做完整 PTP 对象模型**（object handle 表 / `.odef` / 设备属性表）→ 决策 1 已选方案 A
- **不做证书 / PKI**（无可信 CA，相机无可靠时钟）
- **不做前向保密**（会话密钥源自长期密钥；配对码窗口 + 双向认证已覆盖主要威胁）
- **不做传输内容审计日志**（相机无可靠落点，且拖慢吞吐）
- **不追求被 gPhoto2 等第三方 PTP 客户端识别**（你要的是配对与可靠传输，不是互操作认证）

---

## 2. 现状盘点（改造面，实测统计）

### 2.1 现有协议与传输

| 项 | 现状 | 代码位置 |
|---|---|---|
| 控制协议 | 自研 UTF-8 行式 JSON，TCP **2122**，一问一答、无服务端推送、单客户端踢旧 | `ConnectServer.java`(262) ↔ `ConnectClient.kt`(188) |
| 文件传输 | 自研只读 FTP，TCP **2121**，commons-net | `FtpServer.java`(585) ↔ `FtpRepository.kt`(249) |
| 缩略图 | FTP 虚拟路径 `/.sonyconnect/th\|pv/<相机绝对路径>`，短连接 | `FtpServer` ↔ `FtpRepository.fetchVirtual*` |
| 目录浏览 / 续传 | FTP `LIST`/`CWD` / `REST offset` | 同上 |
| 心跳 | 手机端 3s `HEARTBEAT`（应答带 battery+lens），连续 3 次失联才判开 | `ConnectionCenter` |
| 设备信息 | 手机端 5s `INFO` 轮询 | `DeviceInfo.java`(232) |
| 心跳/INFO 串行 | 共用 `ConnectClient.ioLock` | `ConnectClient.kt` |
| 设备记忆 | 手机端仅存 `"型号\|序列号"` 字符串（`SettingsRepo.deviceHistory` / `preferredDevice`）——**无密钥、无双向**；相机端**完全不记** | `SettingsRepo.kt`(128) / `DeviceStore.kt`(31) |
| 设备码 | **不存在** | — |

### 2.2 调用点（改造波及范围）

**手机端（合计 3928 行 Kotlin）** — 两个门面被引用次数：

| 门面 | 被引用 |
|---|---|
| `FtpRepository.` | `ConnectionCenter`(5)、`ThumbStore`(2)、`DownloadService`(9)、`FilesScreen`(9) |
| `ConnectClient.` | `ConnectionCenter`(7)、`Scanner`(4)、`DeviceStore`(2)、`DownloadService`(3) |

**相机端（合计 5182 行 Java）**：
`FtpServer` / `ConnectServer` / `MainActivity`（`startServices` / `stopFtpServer` / `stopConnectServer` /
`ConnectHandler` / `shutdownServicesAndRadio` / 退出链 / 主页文案）+ `desktop-test/ProtocolTest.java`（27 项，必须整套重写）。

> **结论：改动面封装得很好。** 手机端替换 `FtpRepository` + `ConnectClient` 两个门面，上层（UI/传输/缩略图）只改类型引用；
> 相机端把两个 Server 合成一个 PTP/IP 服务。这是本方案工作量可控的根本原因。

### 2.3 固件侧证据（决定"端口不能写死"）

在 6300fw 固件解包 `extracted/dump/nflasha15_unpacked_unpacked/lib/libInfraPtpControl.so` 中查证：

| 证据 | 含义 |
|---|---|
| `PtptIp` 类：`InitSocket` / `AcceptDataSocket` / `AcceptEventSocket` / `ReadSocket` / `WriteSocket` / `CloseSocket` | 固件**自带**监听型 PTP/IP 服务端 |
| `SendInitEventAck` / `CheckInitiatorConnection` / `CheckHostInfo` / `AcceptSocket(sockaddr_in*)` | 有完整 PTP/IP 会话语义 |
| `socket` / `bind` / `listen` 齐全；`PTPT_init_ip` / `PTPT_open_ip` / `PTPT_send_data_ip` 与 USB 侧分流 | IP 通道面向 PC 遥控 |

**两点结论**：
1. 端口**不能写死** → 默认 15740，绑不上按候选回退，实得端口经发现流程通告手机端。
2. **不要复用固件那个 `PtptIp`**（它面向 PC 遥控、不是 SD 卡浏览，服务端我们既插不进也加不了配对）；
   我们实现自己的 PTP/IP 服务端，只在**协议语义**上向它对齐。
   ★ 它同时是我们的**同机参考实现**：`Init Event Ack` / Initiator 校验语义可对照（P1 实现时用 `strings` 交叉验证常量）。

---

## 3. 总体架构

### 3.1 连接模型（★ 相较 v1 讨论稿的改进）

v1 曾设想"相机再下发一个数据端口"。**改为单端口多连接** —— 更贴近 PTP/IP 原生语义，也少一个协商回合：

```
                        ┌──────────────────────────────────────────────┐
   手机（Initiator）     │            相机（Responder）· TCP 15740       │
                        └──────────────────────────────────────────────┘

  ① 控制连接 ──▶  首包 = Init Command Request (0x01)
                 会话：AUTH_CHALLENGE / LIST_DIR / STAT / THUMB_* / PING / DEVICE_INFO / EXIT_APP
  ─────────────────────────────────────────────────────────────────────
  ② 数据连接 ──▶  首包 = DATA_OPEN (厂商包，携带 sessionToken)
                 会话：Start Data(0x09) / Data(0x0A) / End Data(0x0B) / Cancel(0x0C)
                 承载：GET_OBJECT（缩略图 / 预览 / 原文件，带 offset+length）
  ─────────────────────────────────────────────────────────────────────
  ③ 事件连接 ──▶  首包 = Init Event Request (0x03)   ← ★ 保留，P5 可选实现
                 用途：服务端主动推送（缩略图进度 / 电量镜头变化）
                 现状替代：继续轮询（沿用 3s 心跳 + 5s INFO，零回归风险）
```

**为什么数据走独立连接**：大文件不阻塞心跳与命令；数据连接断开不影响控制会话（反之亦然）；
续传只需重开数据连接。**这是把"意外断线"从灾难降级为可恢复的关键设计。**

**为什么单端口**：PTP/IP 原生就是"一个端口、多条 TCP、靠首包类型判别角色"。控制/数据/事件三条连接首包不同，
服务端 `accept` 后读首包即知角色，无需额外端口协商。

### 3.2 模块划分

**相机端（新增 8 个类，删 2 个）**

| 新类 | 职责 |
|---|---|
| `PtpCodec.java` | 包编解码：长度/类型/字段读写（纯逻辑，可在桌面测） |
| `PtpIpServer.java` | 监听、多连接角色判别、会话、事务号、连接生命周期 |
| `PairingManager.java` | 配对状态机（三阶段）+ 配对码生成/倒计时/失败计数 |
| `PairingStore.java` | 配对表持久化（SJson 单行字符串 → SharedPreferences） |
| `DeviceIdentity.java` | deviceId 生成/持久化/友好名 |
| `CryptoSuite.java` | AES-CBC + HMAC、PBKDF2-HMAC-SHA256 自实现、能力探测 |
| `ObjectStore.java` | SD 卡访问：LIST_DIR / STAT / GET_OBJECT（含路径逃逸拦截） |
| `DataChannel.java` | 数据连接：token 校验、分块加密、offset 续传 |

**手机端（新增 6 个文件，删 2 个）**

| 新文件 | 职责 |
|---|---|
| `ptpip/PtpCodec.kt` | 与相机端 `PtpCodec` 对称的编解码 |
| `ptpip/PtpIpClient.kt` | 控制连接 + 会话（取代 `ConnectClient`） |
| `ptpip/DataChannel.kt` | 数据连接 + 分块解密 |
| `ptpip/ObjectRepository.kt` | 取代 `FtpRepository`，API 形状保持不变（见 §9.3） |
| `crypto/SessionCrypto.kt` | 会话密钥、EtM 收发 |
| `crypto/KeyDerivation.kt` | PBKDF2-HMAC-SHA256（纯 Kotlin）、KDF 派生 |
| `data/IdentityRepo.kt` | 手机端 deviceId |
| `data/PairingStore.kt` | 配对表（取代 `DeviceStore`） |

---

## 4. 线格式规范（PTP/IP）

> ★ **本节是"实现依据"，不是"已验证事实"。**
> P1 起手第一件事：**逐字段对照 CIPA DC-X005 规范原文核对本表**，并用固件
> `libInfraPtpControl.so` 的 `strings` 输出交叉验证常量；核对无误后先写**编解码单测**再写业务。
> （v1 讨论稿此处把 `End Data`/`Cancel` 的类型值写错，本文已更正，仍以核对为准。）

### 4.1 通用包头

所有 PTP/IP 包共用 8 字节包头，**小端（LE）**：

```
 0               4               8
 +---------------+---------------+----------------...
 |  Length (4)   |  Type  (4)    |   Payload (Type 相关)
 +---------------+---------------+----------------...
Length = 含包头在内的整包字节数
```

### 4.2 包类型表

| 值 | 名称 | 方向 | 载荷 |
|---|---|---|---|
| `0x01` | Init Command Request | 手机→相机 | GUID(16) + FriendlyName(UTF-16LE, 1B 长度前缀) + ProtocolVersion(4) |
| `0x02` | Init Command Ack | 相机→手机 | ConnectionNumber(4) + GUID(16) + FriendlyName + ProtocolVersion(4) |
| `0x03` | Init Event Request | 手机→相机 | ConnectionNumber(4) |
| `0x04` | Init Event Ack | 相机→手机 | （空 / ConnectionNumber，随实现） |
| `0x05` | Init Fail | 相机→手机 | Reason(4)：`0x01`=拒绝（未配对） / `0x02`=不支持 / `0x03`=设备忙 |
| `0x06` | Operation Request | 手机→相机 | DataPhaseInfo(4) + OperationCode(2) + TransactionID(4) + Params(4×n) |
| `0x07` | Operation Response | 相机→手机 | DataPhaseInfo(4) + ResponseCode(2) + TransactionID(4) + Params(4×n) |
| `0x08` | Event | 相机→手机 | EventCode(2) + TransactionID(4) + Params(4×n) |
| `0x09` | Start Data | 双向 | TransactionID(4) + TotalDataLength(**8**) |
| `0x0A` | Data | 双向 | TransactionID(4) + Data(…) |
| `0x0B` | End Data | 双向 | TransactionID(4) + Data(…) |
| `0x0C` | Cancel | 双向 | TransactionID(4) |

> ★ `Start Data` 的 `TotalDataLength` 是 **64 位**，不是 32 位 —— 这是最常见的实现坑，P1 单测要覆盖。
> `DataPhaseInfo`：`0`=无数据阶段 / `1`=Data-In（手机收） / `2`=Data-Out（手机发）。以规范核对为准。

### 4.3 握手与角色判别（服务端视角）

```
accept() → 读 8 字节包头 → 按 Type 判角色：
   0x01 Init Command Request → 控制连接
       ├ 查配对表：
       │    命中 → 回 Init Command Ack(0x02) → 进入双向认证（§7.2）
       │    未命中 → 回 Init Fail(0x05, reason=0x01)，随后立即断开
       └ ★ 未认证阶段的"最小身份应答"（供扫描用，见 §4.4）
   0x03 Init Event Request  → 事件连接（P5 可选）
   厂商 DATA_OPEN           → 数据连接（校验 sessionToken，设备未认证则直接拒）
   其它                      → 回 Init Fail(0x05, reason=0x02)，断开
```

### 4.4 ★ 操作码分配（厂商扩展区 `0x9000–0x9FFF`）

| 操作码 | 名称 | 替代 | 说明 |
|---|---|---|---|
| `0x9001` | `PAIR_BEGIN` | 新增 | 进入配对窗口 |
| `0x9002` | `PAIR_EXCHANGE` | 新增 | prekey / nonce 交换 |
| `0x9003` | `PAIR_COMMIT` | 新增 | **唯一落库点** |
| `0x9004` | `PAIR_ABORT` | 新增 | 主动放弃，双方清临时态 |
| `0x9010` / `0x9011` | `AUTH_CHALLENGE` / `AUTH_RESPONSE` | 替代 `HELLO` | 双向 challenge-response |
| `0x9012` | `PING` | 替代 `HEARTBEAT` | 应答仍携带 `batteryPct` + `lens` |
| `0x9013` | `DEVICE_INFO` | 替代 `INFO` | 全量设备信息 + thumb 状态 |
| `0x9020` | `LIST_DIR` | 替代 FTP `LIST`/`CWD` | 元数据**内联**在响应里（见 §4.5） |
| `0x9021` | `STAT` | 替代 FTP `SIZE`/`MDTM` | 同上 |
| `0x9022` | `GET_OBJECT` | 替代 FTP `RETR` + `REST` | **走数据通道**，带 `offset` + `length` + `kind` |
| `0x9023` | `THUMB_QUEUE_BEGIN` | 替代 `THUMB_BEGIN` | |
| `0x9024` / `0x9025` / `0x9026` | `THUMB_QUEUE_PAUSE` / `_RESUME` / `_CANCEL` | 同名替代 | |
| `0x9030` | `EXIT_APP` | 同名替代 | **先回包 → 延迟 300ms → 有序退出**，语义不变 |
| `0x9040` | `DATA_OPEN`（厂商**包类型**，不在操作码区） | 新增 | 数据连接首包，携带一次性 sessionToken |
| `0x9041`（事件） | `EV_THUMB_PROGRESS` | 新增 | 事件通道推送（P5，可选） |

`GET_OBJECT` 的 `kind` 参数：`0`=小缩略图(160×120) / `1`=大预览(1616×1080) / `2`=原文件。
→ 顺带保留原有"**相机零解码、只按字节偏移读内嵌 JPEG**"的性能优势（`ThumbnailExtractor` 不动）。

### 4.5 元数据内联 vs 数据通道的划分（★ 明确定界）

| 载荷性质 | 走哪 | 理由 |
|---|---|---|
| 小元数据（`LIST_DIR` 列表 / `STAT` 结果 / `DEVICE_INFO` / `PING`） | **内联在 `Operation Response` 的参数区或长度前缀 blob** | 一次往返，几 KB 级，不值得开数据连接 |
| 大字节流（`GET_OBJECT`：缩略图 / 预览 / 原文件） | **数据通道** | 可流式、可暂停、可取消、可续传，不阻塞控制连接 |

> 这样控制连接保持"纯请求/响应"的简单模型，不需要在控制连接上做 PTP/IP 的 DataPhase 交错 —— 
> 显著降低实现与调试复杂度，且**功能等价**。

### 4.6 响应码

| 值 | 名称 | 用途 |
|---|---|---|
| `0x2001` | `OK` | 成功 |
| `0x2002` | `GeneralError` | 未分类失败 |
| `0x2003` | `SessionNotOpen` | 未认证 / 会话已失效 |
| `0x2004` | `InvalidTransactionID` | 事务号不匹配 |
| `0x2005` | `OperationNotSupported` | 未知操作码 |
| `0x2006` | `AccessDenied` | 路径逃逸 / 越权 |
| `0x2007` | `ObjectNotFound` | 路径不存在 |
| `0x2008` | `DeviceBusy` | 正在传输，拒绝新会话（见 §9.4） |
| `0x2009` | `PairingFailed` | 配对码错 / 窗口超时 |
| `0x200A` | `CryptoRequired` | 抗降级：对端要求明文而本端要求加密 |

---

## 5. 设备码与设备记忆

### 5.1 设备码（deviceId）

- **生成时机**：首次启动检测到本地无设备码 → `SecureRandom` 生成 **8 字节**（64 bit）
- **持久化**：固定不变，此后仅在后端用于辨别设备
- **表示**：16 位十六进制（如 `3f7a91c40b2e5d86`）——**不上主界面**，仅"关于 / 配对"页供排障
- **存放**：相机端 `SharedPreferences`；手机端 `SharedPreferences`（现有 `settings` 表旁）
- **用途**：① 配对表主键；② PTP/IP `Init Command Request` 的 **GUID 字段**（规范原生位置，
  **不需要自己发明字段**）；③ 重连判别（同 deviceId 才允许踢旧）

### 5.2 配对表（双端各存一份）

| 字段 | 类型 | 说明 |
|---|---|---|
| `peerDeviceId` | String(16hex) | **主键** |
| `peerName` | String | 对端友好名（相机：`ILCE-6300 05186914`；手机：用户可改昵称） |
| `peerModel` / `peerSerial` | String | 相机侧信息，列表显示用 |
| `sharedKey` | byte[32] | 配对协商出的长期共享密钥，**密钥本身不过网** |
| `cryptoSuite` | String | 如 `AES128-CBC-HMAC256`（GCM 作备选位，不写死） |
| `encryptData` | boolean | 本条配对是否加密数据通道（配对时协商） |
| `pairedAt` / `lastSeenAt` / `lastIp` / `lastPort` | long/String/int | 排序 + 快速重连 |
| `protoVersion` | int | 对端协议代次，便于将来演进 |

存储格式：相机端 `SJson` 序列化成单条字符串存 prefs（**不引入新依赖**，Java 1.6 友好）；手机端存 JSON。

---

## 6. 配对协议

### 6.1 两种模式的行为差异

**相机端**

| 模式 | 行为 |
|---|---|
| 正常连接模式（默认） | 只服务配对表内设备；未配对设备的 `Init Command Request` → `Init Fail(reason=0x01)`，随后断开 |
| 配对模式 | 接收配对申请，**配对码上屏 + 二维码 + 180s 倒计时**；退出即回正常模式 |

**手机端**

| 模式 | 行为 |
|---|---|
| 正常连接模式（默认） | 只对配对表内设备做命中与自动连接 |
| 配对模式 | 扫描"可配对"设备 → 输码/扫码 → 配对成功 → **自动切回正常模式** |

★ **为了不把扫描功能搞死**：未认证阶段相机允许回一个**最小身份应答** —— 只含
`deviceId` 摘要、友好名、`pairingAvailable` 布尔。手机据此把设备标成"未配对（可配对）"。
**不泄露任何敏感信息**（型号/序列号/目录仅在认证成功后返回），且未认证连接随后立即断开。

### 6.2 配对流程（三阶段 + 原子提交）

```
手机                                            相机
 │  ── PAIR_BEGIN{devIdA, nameA} ──▶        进入配对窗口（180s，倒计时上屏）
 │                                           生成一次性配对码，上屏 + 二维码
 │  ◀── PAIR_BEGIN_ACK{devIdC, nameC, saltC} ──
 │  用户输入 / 扫码得到配对码
 │  ── PAIR_EXCHANGE{nonceA, enc(K_pair, prekeyA)} ──▶
 │  ◀── PAIR_EXCHANGE_ACK{nonceC, enc(K_pair, prekeyC)} ──
 │  双方各自算出 K = HMAC-SHA256(K_pair, prekeyA‖prekeyC‖devIdA‖devIdC)
 │  ── PAIR_COMMIT{HMAC(K,"commit",devIdA‖devIdC)} ──▶   ★ 相机此刻才落库
 │  ◀── PAIR_COMMIT_ACK{HMAC(K,"commit-ack",…)} ──       ★ 手机收到才落库
```

### 6.3 原子性铁律

> **`PAIR_COMMIT` 是唯一的落库点。** 任何一步断开 → 双方丢弃内存里的临时密钥，
> 配对表不变，重来即可。**不存在"一半配对成功"的中间态。**

其它规则：
- 配对窗口超时 / 失败 **5 次** → 作废当前配对码，需在相机上重新进入配对模式
- 同一 `peerDeviceId` 重复配对 → 覆盖旧记录（旧密钥立即失效）
- **配对码只在窗口内有效，不落盘**

---

## 7. 连接认证与会话

### 7.1 密钥派生分层

| 层 | 公式 |
|---|---|
| 设备身份 | `deviceId`（8B 随机，持久固定） |
| 配对 | 一次性 8 位 Base32 配对码 → `K_pair = PBKDF2-HMAC-SHA256(code, salt, 20000)` |
| 长期密钥 | `K = HMAC-SHA256(K_pair, prekeyA‖prekeyC‖devIdA‖devIdC)`，**双方各自算出，密钥本身不过网** |
| 会话密钥 | `K_enc = HMAC(K, "enc"‖nA‖nC)`、`K_mac = HMAC(K, "mac"‖nA‖nC)`，每次会话 nonce 全新 |

### 7.2 双向认证

```
① 相机 ── AUTH_CHALLENGE{nonceC} ──▶  手机
② 手机 ── AUTH_RESPONSE{nonceA, HMAC(K, nonceC‖nonceA‖"phone")} ──▶  相机校验
③ 相机 ── AUTH_CHALLENGE_ACK{HMAC(K, nonceA‖nonceC‖"camera")} ──▶  手机校验
★ 双方都需证明自己（防假冒相机/中间人）
```

DMZ 超时：认证 5s；失败即断开，不重试计入配对失败次数（仅配对阶段计）。

### 7.3 认证后

- `Operation Response` 起，`TransactionID` 严格配对；不匹配回 `InvalidTransactionID`
- 数据连接 `DATA_OPEN` 携带 **一次性 sessionToken**（绑定会话 nonce，用后即废）→ 防重放

---

## 8. 安全设计

### 8.1 分层手段

| 层 | 手段 |
|---|---|
| 设备身份 | 8B 随机 `deviceId`，持久固定 |
| 配对 | 一次性 8 位 Base32 配对码（窗口 180s，失败 5 次作废）+ 交换 prekey |
| 长期密钥 | `K` 双方本地算出，**从不过网** |
| 连接认证 | 双向 challenge-response（相机也要证明自己） |
| 会话密钥 | 每次会话全新 nonce 派生 `K_enc` / `K_mac` |
| 传输加密 | `AES-128-CBC` + `HMAC-SHA256`，**Encrypt-then-MAC**，每帧 64 位单调序号 |
| 抗重放 | 会话 nonce + 帧序号双向校验（乱序/重复帧直接弃） |
| 抗降级 | 对端不支持加密则拒绝连接（除非配对时明确协商明文并写入配对表） |
| 隐私 | 型号 / 序列号 / 目录内容**仅在认证成功后**返回 |

### 8.2 数据帧格式（数据通道 `Data` 包）

```
Length(4) | Type=0x0A(4) | TransactionID(4) | Seq(8) | Ciphertext(N) | MAC(32)
MAC = HMAC-SHA256(K_mac, Length‖Type‖TransactionID‖Seq‖Ciphertext)
IV  = sessionSalt(8) ‖ Seq(8)          （每帧唯一，杜绝 CBC 的 IV 重用）
明文分块 = 64 KiB 定长
```
- 明文前 4 字节放 `PlainLen`（末块补齐用），解密后校验
- `Seq` 从 0 单调递增，**接收端拒绝非递增** → 重放/乱序直接丢弃
- `Start Data` 携带总长度，供接收端预分配与进度计算

### 8.3 API 16 的现实约束（★ P0 实测已澄清）

| 项 | 方案假设 | P0 实测 | 处置 |
|---|---|---|---|
| `AES/GCM` | 很可能不可用（GCM 一般 API 19+） | **可用**（固件自带 provider） | **仍选 EtM（CBC+HMAC）**：分块流式加密下 IV/序号管理更简单，且避开 GCM nonce 重用"一次失误密钥全废"的坑；GCM 记 `cryptoSuite` 备选位 |
| `AES/CBC + HMAC-SHA256` | 稳 | **可用** | ✅ 定为默认套件 |
| ECDH / DH 2048 退路 | 需要 | — | **不需要**（方案不做前向保密，prekey 用随机值即可） |
| PBKDF2 | `SecretKeyFactory` 变体有坑 | — | **自实现 PBKDF2-HMAC-SHA256**（约 40 行，只依赖 API 16 就有的 `Mac`） |
| AES 吞吐 | 用来定决策 3 默认值 | **未实测**（按要求移除基准） | 决策 3 按"默认开 + 可协商明文"执行；真机联调若掉幅大再走明文 |

### 8.4 明确不做（同 §1.2）

不做证书/PKI、不做前向保密、不做传输内容审计日志。

---

## 9. 文件传输：TCP 取代 FTP

### 9.1 能力映射

| 原 FTP 能力 | 新实现 |
|---|---|
| 匿名只读、拒绝写命令 | 认证后只读；**协议层根本不存在写操作码** |
| `LIST` / `CWD` / `PWD` | `LIST_DIR{path}` → 结构化条目（名字/是否目录/大小/mtime），内联返回 |
| `SIZE` / `MDTM` | `STAT{path}` |
| `RETR` | 数据通道 `GET_OBJECT{path, kind, offset, length}` |
| `REST` 续传 | `offset` 参数（语义相同，能力等价） |
| 逃逸拦截（`../` → 550） | 保留：服务端 canonical path 前缀校验，失败回 `AccessDenied` |
| 128KB 大缓冲 / `TCP_NODELAY` / 256KB socket 缓冲 | 保留（吞吐优化不丢） |
| 被动模式 PASV/EPSV | 不再需要（数据连接由手机发起、相机按 token 接纳） |
| 匿名登录 `USER`/`PASS` | 由 `AUTH_CHALLENGE` 取代 |

### 9.2 续传三态（沿用现有定版语义）

| 结果 | 触发 | 语义 |
|---|---|---|
| `COMPLETED` | 正常收完 | `.part` → commit 原子改名 |
| `CANCELLED` | 用户取消 / 断开 | **保留断点**，可续传 |
| `FAILED` | 其它错误 | 保留断点，重试 |

重连后续传前**先 `STAT` 校验 size + mtime 未变**：未变则续传，变了则从头（避免拼出坏文件）。

### 9.3 手机端门面替换（API 形状保持，上层几乎不动）

```kotlin
// ftp/FtpRepository.kt  →  ptpip/ObjectRepository.kt
data class FtpEntry(...)      →  data class ObjectEntry(...)   // 字段一一对应
fun list(host, dir)           →  fun list(dir)
fun fetchVirtualThumb(...)    →  fun fetchThumb(path)
fun fetchVirtualPreview(...)  →  fun fetchPreview(path)
fun pumpToStream(...)         →  fun pumpToStream(...)         // 签名不变
fun joinPath / parentOf / breadcrumbOf                          // 原样保留（纯字符串工具）
```
→ `FilesScreen`(9 处) / `DownloadService`(9 处) / `ThumbStore`(2 处) / `ConnectionCenter`(5 处) **只改类型引用与 host 参数**，逻辑不动。

### 9.4 单客户端语义改造（防传输被抢占）★

原语义"新连接踢旧"在传输中会**把正在跑的传输打断**，不能照搬。新规则：

| 情形 | 处理 |
|---|---|
| 同 `deviceId` 新连接（真重连） | 踢旧，保留原语义 |
| 不同 `deviceId`，且当前无活动传输 | 拒绝 → `Init Fail(reason=0x03 busy)` |
| 不同 `deviceId`，且正在传输 | 拒绝并提示"设备忙"，**绝不打断传输** |

**性能目标**：不低于现基线（Wi‑Fi 端 2–3 MB/s 是物理上限，相机内网更快）。

---

## 10. 意外断线处理矩阵（你特别强调的部分）

| # | 场景 | 设计 |
|---|---|---|
| 1 | 配对中途**相机**断/重启 | 相机丢弃内存态，配对表不变；手机未收 `PAIR_COMMIT_ACK` → 不落库 |
| 2 | 配对中途**手机**断 | 同上镜像 |
| 3 | 配对成功后第一次连接失败 | 双方已落库，可重复尝试，**不需重配** |
| 4 | 认证握手（challenge-response）中途断 | 无副作用，重连重来 |
| 5 | **传输中数据连接断** | 手机保留 `offset`；重连后先校验 `size+mtime` 未变 → 续传，变了 → 从头 |
| 6 | **控制连接断但数据连接还在** | 数据连接在控制通道丢失后 **10s** 自行关闭，杜绝孤儿传输 |
| 7 | 相机端 `EXIT_APP` | 先回包 → 延迟 300ms → 有序关闭（**新增数据连接纳入关闭序列**），与手动退出完全一致 |
| 8 | 手机崩溃 / 拔网 | 相机心跳超时 → 清理会话 → **释放 SD 句柄**（铁律在协议层的对应物） |
| 9 | 重复配对同一设备 | 覆盖更新密钥，旧密钥立即失效 |
| 10 | 配对表损坏 / 解析失败 | 视为空表，要求重新配对，**不崩** |
| 11 | 数据连接 token 重放 | token 一次性 + 绑定会话 nonce，用后即废 |
| 12 | 认证后收不到心跳 | **3 次失联**判定断开（沿用现有定版，不改成"一次"） |

★ 所有阶段都有**明确 deadline**：配对 180s / 认证 5s / 心跳 3s×3 / 数据空闲 10s / 单次操作 5s。

---

## 11. 分阶段实施计划（一次一阶段，自测通过再进下一阶段）

### P1 · PTP/IP 协议内核（固定预共享密钥，不含配对）

| 交付 | 内容 |
|---|---|
| 前置 | **逐字段核对 CIPA DC-X005 原文** + `libInfraPtpControl.so` 常量交叉验证，先出线格式契约 |
| 相机端 | `PtpCodec.java` + `PtpIpServer.java`（控制连接、会话、事务号、坏包/超长包防护） |
| 手机端 | `ptpip/PtpCodec.kt` + `ptpip/PtpIpClient.kt` |
| 测试 | `desktop-test/ProtocolTest.java` **重写为 PTP/IP 版（≥27 项）**：包往返、坏包、长度越界、事务号、角色判别、踢旧、并发 |
| **验收** | 桌面 `ProtocolTest` 全绿 **+ 真机握手成功**（用固定预共享密钥，先不做配对） |

### P2 · 设备码 + 配对（双端 UI + 事务）

| 交付 | 内容 |
|---|---|
| 相机端 | `DeviceIdentity` / `PairingStore` / `PairingManager`；`MainActivity` 选项菜单加「配对模式」行 + 配对屏（配对码 + 二维码 + 倒计时） |
| 手机端 | `data/IdentityRepo.kt` + `data/PairingStore.kt`（取代 `DeviceStore`）；未连接页加"配对"入口；已配对设备列表；设置页可删配对 |
| 测试 | 配对三阶段 + 原子提交 + 断线回滚（**对照 §10 矩阵逐条做**） |
| **验收** | 配对成功；**配对中途拔网 → 双方配对表均无残留**（重点用例）；同设备重复配对覆盖；二维码在 640×480 上显示为正方形（预压 75%） |

### P3 · 数据通道 + 文件传输（替换 FTP）

| 交付 | 内容 |
|---|---|
| 相机端 | `CryptoSuite.java` + `ObjectStore.java` + `DataChannel.java`（token / 分块加密 / offset 续传） |
| 手机端 | `crypto/KeyDerivation.kt` + `crypto/SessionCrypto.kt` + `ptpip/DataChannel.kt` + `ptpip/ObjectRepository.kt` |
| 清理 | 移除 `commons-net` 依赖 |
| **验收** | `TestMain` 21 + **`BatchTest` 361 全绿（回归底线，不许绿变红）** + 真机整文件续传（拔网续传实测） |

### P4 · 缩略图与预取迁移

| 交付 | 内容 |
|---|---|
| 相机端 | `ThumbPrefetcher` 输出改走数据通道；`ThumbnailExtractor` **完全不动** |
| 手机端 | `ThumbStore` 走数据通道 `GET_OBJECT{kind=th\|pv}`；`ThumbPrefetcher` 的 `PayloadSource` 钩子保留 |
| **验收** | 批量缩略图回归（361 项）+ 传输时 `THUMB_PAUSE`/`RESUME` 联动仍生效 |

### P5 · 退出链整合 + 安全加固 + 版本升 2.0

| 交付 | 内容 |
|---|---|
| 相机端 | 退出链加入**数据连接的有序关闭**（铁律顺序不可变）；`ExitCompletedReceiver` kill 列表加 `PtpIpServer` |
| 双端 | 全链路加密打开 + 抗降级（`CryptoRequired`）+ 重放校验；**版本号升 2.0 / versionCode 2** |
| **验收** | 桌面回归全绿 + **退出后无残留**（SD 卡可正常交还固件、Wi‑Fi/热点正常恢复） |

### P6 · 双端真机联调 + 旧协议代码清除

| 交付 | 内容 |
|---|---|
| 联调 | 心跳 / INFO / 扫描 / 配对 / 缩略图 / 传输暂停恢复 / 失联回未连接 / 异常断线全流程 |
| 清理 | 删 `FtpServer.java` / `ConnectServer.java` / `ftp/FtpRepository.kt` / `protocol/ConnectClient.kt` / `data/DeviceStore.kt`；双端 APK 出包 + GitHub 打 tag `2.0` |
| **验收** | 全流程通过 + 装机 + 回归全绿 |

---

## 12. 测试与验收策略

### 12.1 三层门禁（每阶段必跑）

| 层 | 内容 | 门槛 |
|---|---|---|
| 主回归 | `desktop-test/run-tests.sh` → `TestMain` 21 + `ProtocolTest` + `BatchTest` 361 | **全绿，不许绿变红** |
| 协议单测 | `ProtocolTest` 重写为 PTP/IP 版 | ≥27 项，覆盖包往返/坏包/越界/事务号/角色/踢旧/并发/续传 |
| 真机联调 | 见 §12.2 | 逐条走查 |

样本（沿用现有）：`Desktop/DSC01781.ARW`、`DSC01779.JPG`、`Desktop/100MSDCF/`（361 文件）。

### 12.2 真机联调清单（P6）

| # | 用例 | 期望 |
|---|---|---|
| 1 | 未配对设备连接 | 收到 `Init Fail`，提示"未配对"，不崩 |
| 2 | 配对成功后自动连接 | 心跳 3s / INFO 5s 正常，电量镜头刷新 |
| 3 | 配对中途拔网 | 双方配对表无残留 |
| 4 | 传输中拔网 → 重连 | 续传成功，字节数接续正确 |
| 5 | 传输中另一台手机连接 | 收到 `DeviceBusy`，**当前传输不中断** |
| 6 | 缩略图批量 + 传输联动 | 传输时缩略图暂停，结束自动恢复 |
| 7 | 相机 `EXIT_APP` | 提示"正在退出···"→ 有序关闭 → 无残留 |
| 8 | 退出后重新开机 | SD 卡正常、Wi‑Fi/热点恢复正常（铁律验证） |
| 9 | 手机端杀进程再连 | 相机清理会话、释放 SD 句柄 |
| 10 | 删配对后连接 | 被拒，回到未配对态 |

---

## 13. 风险清单

| 风险 | 等级 | 应对 |
|---|---|---|
| 15740 被固件运行时抢占 | ~~高~~ **低** | ★P0 实测空闲；候选回退 `[15740,15741,25740]` 保留作保险，实得端口经发现流程通告 |
| **PTP/IP 规范字段顺序/类型记错** | **中** | ★P1 起手**逐字段对规范原文核对** + 固件库交叉验证，先出线格式契约再写业务（不凭印象） |
| API 16 加密能力不足 | ~~中~~ **低** | P0 实测 CBC/HMAC/GCM 均可用；PBKDF2 自实现规避 `SecretKeyFactory` 坑 |
| 相机 CPU 加密吞吐掉太多 | 中 | 未实测（按要求移除基准）；数据通道可协商明文（决策 3），真机掉幅大再走明文 |
| **重写导致既验功能回归** | **高** | 三层门禁（§12.1）；`FilesScreen`/`DownloadService` **只改门面不动逻辑**；旧协议保留到 P6 再删（待你确认，见 §14-Q2） |
| 双端协议不兼容的过渡期 | 低 | 协议版本号字段 + "必须双端同升"提示 + 版本升 2.0 明确代次 |
| 配对码被截获/暴力 | 低 | 8 位 Base32（~40bit）+ 180s 窗口 + 5 次失败作废 |
| 相机端 640×480 UI 承载配对屏 | 低 | 复用现有 `QrCode`(377 行) + 字体真值（`FsMS` 32px/0.7001）+ 二维码预压 75% |

---

## 14. 待你回答的问题（见回复中的选项）

| # | 问题 | 我的建议 |
|---|---|---|
| Q1 | **P1 起手方式**：桌面先跑通 / 先出线格式契约 / 先搭相机骨架 | 桌面先（先写纯 Java 1.6 内核 + 重写 `ProtocolTest` 测绿，再落到双端） |
| Q2 | **过渡策略**：双协议并存（开发期旧 FTP/2122 保留可回退）vs 直接硬切 | 并存到 P6 再删——风险最低，且每阶段都能装机自测 |
| Q3 | **手机端设备记忆范围**：多台相机可选 vs 只记最近一台 | 多台可选（配对表本就是多行结构，成本几乎为零） |
| Q4 | **解除配对**：手机端可删；相机端是否也要能删已配对手机 | 相机端也要（配对屏加一行"清空配对表"），否则换手机后相机端黑名单无法自救 |
| Q5 | **事件连接**（PTP/IP 标准 `Init Event Request`）：P5 可选实现 vs 不做、继续轮询 | P5 可选实现；P1–P4 继续轮询（沿用 3s/5s，零回归风险） |
| Q6 | **版本号何时升 2.0**：P5 统一升 vs 一开工就升 | P5 升（P1–P4 开发期仍 1.0，避免"版本号相同却协议不同"的迷惑期） |

---

## 附录 A · 逐文件改造清单（实测行数）

### 相机端 `Desktop/SonyConnect/app/app/src/main/java/com/bi2qfa/sonyconnect/`（5182 行）

| 文件 | 行数 | 处置 |
|---|---|---|
| `MainActivity.java` | 1947 | **改**：菜单加「配对模式」+ 配对屏；`startServices` 改起 `PtpIpServer`；退出链加数据连接关闭 |
| `FtpServer.java` | 585 | **删**（文件系统逻辑抽到 `ObjectStore.java`） |
| `ConnectServer.java` | 262 | **删**（协议逻辑抽到 `PtpIpServer.java`） |
| `ThumbnailExtractor.java` | 618 | **不动**（零解码读内嵌 JPEG 的性能优势） |
| `ThumbPrefetcher.java` | 192 | 改：输出改走数据通道 |
| `DeviceInfo.java` | 232 | 保留：字段并入 `DEVICE_INFO` 响应 |
| `SJson.java` | 267 | **不动** |
| `QrCode.java` | 377 | **不动**（配对二维码复用） |
| `BatteryView.java` | 60 | 不动 |
| `ExitCompletedReceiver.java` | 49 | 改：kill 列表加 `PtpIpServer` |
| `radio/`（4 类） | 593 | **不动** |
| **新增** | — | `PtpIpServer` / `PtpCodec` / `PairingManager` / `PairingStore` / `DeviceIdentity` / `CryptoSuite` / `ObjectStore` / `DataChannel` |

### 相机端 `desktop-test/`（546 行）

| 文件 | 行数 | 处置 |
|---|---|---|
| `ProtocolTest.java` | 235 | **整套重写**为 PTP/IP 版（≥27 项） |
| `TestMain.java` | 250 | 保留（提取器 + FTP 真样本），FTP 相关改走新门面 |
| `BatchTest.java` | 61 | 保留并扩展（361 项缩略图回归） |
| `run-tests.sh` | — | 保留 |

### 手机端 `AndroidStudioProjects/SonyConnect/app/src/main/java/com/bi2qfa/sonyconnect/`（3928 行）

| 文件 | 行数 | 处置 |
|---|---|---|
| `ui/screens/FilesScreen.kt` | 555 | 改：`FtpEntry`→`ObjectEntry` 类型引用（9 处），逻辑不动 |
| `ui/MainActivity.kt` | 359 | 小改：路由加配对页 |
| `core/ConnectionCenter.kt` | 336 | 改：接线换新门面（5+7 处）+ 配对态 |
| `transfer/DownloadService.kt` | 298 | 改：门面类型引用（9+3 处） |
| `ftp/FtpRepository.kt` | 249 | **删** → `ptpip/ObjectRepository.kt` |
| `core/StorageSinks.kt` | 249 | **不动** |
| `ui/screens/TransfersScreen.kt` | 245 | 不动 |
| `ui/screens/SettingsScreen.kt` | 241 | 改：加已配对设备管理（含删除） |
| `data/ThumbStore.kt` | 230 | 改：取图走数据通道 |
| `transfer/TransferStore.kt` | 219 | 不动 |
| `protocol/ConnectClient.kt` | 188 | **删** → `ptpip/PtpIpClient.kt` |
| `ui/screens/HomeScreen.kt` | 137 | 小改（已连接态信息源） |
| `data/SettingsRepo.kt` | 128 | 改：加配对表持久化 |
| `ui/screens/DisconnectedScreen.kt` | 120 | 改：加"配对"入口 |
| `protocol/Scanner.kt` | 108 | 改：探测改 PTP/IP 握手 |
| `core/ConnectionService.kt` | 79 | 小改 |
| `transfer/TransferItem.kt` | 51 | 不动 |
| `ui/theme/SonyConnectTheme.kt` | 46 | 不动 |
| `ui/ProgressUi.kt` | 34 | 不动 |
| `data/DeviceStore.kt` | 31 | **删** → `data/PairingStore.kt` |
| `SonyConnectApp.kt` | 25 | 不动（`ConnectionCenter.init` 保留） |
| `app/build.gradle.kts` | — | 改：**移除 `libs.commons.net`** |
| **新增** | — | `ptpip/PtpCodec.kt` / `ptpip/PtpIpClient.kt` / `ptpip/DataChannel.kt` / `ptpip/ObjectRepository.kt` / `crypto/SessionCrypto.kt` / `crypto/KeyDerivation.kt` / `data/IdentityRepo.kt` / `data/PairingStore.kt` |

---

## 附录 B · 定案决策索引（2026-09-12）

| # | 决策 | 定案 |
|---|---|---|
| 1 | PTP/IP 保真度 | 方案 A（传输层 + 厂商操作码 `0x9000+`） |
| 2 | 版本号 | 解冻升 2.0 / versionCode 2 |
| 3 | 数据通道加密 | 默认开，配对时可协商明文 |
| 4 | 配对码 | 8 位 Base32 + 二维码辅助（相机屏显示、手机手输为主） |
| 5 | 控制端口 | 候选回退 `[15740, 15741, 25740]`（P0 实测 15740 空闲，首选可用） |
| 6 | P0 前置实测 | ✅ 已完成，只测量未改码 → `docs/P0-实测结论.md` |
| 7 | 相机端菜单文案 | 「配对模式」（新增一行） |
| 8 | 加密套件 | `AES-128-CBC` + `HMAC-SHA256`，Encrypt-then-MAC（GCM 实测可用但作备选） |

---

## 附录 C · 铁律对照（任何改动不得违背）

> 你在写软件的退出流程时应该保证当收到退出命令进入退出流程时有序的关闭所有有关服务，
> 例如 wifi 或者 ftp，释放 sd 卡占用保证他们全部恢复到正常状态后才完全关闭软件，
> 保证不会影响到别的系统功能

本方案的对应落实：
- PTP/IP 服务端纳入**同一有序退出链**（停数据连接 → 停 PTP/IP 服务 → [热点]关 Direct→关 WiFi→轮询确认 / [WiFi] `wifiEnabledByUs` 则关回 → `deleteTraceFiles` → APO/NORMAL → `daFinishOnly()`）
- 退出时**显式释放 SD 句柄**（`ObjectStore` 关闭全部流）
- `EXIT_APP` 保持"先回包再延迟 300ms"语义不变
- 退出指示弹窗化（"正在退出···"，无按钮）
- §10 矩阵第 6/8 条即该铁律在协议层的映射

---

▶ **下一步：等你回答 §14 的 Q1–Q6，我即按指定方式进入 P1。**
