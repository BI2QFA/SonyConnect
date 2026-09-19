# 旧协议层归档（retired / old-protocol）

2026-09-12 由 PTP/IP 重构落地时**移出源码树**（不是删除，随时可恢复）。

| 文件 | 原路径 | 处置 |
|---|---|---|
| `FtpRepository.kt` | `app/src/main/java/com/bi2qfa/sonyconnect/ftp/` | 被 `ptpip/ObjectRepository.kt` 取代（相机端 FTP(2121) 系统 + 手机端 FTP 客户端整体下线） |
| `ConnectClient.kt` | `app/src/main/java/com/bi2qfa/sonyconnect/protocol/` | 被 `ptpip/PtpIpClient.kt` 取代（旧私有 TCP 2122 行式 JSON 协议废弃） |
| `Scanner.kt` | `app/src/main/java/com/bi2qfa/sonyconnect/protocol/` | 被 `ptpip/Discovery.kt` 取代（TCP 2122 探活 → UDP Probe，协议端口同号） |

## 迁移对照（调用点已全部切换）

| 旧 | 新 |
|---|---|
| `FtpRepository.list` | `ObjectRepository.list` |
| `FtpRepository.fetchVirtualThumb` / `fetchVirtualPreview` | `ObjectRepository.fetchVirtualThumb` / `fetchVirtualPreview`（走 `OP_GET_OBJECT` + 独立文件端口） |
| `FtpRepository.pumpToStream` / `PumpResult` | `ObjectRepository.pumpToStream` / `PumpResult` |
| `FtpRepository.boundNetwork` | `ObjectRepository.boundNetwork` |
| `FtpRepository.joinPath` / `parentOf` / `breadcrumbOf` | 同名（逐字一致，UI 无感） |
| `ConnectClient.CameraInfo` | `data/CameraInfo.kt`（字段从 18 精简到 UI 真实消费的 8 个 + PTP/IP 连接标识） |
| `ConnectClient.info()` | `OP_DEVICE_INFO` → `CameraInfo.fromDeviceInfo` |
| `ConnectClient.heartbeat()` | `OP_PING` → `DeviceStore.applyPing` |
| `ConnectClient.thumbBegin/pause/resume/cancel` | `OP_THUMB_QUEUE_BEGIN/PAUSE/RESUME/CANCEL` |
| `ConnectClient.exitApp()` | `OP_EXIT_APP` |
| `Scanner.scan` | `Discovery.scan`（UDP Probe，友好名必须带 `VENDOR_TAG`） |
| `Scanner.Candidate` | `Discovery.DiscoveredCamera`（含 guid / 双端口 / 配对状态；**不再携带 model/serial/battery** —— UDP 应答本就不含） |

## 为什么归档而不是删

该工程不在任何 git 仓库跟踪范围内（上层 `C:\Users\93849` 虽是个仓库，但这三个文件从未 `git add`），
直接删除不可恢复。归档目录不在 Gradle sourceSet 内，不影响编译，也不会与新层"并存双份协议实现"。

确认彻底不用后可整体删除本目录。
