# 变更记录

只记录**用户可感知的行为变化**与**影响数据正确性的修复**。内部重构若不影响行为，归入所在版本末尾的"维护"项。

---

## v2.9.0 — BT 引擎升级与手机单手交互重做（2026-11-19）

版本号 `versionCode 14 / versionName 2.9.0`。`minSdk` 从 24 提升到 26（Android 8.0+）。

### 任务一 — 彻底解决 BT 不可达
- 移除 `libs/jlibtorrent-1.2.0.18.jar` 与 `jniLibs/` 下旧 `.so`（2018 年版本默认绑定 `NETLINK_ROUTE`，在 Android 11+ 被 SELinux 拦截，枚举不到网卡导致 DHT/tracker 全失效）。
- 接入 FrostWire 官方 Maven `https://dl.frostwire.com/maven`，依赖升级为 `com.frostwire:jlibtorrent-android:2.1.2.1`（libtorrent 2.x，自带 arm64/arm/x86 .so 与 R8 规则），`abiFilters` 只保留 `arm64-v8a`、`armeabi-v7a`。
- 适配 2.x API：`setEnableDht/setEnableLsd`、`maxQueuedDiskBytes`、`setDhtBootstrapNodes`、magnet 下载显式 `torrent_flags_t`、`Sha1Hash` 取法、`infoHashV1/V2`、`MetadataReceivedAlert` 改从 handle 取 `torrentFile()`、移除已删除的 `ALLOCATING` 状态。
- `listenInterfaces` 改为 `0.0.0.0:0,[::]:0`（5G IPv6 优先）；内置 DHT 引导节点 4 个；`announce_to_all_trackers/tiers` 全开。
- 内置 tracker 扩到 27 个 HTTP/HTTPS/UDP 混合列表；`.torrent` 文件也通过 `handle.addTracker` 补齐。
- 传输页 BT 任务卡新增独立诊断行：`等待元数据 · DHT节点 X · Tracker Y · 已连节点 Z`，0 值如实展示，详情里同步列出 Tracker/已连节点。

### 任务二 — 浏览器重做为真正的手机单手操作
- 首页删除顶部蓝色标签条与 ←→⟳⌂☆⋮ 六个按钮；只保留全宽圆角地址框（内含清除/嗅探）、磁力搜与影视站搜入口、3 列书签卡。
- 修复书签文字被拦腰截断：卡片高度改 `WRAP_CONTENT + minHeight 96dp`，标题最多两行完整显示。
- 网页态顶部仅标题胶囊 + 3dp 进度条 + 抓资源/N + ⋮；底部紧贴主导航上方是 5 等分大触控键（后退/前进/刷新或停止/收藏/标签数），禁用态不可点。
- 收藏改为居中确认弹窗；标签管理改为底部抽屉（新建/切换/关闭/回首页）；返回键统一由 `BrowserPage.handleBack()` 处理（有历史回退、无历史回首页）。
- 底部浏览器图标由易误认为禁止符的「圆+横线」改为指南针矢量绘制。

### 任务三 — 磁力搜索可达性
- 删除长期失效的 磁力猫/柠檬BT/SkrBT 默认源；TorrentKitty、动漫花园标注「需代理」。
- 磁力搜页新增「全网磁力索引 / 影视站直搜（免代理）」双模式；影视站直搜用关键词套站内模板（高清族/电影港/SeedHub，可长按添加自定义模板），打开详情页后用「抓资源」提取 magnet。
- 默认书签域名修正为 `https://cn.bing.com`、`https://hdzu.cc`；旧持久化数据中的失效域名就地迁移。
- 全部源超时且未开 HTTP 代理时主动提示改用影视站直搜或浏览器抓资源。

### 验证
- `testDebugUnitTest` 209/209、`lintDebug` 0 Error、`assembleRelease` 成功；`aapt2` 校验 APK 内为新版 `libjlibtorrent.so`。

---

## v2.8.0 — 真实用性重构（2026-11-19）

版本号 `versionCode 13 / versionName 2.8.0`。围绕「真正能稳定下载」与「单手好用的浏览器」，
不引入任何重型第三方框架（纯 Kotlin + Android SDK + androidx.webkit + jlibtorrent）。

### 模块一 — 救活下载能力

- 修复 BT 任务创建后 0 B/s：`TorrentEngine.io` 单线程死锁（`fetchMagnet` 阻塞 180 秒占唯一线程）
  与 `session.download()` 双重添加句柄冲突，改为事件驱动（元数据 alert + 非阻塞超时）。
