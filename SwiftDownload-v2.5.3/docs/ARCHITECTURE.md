# 极速下载 · 架构说明

面向维护者。描述模块边界、线程模型、状态机，以及几条**不能破坏的数据完整性约束**。
面向使用者的说明在 [USER_GUIDE.md](USER_GUIDE.md)。

---

## 1. 技术栈与约束

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin 1.9.24 |
| 构建 | Gradle 8.9 / AGP 8.5.2 / JDK 17 |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 24 |
| UI | 纯代码构建 View（无 XML 布局、无 Compose） |
| 存储 | SQLite（任务）+ SharedPreferences（设置）+ JSON 文件（书签/磁力源） |
| 原生 | jlibtorrent 1.2.0.18（arm64-v8a / armeabi-v7a） |

设计取向：**零第三方 UI 框架、零网络框架**（HTTP 用 `HttpURLConnection`），把依赖压到最低，
便于在一个 APK 里同时容纳浏览器、BT 原生库和下载引擎。

## 2. 模块图

```
MainActivity（四页主壳 / Intent 接管 / 浮层 / 投屏与边播接线）
├── browser/  BrowserPage（多标签 WebView + 首页书签 + 嗅探）
│             BookmarkManagerPage
├── transfers/TransfersPage（任务列表 + 批量操作 + 剪贴板卡片）
├── files/    FilesPage + FileSorts（排序/打开/分享/重命名/删除）
├── magnet/   MagnetSources（5 个内置源）
│             MagnetParsers（torrentkitty / dmhy / rowtable / generic）
│             MagnetSearch（并发抓取 + infohash 聚合）
│             MagnetSearchPage / MagnetSourceStore / MagnetSourceManagerPage
├── settings/ DownloadSettings（SharedPreferences）
│             DownloadSettingsPage / DownloadRules（Wi-Fi、时段）/ RateLimiter
├── player/   StreamServer（本地 HTTP 服务）/ TaskBacking / StreamMath
│             M3u8（解析 + 特性识别）/ M3u8Job（分片下载）/ VideoProbe / VideoSniff
├── bt/       TorrentEngine（jlibtorrent 封装）
├── dlna/     Dlna（SSDP + SOAP）/ DlnaPicker（设备选择 UI）
├── ui/       Ui（通用控件）/ FlowLayout
└── DownloadService（前台服务：任务表 / 调度 / 通知 / 引擎与 BT 的桥）
    ├── DownloadEngine（多线程 HTTP 引擎，纯 JVM 可测）
    ├── DownloadDatabase（SQLite v1→v3）
    ├── DownloadTask（模型 + 状态枚举）
    ├── StorageRoots（下载根目录探测与降级）
    ├── MediaTypes / Format / OpenFileReceiver
```

**分层原则**：`DownloadEngine`、`MagnetParsers`、`M3u8`、`StreamMath`、`FileSorts`、`DownloadRules`
都是无 Android 依赖或仅依赖纯 JVM 的模块，因此可以在 JVM 单测里直接跑；所有 Android 相关的粘合逻辑
集中在 `DownloadService` 与各 Page。

## 3. 线程模型

| 执行体 | 职责 | 注意事项 |
| --- | --- | --- |
| 主线程 | UI、`notifyUi()` 派发 | 任何任务状态变化都必须经 `notifyUi()` → `mainHandler.post` |
| `DownloadService` 协程（IO） | ticker：每 500ms 采样速度、回写状态、更新通知 | 只回写 `RUNNING` / `PROBING`，不得覆盖终态 |
| `DownloadEngine` 线程池 | 每个任务一个固定大小线程池（= 分段数） | 每线程独享 `RandomAccessFile` 与 `myPos` |
| `M3u8Job` 单线程 | 顺序下载分片 | 取消后回滚到分片边界 |
| `StreamServer` accept 线程 + 每客户端线程 | 本地播放服务 | 慢客户端不得阻塞 accept |
| `TorrentEngine.io` 单线程 | jlibtorrent 调用与元数据轮询 | 不在主线程碰 session |
| `DlnaPicker` 工作线程 | SSDP 搜索、SOAP 请求 | 结果 post 回主线程 |

通知 ID 约定：前台通知固定 `1`，任务通知 `1000 + taskId`。两者不能复用，否则第一个任务会顶掉
常驻通知，完成时又把前台通知 cancel 掉。