- 补 DHT 4 引导节点、LSD/UPnP/NAT-PMP、全 Tracker 宣告、连接与缓存配置；
  磁力任务卡/详情显示 DHT 节点、状态、Tracker 与 Peer 数。
- 元数据未到且无下载量时保持「等待元数据」状态，不再误导显示「下载中 0 B/s」。
- 浏览器遇 `.torrent` 链接直接建 BT 任务；HTTP 失败任务新增「单线程重试」。

### 模块二 — 浏览器单手体验

- 首页极简：搜索胶囊 + 3 列书签方块，长按编辑；更新默认书签（高清族改 `hdzu.cc`）。
- 网页态紧凑标题栏（点击展开地址栏）+ 底部 6 键工具栏（后退/前进/刷新/收藏/标签/抓资源，前进后退置灰）。

### 模块三 — 网页打不开与广告

- WebView 接入 App HTTP 代理（`ProxyController` + 特性检测）；`target=_blank` 用户手势正确开新标签；
  SSL 证书异常弹窗询问；主框架加载失败显示内置错误引导页；补全现代 WebView 能力。
- 广告拦截升级为「网络拦截 + DOM 注入去悬浮广告」，强力模式默认关闭避免误伤播放器。

### 模块四 — 磁力搜索可用性

- 磁力源管理支持「＋ 添加新源 / 删除自定义源 / 一键测试连通性」，逐源显示命中数、403、超时。
- 搜索状态栏可展开每个源的命中/被拦截明细；空壳/被拦截源启用离屏 WebView 等 JS 渲染兜底。
- 浏览器「🎬 抓资源」一键扫描本页磁力/种子/电驴/40 位 hash，列表中勾选批量下载或全部复制。

**门禁**：`testDebugUnitTest` **200/200 通过**；`lintDebug` 0 Error / 40 Warning；`assembleRelease` 成功，
v2 scheme 签名有效，SO 库 arm64-v8a + armeabi-v7a 完整。严格遵守「属性声明在 init 前」铁律。

---

## v2.7.0 — 体验升级（2026-11-19）

版本号 `versionCode 12 / versionName 2.7.0`。五个阶段交付，v2.5.2 数据完整性约束与
v2.6.0 的下载/网络/HLS/BT 能力全部保留；数据库仅做一条 `ALTER TABLE`（v4→v5，加 `final_url`）。

### Stage 0 — 基线稳固
- 仓库目录永久规范为 `SwiftDownload/`，同步全部文档引用。
- 六项跨模块边界核查：修复 MediaStore 文件重命名回退（`ContentResolver.update(DISPLAY_NAME)`）、
  磁力种子 URL 统一走 `HttpUtil`、Manifest 补权限与 scheme；其余三项给出源码证据确认无缺陷。

### Stage 1 — 下载韧性与任务交互
- **刷新链接保留断点**：勾选后先探测新链接，仅当大小一致且支持 Range 时才保留 `.part`/`.swiftprog`
  续传，否则清空重下；DB v5 加 `final_url`。
- **网络瞬断自动重试**：区分可恢复瞬断/5xx 与硬错误，退避 3s→10s→30s，设置可调 0–5 次，
  状态行显示「网络重连 n/3」。
- **空间前置校验**：probe 后按剩余空间 + 32MB 缓冲校验，不足立即报错；传输页显示剩余空间。
- **任务详情弹窗**：完整文件名/类型/状态/错误、原始与最终 URL 可复制、路径、大小、分段或节点数。

### Stage 2 — BT / HLS 引擎深度补强
- **BT**：代理（HTTP/SOCKS5，可对 BT 单独开关）、完成后停种策略（立即/延迟 5 分/一直）、
  上传限速、磁力自动追加精选公开 Tracker；`setPieceDeadline` 实现真正的按需边下边播（含预读与首尾 piece）。
- **HLS**：支持 `#EXT-X-MAP`（fMP4 init 段拼接，输出 `.mp4`）；传统 TS 流可无重编码封装为 MP4，
  编码不兼容时静默保留 `.ts`。

### Stage 3 — 浏览器净化、嗅探体验与批量建任务
- **广告拦截**：内置域名/URL 规则 + 自定义规则，拦截广告与恶意外部跳转，拦截脚本弹窗；设置可开关。
- **细节**：标签 × 关闭与平滑滚动、地址栏聚焦全选与清空、桌面/手机 UA 切换、图片/链接长按菜单。
- **批量建任务**：多行粘贴，去重识别全部直链/磁力，复选框全选/反选后一键建立。

### Stage 4 — UI 质感、文件工具与备份生态
- **UI**：targetSdk 36 Edge-to-Edge 安全边距、锁定浅色主题、动态数字等宽（tnum）。
- **传输页**：全部/进行中/已完成/失败状态筛选（带数量角标）。
- **文件**：ZIP 安全解压（防 Zip Slip）、文本/日志/字幕内置查看（限 512KB）。
- **备份**：「我的 → 备份与恢复」一键导出/导入 JSON（书签、磁力源、广告规则、下载设置），
  带版本校验与合并/覆盖确认。

### 测试
`./gradlew testDebugUnitTest`：**184/184 通过**（v2.6.0 的 139 → 184，0 失败 0 跳过）；
`lintDebug` 0 Error；`assembleRelease` 成功（v2 scheme 签名、arm64-v8a / armeabi-v7a SO）。

---

## v2.6.0 — 并发安全、网络健壮性、引擎能力扩展（2026-11-19）

版本号 `versionCode 11 / versionName 2.6.0`。分四个阶段交付，下载完整性约束全部保留。

### Phase 1 — P0 缺陷与并发安全

- **边下边播**：`StreamServer` 支持 HEAD（返回与 GET 一致的 200/206/416 头但不发 body），
  416 补齐 `Content-Length` / `Accept-Ranges`；`TaskBacking.fetch/close` 加锁，修复多连接
  播放器并发 Range 请求导致 `streamPos/conn/stream` 竞态错位。
- **网络**：抽出 `HttpUtil`，手动跟踪 301/302/303/307/308（含跨协议）最多 5 跳，
  每跳重放 Range/Referer/Cookie/UA；统一下载与 WebView 的 UA 常量。
- **引擎**：分段线程池在成功/失败/取消/探测异常各路径均 shutdown，修复核心线程永久泄漏；
  `Content-Disposition` 改名遇同名文件或 `.part` 占用时自动追加 ` (2)/(3)`；
  `.part` 写句柄复用并在合并前关闭。
- **服务/BT**：`start(ctx)` 按 API 26 分流（`startForegroundService`/`startService`），修复 API 24 崩溃；
  新任务启动时统一挂前台通知；`ListenerOnceGuard` 防止 BT 监听器重复注册；
  BT 完成统一到 `finishBtTask()` 防重，`onError` 清跟踪表，ticker 不再覆盖 PAUSED/FAILED；
  `.torrent` 任务补齐 Wi-Fi/时段检查。

### Phase 2 — 网络健壮性与系统适配

- 探测对 HEAD 与退化 GET **都要求 2xx**，403/404 错误页不再被读成文件大小；
  记录重定向后的 `finalUrl` 供分段直连。
- 未知 `Content-Length`（Chunked）改为单线程流式下载到 EOF，完成后回填真实长度再走同一套验收。
- `DownloadTask` 新增 `cookie`/`userAgent`/`exportUri`；DB 升到 **v4**，只做三条
  `ALTER TABLE ADD COLUMN`，既有任务零改动；浏览器用 `CookieManager` 抓取登录态并全链路透传。
- 「新建任务」增加可折叠高级选项（文件名 / Referer / Cookie / UA）。
- 新增并发队列（1–5，默认 3），超限进入 `QUEUED`「等待空闲队列」，任务结束后立即补位；
  运行中任务若切到移动数据或超出时段，自动挂回排队，条件恢复后自动拉起。
- Android 10+ 且使用降级目录时，完成后经 `MediaStore.Downloads` 导出到
  `Download/极速下载/[分类]/`，更新 `save_path`/`export_uri` 并清理私有副本。

### Phase 3 — 核心引擎能力

- **Work Stealing**：率先完成的分段扫描最慢分段，从当前前沿对半切分并派给空闲线程；
  切分在锁内完成"收缩终点 + 登记新段"，验收/回写语义与新区间一致。
- **HLS**：支持 AES-128 解密（CBC/PKCS5Padding，显式 IV 或按分片序号）、`EXT-X-BYTERANGE`，
  4 线程并发拉片 + 单片失败指数退避重试 3 次 + `.hls/` 缓存 + 原子改名防半片 +
  分片级进度估算与速度采样；fMP4 / SAMPLE-AES 仍明确拒绝。