## 4. 任务状态机

```
                 addTask
                   │
                   ▼
               ┌────────┐  条件不满足（仅Wi-Fi/时段）
               │ QUEUED │◄──────────────┐
               └───┬────┘               │
       允许开始     │                    │
                   ▼                    │
               ┌─────────┐  BT 等元数据  │
               │ PROBING │───────────────┘
               └────┬────┘
                    │ 元数据到达 / HTTP 探测完成
                    ▼
               ┌─────────┐   pause   ┌────────┐
               │ RUNNING │──────────►│ PAUSED │
               └────┬────┘◄──────────└────────┘
                    │        resume
        ┌───────────┴───────────┐
        ▼                       ▼
   ┌───────────┐          ┌────────┐
   │ COMPLETED │          │ FAILED │
   └───────────┘          └────────┘
```

约束：

1. `COMPLETED` 只能由**验收通过**的路径写入（见第 5 节）。
2. ticker 不得把 `PAUSED` / `FAILED` / `COMPLETED` 改回 `RUNNING`。
3. `QUEUED` 的 `error` 字段存放**排队原因**（"等待 Wi-Fi 连接" / "等待允许下载时段"），
   不是失败原因；UI 用 `DownloadRules.isWaiting()` 区分颜色与文案。
4. 进程重启时只重建 `RUNNING` / `PROBING` 的任务，暂停态保持暂停。

## 5. 数据完整性约束（核心）

这几条是 v2.5.2 修复的成果，也是后续改动最容易破坏的地方。

### 5.1 HTTP 分段

- 请求过分段的任务，响应必须是 **206**；收到 **200** 一律失败（说明服务器忽略了 Range）。
- 必须校验 `Content-Range` 的起点等于请求偏移、总长度等于探测值。
- 每个分段必须收到**恰好** `end - start + 1` 字节；少一个字节即失败，多读的部分丢弃。
- 写偏移用线程私有 `myPos`，不能用共享计数器（边播回写会推进共享计数）。
- `externalWrite`（边播回写）只能推进到**本段边界**，跨段部分不计入本段。
- 合并前：各段计数之和 == `totalBytes`，`.part` 长度 == `totalBytes`；否则 `FAILED`。
- 合并前先关闭所有 `RandomAccessFile`，否则 Windows/部分文件系统上 rename 会失败并留下 `.part`。

### 5.2 边下边播

- `StreamServer` 的 `streamPos` 表示"实时写到的位置"，不是播放器请求的位置。
- 源站忽略 Range（200）时，流位置归零，再按需跳过已读字节。
- 播放器断开 8 秒后恢复多线程；恢复前同样执行 5.1 的收尾验收。

### 5.3 HLS

- 只接受未加密的标准 TS 分片；`EXT-X-KEY` / `EXT-X-MAP` / `EXT-X-BYTERANGE` 在解析阶段拒绝。
- 状态文件 `<id>.m3u8state` 记录 `(已完成分片数, 对应字节偏移)`，两者必须自洽。
- 恢复时把 `.part` 截断到记录偏移；分片下到一半被取消时不提交该分片。

### 5.4 路径与代次

- 同一目录下不允许两个**活动任务**共用 `savePath`；冲突时自动改名 `名称 (2).ext`。
- 「刷新链接」是一次**代次切换**：必须清 `.part` / `.swiftprog` / `.m3u8state`、结束旧流、
  重判 `kind`、重算路径、重置进度，再从 DB 重新读取该行。
- 「重新下载」必须按原始参数重建（m3u8 的 `variant` / `referer`、种子的 `hash`），
  不能在前端用 `isBt` 二分推断。

## 6. 数据库

`swift_download.db` / `tasks`，版本 3。

| 列 | 说明 |
| --- | --- |
| `id` | 自增主键，也是进度文件名（`<id>.swiftprog`）的一部分 |
| `url` / `file_name` / `save_path` | 源地址与落盘位置（`save_path` 会随 Content-Disposition 或重命名更新） |
| `total_bytes` / `downloaded_bytes` | `-1` 表示未知 |
| `status` | 0..5，对应 `DownloadTask.Status` |
| `threads` | 1..16 |
| `created_at` | 毫秒时间戳，列表倒序 |
| `error` | 失败原因，或排队原因 |
| `kind` | 0 HTTP / 1 BT / 2 m3u8 |
| `hash` | BT infohash（40 位十六进制） |
| `variant` / `referer` | HLS 选定变体与来源页 |