- **BT**：支持种子内选文件（未勾选设 IGNORE）、顺序下载开关、已完整落盘视频的「预览」入口。

### Phase 4 — 浏览器、投屏与文件

- 嗅探扩展到 mkv/flv/mov/webm；新增剪贴板智能识别（整段文案提取直链/磁力），
  回到 App 获焦即弹识别卡片；地址栏常驻「嗅探(N)」角标，可重新查看或批量下载；
  WebView 随 Activity 生命周期 pause/resume/destroy。
- DLNA 填充标准 DIDL-Lite 元数据（标题 + `res protocolInfo`），投屏期间持
  WakeLock/WiFiLock，空闲阈值 2 分钟延长到 10 分钟；弹窗关闭时停止发现线程。
- 文件页长按与已完成任务卡新增「校验哈希 / 详情」：MD5 / SHA-1 / SHA-256 一次算完，
  可粘贴官方值比对；浮层统一「← 返回 + 标题」；重命名拒绝 `.` 开头。
- 新增局域网投递面板（「我的 → 局域网传链接」）：纯 JDK `ServerSocket` 内嵌单页控制台，
  支持粘贴直链/磁力、上传 `.torrent`、实时查看任务列表。默认关闭、无鉴权，仅可信网络使用。

### Phase 5 — 交叉检查修复

针对四阶段联动处的隐患逐条核查并补测：

- **动态断点丢失**：`.swiftprog` 从两列扩展为三列（起点/终点/字节），兼容旧格式；
  恢复时按持久化区间还原，不再用静态 `Segments.split` 校验动态起点；`.part` 长度校验
  只统计实际写过字节的分段。新增"动态切分后续传"端到端测试（验证请求 `bytes=200000-300000`
  而非从头重下）。
- **MediaStore 联动**：删除任务同时删 MediaStore 条目；已导出文件的投屏改用
  `ParcelFileDescriptor` 随机定位零拷贝（不再把 2~4GB 视频复制进 cacheDir）；
  「投屏/播放/校验」按钮按 `fileAvailable` 判断，打开走 content URI。
- **HLS 缓存孤儿**：`.hls/` 纳入删除/刷新链接清理路径，递归删除；补多 Key 轮换解析测试。
- **网卡识别**：`pickLanAddress` 过滤 tun/tap/ppp/dummy 等虚拟网卡，优先 wlan/eth；
  172.x 只接受 16–31 私有段；补快速开关端口复用测试。

### 测试

`./gradlew testDebugUnitTest`：**139/139 通过**（v2.5.3 的 90 → 101 → 111 → 121 → 132 → 139）。

---

## v2.5.3 — 代码清理与文档完善（2026-11-17）

**下载行为与 v2.5.2 完全一致**，不含功能变更。版本号 `versionCode 10 / versionName 2.5.3`。

### 清理

- 删除被新实现取代的数据库接口：`updateUrl`（由 `resetForRefresh` 取代）、`updateM3u8Choice`（建任务时已落库）。
- 删除无调用方的入口：`DownloadBinder.speedsSnapshot/renotifyAll/startStream`、私有 `renotifyAll`、
  `TransfersPage.onSpeed`、`MagnetSources.byId`、`DownloadTask.partFile`。
- 删除死参数：`addSiteCard(etHome)`、`renderSites(etHome)`、`Ui.pressable(ctx)`、`Ui.iconBadge(sizeDp)`、
  `TorrentEngine.addMagnet(displayName)`、`Dlna.cast(title)`、`buildSetUriSoap(title)`、
  `MagnetParsers.decodeFromUrl(row)`、`DownloadEngine.writeAt(t)`。
- 删除冗余代码：`probe()` 中未使用的 `code`、`updateProgressNotif` 中未使用的 `pct`、
  `MediaTypes.mimeOf` 的无用绑定、10 处未使用 import、测试中两个未使用变量。
- 版本号改为从 `PackageManager` 读取，修掉界面仍显示 `v2.5.1` 的硬编码。

### 文档

- 新增 `docs/USER_GUIDE.md`（用户手册）、`docs/ARCHITECTURE.md`（架构与完整性约束）、本变更记录。
- `README.md` 重排为"概览 + 文档索引 + 构建/测试 + 设计约束"，历史修复明细移入本文件。
- 更新 `docs/REGRESSION.md`，补充 v2.5.3 的清理复核与 90/90 复跑结果。

---

## v2.5.2 — 数据完整性修复