迁移策略：`onUpgrade` 只做 `ALTER TABLE ADD COLUMN`，不删列、不改既有行；新建库也走同样的列顺序。

## 7. 存储布局

```
<root>/
├── <文件>                       # 普通下载
├── <文件>.part                  # 下载中的临时文件（完成时 rename）
├── <id>.swiftprog               # 分段进度：每行 "段起点 已下载字节"
├── <id>.m3u8state               # HLS 进度：已完成分片数 + 字节偏移
├── 视频/ 音频/ 图片/ 文档/ ...   # 自动分类（可选）
└── BT/<种子名>/                 # BT 任务
```

`<root>` 由 `StorageRoots` 决定：公共 `Download` 可写就用它，否则退回
`getExternalFilesDir(DIRECTORY_DOWNLOADS)`。探测方式是真的写一个临时文件再删除，
不能只信 `canWrite()`。

## 8. 边下边播实现

```
播放器 ──HTTP──► StreamServer ──► TaskBacking
                                   ├── 已下载区间：直接读 .part
                                   └── 缺失区间：向源站 Range 请求
                                          └── 写入 .part + externalWrite 回写引擎计数
```

- `StreamMath` 负责区间合并与缺口计算（纯函数，有单测）。
- `TaskBacking` 复用一条长连接按序续读，避免每次拖动都重新握手。
- `StreamServer` 支持 Range、206/416、HEAD，按客户端线程并发。
- 当前绑定 `0.0.0.0` 且无鉴权，仅适合可信局域网（见第 11 节）。

## 9. HLS 与嗅探

- `VideoSniff` 判定请求是否为候选媒体（扩展名 + MIME + 内容类型）。
- `BrowserPage` 拦截 WebView 请求 → 弹出选择器 → `M3u8.parse` 解析 master，列出变体。
- 选定后以 `masterUrl + variant + referer` 建任务；`M3u8Job` 顺序拉分片、合并为 `.ts`。

## 10. BT 集成

- `TorrentEngine` 懒启动 session，`io` 单线程串行化所有 jlibtorrent 调用。
- 添加磁力后轮询 `fetchMagnet`（最长 180 秒），元数据到达通过 `AlertListener` 与轮询双通道回调。
- 元数据到达后同步真实文件名与 `save_path`，否则完成后"打开/投屏"会指向占位路径。
- 暂停后继续优先 `resume()` 会话内种子；删除任务时 `remove()` 停止做种；服务销毁 `shutdown()`。

## 11. 已知架构债

按影响排序，均为**有意保留**（自用场景，暂不处理）：

1. `DownloadService` 约 1130 行，承担任务仓库、调度、通知、流会话多职责，后续可按职责拆分。
2. `StreamServer` 绑定 `0.0.0.0`、无令牌/鉴权/连接数上限；DLNA 场景需要局域网可达，但可改为绑定选定网卡 + 随机路径。
3. 签名私钥与明文口令随源码分发，`debug` 复用 `release` 签名；对外发布前必须轮换。
4. `minSdk 24` 但 `startForegroundService` 是 API 26 接口；Android 7.x 需补兼容分支。
5. 仅打包 ARM 原生库，x86 模拟器加载 BT 会失败；未配置 `abiFilters`。
6. 无 CI、无 Lint 基线、无 instrumentation 测试；回归依赖 JVM 单测 + 手工真机验证。

## 12. 测试策略

- **纯逻辑优先**：分段数学、磁力解析、DLNA 报文、HLS 解析、排序、格式化全部可在 JVM 跑。
- **故障注入**：`DownloadIntegrityTest` 用裸 `ServerSocket` 起一个"不守规矩"的 HTTP 服务器，
  覆盖忽略 Range、短响应、错误 `Content-Range`，断言必须失败且不产出正式文件。
- **真实链路**：`RealEngineE2eTest` 下载公开测试文件并比对 SHA-1（含中断续传）；
  `RealWorldProbeTest` 探测真实 HLS/平台 URL 形态。两者外网不可达时由 `Assume` 跳过。
- **单测配置**：`testOptions.unitTests.isReturnDefaultValues = true`，否则 `android.util.Log`
  会抛 "not mocked"，把 `catch` 里的错误回调整个吞掉，故障路径在测试中表现为"无响应"。