主题：堵住"文件看起来下完了、内容其实是坏的"这一类问题。
版本号 `versionCode 9 / versionName 2.5.2`。

| # | 问题 | 影响 | 修复 |
| --- | --- | --- | --- |
| 1 | 服务器忽略 Range 返回 200 时照常写入分段 | 分段整体错位，却标记完成 | 多段任务遇到 200 立即失败；单线程续传退回 0 重下 |
| 2 | 不校验 `Content-Range` | 服务器返回错误起点时写入错误偏移 | 校验起点与总长度 |
| 3 | 不校验每段实际字节数 | 短响应被当成"该段完成"，合并出中间有洞的文件 | 少一个字节即失败，多读部分丢弃 |
| 4 | 合并失败仍标记完成 | 得到"已完成"的残缺文件 | 合并前校验各段计数与 `.part` 长度，失败即 FAILED |
| 5 | 边下边播回写跨段多计数 | 提前满足"已下完"，空洞被合并 | 按本段剩余空间截断计数 |
| 6 | 边播收尾不校验 | 计数够了就标记完成 | 校验 `.part` 长度 == 总长度 |
| 7 | m3u8 取消时把半个分片记成已完成 | 续传拼出"半段 + 完整段" | 回滚到分片边界；恢复时截断 `.part` |
| 8 | AES-128 / fMP4 / ByteRange 的 HLS 直接拼接 | 下完也播不了，却显示完成 | 解析阶段识别并拒绝，给出原因 |
| 9 | 「刷新链接」只换 URL | 新链接续到旧 `.part`；类型/清晰度/total 沿用旧值 | 清 part/prog/state、结束旧流、重判类型与路径、重置进度 |
| 10 | 「重新下载」丢参数 | m3u8 丢清晰度与 Referer；`.torrent` 被当磁力直接失败 | 服务端按原始参数重建 |
| 11 | ticker 无条件回写 RUNNING | 已完成/暂停的任务被翻回"下载中" | 只回写 RUNNING/PROBING |
| 12 | 两个活动任务可共用同一落盘路径 | 互相清空 `.part`、覆盖对方文件 | 建任务时自动改名避让 |
| 13 | 合并时文件句柄未关 | Windows/部分文件系统上 `.part` 残留 | 先关句柄再结算 |
| 14 | 流服务器不跟踪连接 | stop 后播放器连接挂到超时 | stop 主动断开所有客户端 |
| 15 | 本地流服务对忽略 Range 的源站错位 | 边播数据整体偏移 | 200 响应把流位置归零后按需跳过 |
| 16 | BT 加入失败/元数据超时无回调 | 任务永久停在"准备就绪" | 明确回报失败原因 |
| 17 | BT 暂停后继续 = 重新 addMagnet | 已在会话中的种子等于没恢复 | 先 `resume()`，不在会话才重新添加 |
| 18 | BT 元数据到达后不改落盘路径 | 完成后"打开/投屏"指向占位路径 | 同步真实种子名与路径 |
| 19 | 浏览器「主页」只显示首页层 | WebView 盖在上面，点主页像没反应 | 同时隐藏所有标签的 WebView |
| 20 | 投屏成功后对话框关闭 | 「停止投屏」再也点不到 | 保留对话框并切换为停止态 |
| 21 | 文件重命名允许 `../x` | 文件被移出下载目录，DB 仍指向旧路径 | 拒绝路径分隔符，成功后同步 `save_path` |
| 22 | HLS 产物仍叫 `.m3u8`、无进度通知 | 播放器不认扩展名；通知栏无进度 | 输出 `.ts`；进度 1 秒节流进通知 |

测试：新增 `DownloadIntegrityTest`（4 例，本地裸 Socket 故障服务器，离线可跑）；
m3u8 不支持特性识别 4 例；全量 90/90。

---

## v2.5.1 — 边下边播数据错位

- `DownloadEngine` 写入改用线程私有 `myPos`：`cancel()` 后阻塞在 `read()` 的分段线程
  晚一步退出时，不会再按被边播推进过的共享计数写错位置。
- 影响：v2.5 里边看边下可能花屏/错乱，本地缓存也会被污染。
  **升级后建议把 v2.5 期间用边播下过的视频重新下载。**

---

## v2.5 — 稳定性与体验