运行：`./gradlew testDebugUnitTest`；构建：`./gradlew assembleRelease`。

---

# v2.6.0 架构增量

以下是对上面基础架构的增量说明，不替代原有章节。

## 新增模块

| 模块 | 职责 |
| --- | --- |
| `HttpUtil` | 统一连接打开器：手动跟踪 301/302/303/307/308（含跨协议、最多 5 跳），每跳重放 Range/Referer/Cookie/UA；全局唯一 UA 常量 |
| `QueueRules` | 并发队列上限（1–5）与等待原因的纯逻辑 |
| `BtTaskRules` | BT 任务终态保护（PAUSED/FAILED 不被 ticker 覆盖） |
| `ListenerOnceGuard` | 防止 `shutdown()` 后重建 session 时重复注册同一个 alertListener |
| `MediaStoreExport` | 完成后经 `MediaStore.Downloads` 导出公共目录，路径/分类纯函数 |
| `FileHash` / `HashDialog` | 一次流式读计算 MD5/SHA-1/SHA-256；比对按长度识别算法 |
| `M3u8Crypto` | AES-128 CBC/PKCS5Padding 解密、IV 解析（显式或序号大端） |
| `BtFileSelection` | 文件优先级映射、越界过滤、最大视频、就绪判定纯逻辑 |
| `remote/LanRemoteProtocol` + `LanRemoteServer` | 局域网 Web 投递：纯 JDK ServerSocket + 内嵌单页控制台 |

## 线程模型增量

- **Work Stealing**：分段线程池固定大小；某段完成后在 `stealLock` 内扫描剩余量最大的慢段，
  原子收缩其 `segEnds` 并登记新分段，再由同一线程池接管后半段。慢段线程每次写盘后在锁内
  按当前终点截断计数，因此"切分"与"计数"串行，验收口径与动态区间一致。
- **HLS**：主worker 线程 + 4 线程分片池；分片只写 `.hls/seg_N.bin`（先 `.tmp` 后原子改名），
  主worker 按序从缓存拼入 `.part`，每片更新 `.m3u8state`。失败重试指数退避 100/200/400ms。
- **DLNA 保活**：`serveFile()` 期间持 PARTIAL_WAKE_LOCK（10 分钟超时）与 WIFI 高性能锁，
  `stopServeFile()`/空闲回收/`onDestroy()` 三处释放。

## 数据完整性约束增量（在原 6 条之后）

7. **动态分段断点**：`.swiftprog` 由两列（start bytes）扩展为三列（start end bytes），
   旧两列按静态 `Segments.split` 补齐终点。恢复时用 `Segments.isResumable` 校验区间
   从 0 开始、首尾相接、完整覆盖文件，再按持久化区间重建 `segCounters/segEnds`，
   不再用静态起点集合过滤。`.part` 长度校验只统计 `bytes > 0` 的分段，
   避免把"尚未开始但起点很大"的新分段误计入。
8. **HLS 分片缓存**：`.hls/` 在任务完成、删除、刷新链接、重新下载四条路径都要清理；
   临时分片先写 `.tmp` 再原子改名，被强杀时只留 `.tmp`，下次重新下载，绝不把半片拼进文件。
9. **MediaStore 文件**：导出后 `savePath` 变为公共路径、`exportUri` 记录 content URI。
   删除任务需同时 `contentResolver.delete(exportUri)`；投屏用 `ParcelFileDescriptor`
   随机定位零拷贝（不复制整文件）；文件可用性判定为"File 存在 或 exportUri 非空"。

## 存储迁移

`DB_VERSION` 从 3 升到 4，只做三条 `ALTER TABLE ADD COLUMN`：
`cookie` / `user_agent` / `export_uri`，均为 `TEXT NOT NULL DEFAULT ''`，既有任务行零改动。

## 已知架构债增量

- 局域网面板无鉴权，仅适合可信网络临时开启；固定端口 8877（已设 reuseAddress）。
- `Dlna.lanIp()` 通过 `pickLanAddress` 过滤 tun/tap/ppp/dummy 等虚拟网卡并优先 wlan/eth，
  但仍无法覆盖所有厂商的自定义网卡命名。
- 未知 Content-Length 的流不支持断点续传（无可靠分段边界）。