| # | 问题 | 修复 |
| --- | --- | --- |
| 1 | 探测（HEAD/GET）在主线程执行 | 移入后台线程池，新增 `awaitReady()` |
| 2 | ticker / 引擎 / BT 回调直接刷新 RecyclerView | `notifyUi()` 统一 post 到主线程 |
| 3 | 任务通知 ID 与前台通知同为 1 | 任务通知改用 `1000 + id` |
| 4 | 任一分段失败后其余分段仍跑完并触发 `onFinished` | 失败即置位并终止其余分段 |
| 5 | 分段计数器边注册边启动线程 | 先登记全部分段计数再启动 |
| 6 | `reload()` 直接清空内存任务表 | 改为合并式加载 |
| 7 | m3u8 任务重启后不恢复 | 补 `resumeActiveAfterRestart` 分支；变体与 Referer 落库 |
| 8 | 外部 magnet / `.torrent` 链接被静默丢弃 | 暂存 Intent，服务连接后补处理 |
| 9 | 流服务器串行处理客户端 | 每客户端独立线程 |
| 10 | 边下边播每次拉取都新建连接 | `TaskBacking` 复用长连接按序续读 |
| 11 | Android 11+ 分区存储拦截公共目录写入 | `StorageRoots` 探测可写性并降级 |
| 12 | `Content-Disposition` 文件名从不生效 | 探测时优先采用并同步路径 |
| 13 | 重新下载不清进度文件 | 重建前清除 `.part` 与 `.swiftprog` |
| 14 | m3u8 暂停后继续从头下 | 记录已完成分片数，恢复时跳过 |
| 15 | 设置页改动没有保存时机 | 显式「保存」按钮（原实现靠点击卡片保存，输入框会吃掉点击） |
| 16 | 单任务限速不显示当前值 | 弹窗回显 + 按钮标记 |
| 17 | 排队原因被当失败原因红色显示 | 排队原因单独展示 |
| 18 | Activity 从不 `unbindService` | `onDestroy` 解绑并取消 scope |
| 19 | 文件页扫描深度不足 | 提至 4 层，覆盖降级目录 |
| 20 | 任务速度首帧恒为 0 B/s | 改用服务端 1 秒窗口采样 |

---

## v2.4 — 视频嗅探

- 浏览器拦截 m3u8/mp4 请求，解析 master playlist 列出清晰度（分辨率 + 码率），用户选定后下载。
- 请求携带来源页 `Referer`，绕过常见防盗链。
- 解析失败退化为原始地址下载（引擎自动选最高码率）。

## v2.3 — 下载控制与 m3u8

- 下载设置：全局限速、仅 Wi-Fi、时段调度（可跨零点）、文件自动分类、m3u8 开关、HTTP 代理。
- 传输页支持单任务限速；失败任务支持「刷新链接」原地替换。
- m3u8 下载：自动选最高码率变体，顺序下载分片后合并。
- 进程重启自动重建 RUNNING/PROBING 任务；共享状态改 `ConcurrentHashMap`；`.swiftprog` 原子写。

## v2.2 — 边下边播与投屏

- 边下边播：本地 HTTP 服务接管 HTTP 视频任务，已下读盘、缺失拉取并回写引擎。
- DLNA 投屏：SSDP 发现 MediaRenderer，SOAP `SetAVTransportURI` / `Play`，支持停止。
- 文件管理升级：排序（时间/名称/大小 × 升降）、类型图标、分享、重命名、长按菜单。
- UI 组件：涟漪、胶囊按钮、圆角图标。

## v2.1 — 书签

- 首页自定义网站：添加 / 编辑 / 置顶 / 删除；地址栏 ☆ 一键收藏。
- 「我的 → 书签管理」集中管理 + 恢复默认。

## v2.0 — 磁力、BT、浏览器

- 磁力聚合搜索：5 个规则源并发，infohash 去重合并，来源数 + 做种排序，失效源跳过，通用扫描兜底。
- BT 引擎：jlibtorrent 1.2.0.18，元数据到达回填任务名，并入统一通知体系。
- 内置多标签浏览器：地址栏、进度条、首页（搜索 + 磁力引擎入口）、链接与 `.torrent` 接管。
- 数据库 v2：新增 `kind` / `hash`，v1 任务原样保留。

## v1.0 — 基础下载器

- 多线程 HTTP、Range 探测、分段并发、64KB 缓冲、`.swiftprog` 断点续传、速度采样。
- 前台服务 + 双通知通道（进度 / 完成）。
- SQLite `tasks` 表与公共 `Download` 目录布局（后续版本保持兼容）。
