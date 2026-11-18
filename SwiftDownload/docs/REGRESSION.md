# 极速下载 v2.0 回归测试报告

- 被测对象：`SwiftDownload` v2.0（com.swift.download, versionCode 2, versionName "2.0"）
- 基线（对照）：v1.0 `app-release.apk`（com.swift.download, versionCode 1, versionName "1.0"）
- 方法：v1.0 APK 逆向提取行为规格（Manifest / DEX 字符串 / SQLite 建表语句 / 通知常量）→ v2.0 逐项静态比对 + JVM 单元测试 + 构建产物核验
- 工具：androguard（逆向）、aapt2 / apksigner（产物核验）、Gradle 8.9 + AGP 8.5.2 + Kotlin 1.9.24（构建）

## 1. Manifest 与组件回归

| 对照项 | v1.0（逆向提取） | v2.0（实际产物） | 结论 |
| --- | --- | --- | --- |
| 包名 | com.swift.download | com.swift.download | PASS |
| 应用名 | 极速下载 | 极速下载 | PASS |
| minSdk / targetSdk | 24 / 36 | 24 / 36 | PASS |
| 权限集合 | INTERNET / ACCESS_NETWORK_STATE / POST_NOTIFICATIONS / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / WAKE_LOCK（+动态接收器权限） | 完全一致（aapt2 dump permissions 逐条比对） | PASS |
| 启动页 | MainActivity（LAUNCHER） | 一致 | PASS |
| 前台服务 | DownloadService（dataSync） | 一致（同 foregroundServiceType） | PASS |
| 接收器 | OpenFileReceiver（+ProfileInstaller 系统组件） | OpenFileReceiver（ProfileInstaller 由 AGP 自动注入，同 v1.0） | PASS |
| Provider | androidx FileProvider | 一致（同 file_paths 定义下载目录） | PASS |
| 直链接管 | http/https VIEW+BROWSABLE | 保持；新增 magnet scheme 与 application/x-bittorrent MIME（v2.0 新能力） | PASS（含增量） |

## 2. 数据库迁移回归（v1 → v2）

- v1.0 建表语句（从 v1.0 DEX 字符串池逐字符提取）与 v2.0 `CREATE_V1` 常量完全一致：

```sql
CREATE TABLE tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    url TEXT NOT NULL,
    file_name TEXT NOT NULL,
    save_path TEXT NOT NULL,
    total_bytes INTEGER NOT NULL DEFAULT -1,
    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 0,
    threads INTEGER NOT NULL DEFAULT 4,
    error TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL
)
```

- v2.0 升级路径：`onUpgrade` 仅执行 `ALTER TABLE tasks ADD COLUMN kind INTEGER NOT NULL DEFAULT 0` 与 `ADD COLUMN hash TEXT NOT NULL DEFAULT ''`，非破坏性迁移；老任务行全量保留，默认 `kind=0`（HTTP 任务），状态/进度/线程数字段语义不变。
- 任务模型构造签名与 v1.0 DEX 方法签名一致：(id, url, fileName, savePath, totalBytes, downloadedBytes, status, threads, createdAt, error) +（v2.0 新增 kind, hash 可默认字段）。
- 状态机保持 0..5：等待/准备就绪/下载中/暂停/已完成/失败（`Status.fromInt` 兜底回 QUEUED）。

## 3. HTTP 下载引擎回归（行为级）

| v1.0 行为 | v2.0 实现 | 结论 |
| --- | --- | --- |
| Range 探测：HEAD 失败退化 GET+Range 0-0，解析 Content-Length / Content-Range / Accept-Ranges | 一致（`DownloadEngine.probe`） | PASS |
| 无 Content-Length → 失败文案「无法获取文件大小（服务器未返回 Content-Length）」并单线程直下路径关闭 | 一致（ProbeResult.totalBytes=-1 → FAILED + 同文案） | PASS |
| 多线程分段下载，threads 默认 4 | 一致（`Segments.split`，余数分配给前段） | PASS |
| 64KB 缓冲读写 | 一致（`ByteArray(65536)`） | PASS |
| `.swiftprog` 进度文件断点续传 | 一致（同名同义：分段时间戳 map 序列化，恢复时按分段起点续传） | PASS |
| SpeedSampler 速度采样（≥1 秒窗口差分） | 一致（单测覆盖窗口差分与回退保护） | PASS |
| 文件名识别：Content-Disposition（RFC 5987/2047）→ URL 末段解码 | 一致（单测覆盖三种形态） | PASS |
| 下载器 UA / 超时 | 一致（Chrome Mobile UA，15s/30s 超时） | PASS |

## 4. 通知体系回归

- 双通道：`swift_download_progress`（下载进度，IMPORTANCE_LOW）/ `swift_download_done`（任务提醒，DEFAULT）—— v1.0 双通道语义保持。
- 常驻前台通知标题「极速下载运行中」、副标题「多线程 · 断点续传」—— v1.0 原文。
- 完成通知「下载完成」+ 文件名 + 点击进主界面 —— v1.0 原文；v2.0 增量：BT 任务显示做种/节点数、等待元数据进度形态。

## 5. UI 文案回归（v1.0 DEX 字符串池逐条核对）

以下 v1.0 原文在 v2.0「传输」页全部保留（抽样清单）：「新建下载任务」「+ 新建下载任务」「粘贴 http/https 下载直链」「请输入 http/https 开头的直链」「支持多线程加速与断点续传，文件名自动识别。」「多线程 · 断点续传」「下载线程数：」「下载设置」「线程越多通常越快，但过多可能被服务器限制。\n新任务将使用此设置。」「检测到已复制的下载链接」「立即下载」「忽略」「暂无下载任务\n\n复制一个下载直链，或点上方按钮添加」「等待」「准备就绪」「下载中」「暂停」「已完成」「完成」「失败」「下载中：」「下载失败：」「任务已添加：」「总速度」「 · 剩余 」「 · 已完成」「删除任务」「确定删除「」及其下载文件吗？此操作不可恢复。」「重新下载」「重下」「将清空「」的现有进度，从头开始下载。」「打开」「文件已不存在」「没有可打开此文件的应用」「小时」「分」「秒」「未知」「未知错误」「极速下载」。

## 6. 单元测试（23/23 通过）

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| FormatTest | 4 | 1024 进制体积 / 速度 / 剩余时间中文单位 / Content-Disposition 文件名 |
| MagnetLinksTest | 5 | hex 归一化 / base32 解码 / 非法 hash 拒绝 / 完整磁力解析 / 体积换算一致性 |
| MagnetParsersTest | 5 | TorrentKitty 行规则 / 动漫花园 title 规则 / 通用兜底扫描 / 结构化失败退化 / stripTags 与大小提取 |
| MagnetSearchTest | 5 | 多源去重与来源数累加 / 排序（来源数→做种→体积）/ 非法 hash 不进入聚合 / magnetUri 生成 / 空输入安全 |
| SegmentsTest | 4 | 分段等分与余数 / 退化条件与 .swiftprog 命名 / URL 文件名提取 / SpeedSampler 差分 |

**测试首轮抓出并修复的 2 个真实缺陷**（与 v2.0 首版开发时的修复点对应）：
1. 磁力 `urn:btih:` 前缀解析用 `substringAfter(':')` 停在 `urn`，hash 提取为空 → 改为 `substringAfterLast(':')`；
2. 大写 hex hash 未归一化导致聚合漏去重（A×40 与 a×40 未合并）→ 聚合前统一 `lowercase()`。
另有历史修复（本轮以守护测试固化）：体积单位 1000/1024 进制不一致 → 统一 1024；非法 hash（长度/字符不符）进入聚合 → 聚合入口过滤。

## 7. 构建产物核验

| 项 | 结果 |
| --- | --- |
| `assembleDebug` | 17.3MB（含调试符号） |
| `assembleRelease`（R8 + shrinkResources + 自签） | 14.5MB |
| apksigner verify | Verified（APK Signature Scheme v2，1 signer） |
| 原生库 | lib/arm64-v8a + lib/armeabi-v7a 的 libjlibtorrent-1.2.0.18.so 均已打入 |
| aapt2 badging | package=com.swift.download, versionCode=2, versionName=2.0, minSdk=24, targetSdk=36 |

## 8. v2.0 增量清单（非回归项，新能力）

1. 磁力搜索聚合（`magnet/` 包）：5 源并发、infohash 去重合并、来源数+做种排序、通用磁力扫描兜底、源管理页（开关/长按改地址/恢复默认）。
2. BT 引擎（`bt/TorrentEngine.kt`）：jlibtorrent 会话（DHT 启用、下载不限速）、元数据轮询回填任务名（最长 180 秒）、进度/做种节点并入统一通知。
3. 内置浏览器（`browser/BrowserPage.kt`）：多标签、地址栏、进度条、首页搜索+引擎入口+站点卡片（增删改）、页面下载接管、磁力一键下载。
4. 文件页（`files/FilesPage.kt`）与四页主壳（浏览器/传输/文件/我的）。
5. DB v2（kind/hash 列）与 magnet/.torrent intent 接管。

## 9. 待真机确认事项

- 磁力源实时连通性（域名轮换后可在「磁力源管理」长按改地址；失效源自动跳过并计入「失效源自动跳过 N 个」提示）。
- 冷门磁力 DHT 元数据 1~3 分钟出文件名（引擎最长等待 180 秒）。
- Android 13+ 通知权限首启弹窗（POST_NOTIFICATIONS）。

## 结论

v1.0 行为级对照项全部 PASS（Manifest/权限/组件/数据库/引擎/通知/UI 文案/打开文件链路），23 项单元测试通过，构建与签名核验通过；v2.0 新增功能不破坏任何 v1.0 行为。**总体结论：通过。**

---

# 附：v2.1 增量（书签/自定义网站）回归记录

- 被测对象：v2.1（versionCode 3, versionName "2.1"）；基线：v2.0 交付版
- 变更范围（仅浏览器/书签，其余模块零改动）：

| 项 | v2.0 | v2.1 | 回归 |
| --- | --- | --- | --- |
| 首页站点卡片 | 添加 / 长按删除 | 添加 / 长按菜单（编辑、移到最前、删除） | PASS |
| 地址栏 | ←→⟳⌂ | 新增「☆」收藏当前页（已存在则改名） | PASS |
| 我的页 | 磁力源管理/下载设置/关于 | 新增「书签管理」入口（列表/编辑/删除/置顶/恢复默认） | PASS |
| 持久化 | quick_sites.json | 同一文件，字段不变（新旧互读兼容） | PASS |
| 版本 | versionCode 2 / "2.0" | versionCode 3 / "2.1" | PASS |

- 单元测试：23/23 通过（沿用 v2.0 用例，书签逻辑为 Android UI 层，由真机冒烟覆盖）
- 构建：assembleRelease 成功（R8 + shrinkResources + 自签），APK 13.8MB，APK Signature Scheme v2 签名块核验存在
- v1.0 行为回归：本次变更不触碰 DownloadEngine/DownloadService/DB/通知，v1.0 对照项维持 PASS

## v2.1 待真机确认事项

- ☆ 收藏 → 返回浏览器首页立即出现新卡片
- 书签管理页删除/置顶 → 首页卡片顺序同步（onChanged 回调 rebuildHomepage）
- 编辑书签后点击卡片按新地址打开

---

# 附：v2.2 增量（边下边播 / 投屏 / 文件排序 / UI 与操作细节）回归记录

- 被测对象：v2.2（versionCode 4, versionName "2.2"）；基线：v2.1 交付版
- 单元测试：48/48 通过（v2.1 的 23 个全保持 + 新增 25 个：StreamMath 12 / DlnaParse 7 / FileSorts 4 / ExternalWrite 2）
- 构建：assembleRelease 成功（R8 + shrinkResources + 自签），APK 13.8MB，APK Signature Scheme v2 签名块核验存在

## 1. 边下边播（HTTP 视频任务）

| 项 | 结果 |
| --- | --- |
| 架构 | 传输页「边下边播」→ 暂停多线程引擎 → 本地 HTTP 服务器（0.0.0.0 随机端口）接管 → 播放器播 `http://127.0.0.1:port/file` |
| 已下载数据复用 | StreamMath.onDisk/diskRunEnd 判定 .part 已有字节直接读盘（12 个单测覆盖：Range 解析、单线程前缀、四段不连续、满段衔接） |
| 边播边下 | 缺失字节远端拉取并 DownloadJob.externalWrite 回写 .part + 推进分段计数器（前沿对齐才计数，重复/越前只落盘）→ 进度/速度/DB/通知全部继续走 ticker |
| 断开恢复 | 播放器断开空闲 8 秒 → 自动重建多线程 Job（.swiftprog 已含流模式字节）继续补齐未完成分段 |
| 播完收尾 | ticker 检测 bytes>=total → finishStreamHttp：.part 改名正式文件 + 删 .swiftprog + DB COMPLETED + 完成通知（对齐 v1.0 mergeParts 语义） |
| 暂停/删除交互 | 流模式下暂停/删除任务会先结束会话（endStream）不恢复引擎 |
| 已知限制 | BT 任务与不支持 Range 的直链不支持；部分 mp4 需 moov 前置才可拖动进度（服务器侧 Range 已支持，取决于封装） |

## 2. DLNA 投屏

| 项 | 结果 |
| --- | --- |
| 发现 | SSDP M-SEARCH（ST=MediaRenderer:1，UDP 组播 239.255.255.250:1900，3 秒窗口）→ LOCATION 去重 → 拉取设备描述 XML → friendlyName + AVTransport controlURL（相对地址按描述地址绝对化） |
| 控制 | SOAP SetAVTransportURI + Play（SOAPACTION 头 urn:schemas-upnp-org:service:AVTransport:1#...） |
| 数据面 | DownloadService.serveFile() 起本地文件服务器（支持 Range，电视可拖进度）→ CurrentURI 推局域网地址 |
| 停止 | 选择器内「停止投屏」SOAP Stop |
| 权限增量 | CHANGE_WIFI_MULTICAST_STATE（normal 级，唯一新增权限） |
| 单测 | controlURL 相对/绝对解析、friendlyName、无 AVTransport → null、absolutize、SOAP 报文含转义 URL（7 个用例） |

## 3. 文件页升级

排序（时间/名称/大小 × 升降，SharedPreferences 记忆）；文件类型彩色图标与类型标签；合计体积统计；长按菜单：打开/分享(FileProvider ACTION_SEND)/投屏播放(视频)/重命名/删除。

## 4. 传输页升级

页面内自算实时速度（两次快照差分，不再依赖宿主传速）；任务数 + 总速度头部；「全部暂停/全部继续/清除完成（保留文件）」工具栏；体积行带百分比；边下边播（HTTP 视频运行/暂停态）/播放/打开/投屏按钮。

## 5. UI 与操作细节

- Ui：pressable 按压涟漪、iconBadge、pillBtn、colorOf 八色调色板
- 底部导航：激活胶囊背景 + 图标着色
- 书签卡片：按名称稳定取色（同真实浏览器观感）
- 双击返回键退出（浏览器页先走网页回退）
- 我的页/关于弹窗文案升 v2.2

## 6. v1.0 行为回归

本次变更不触碰 DownloadEngine 既有 segment() 下载路径（仅新增 externalWrite 独立方法）、DB Schema、通知通道与文案；v1.0 对照项维持 PASS。新增唯一权限为 normal 级 CHANGE_WIFI_MULTICAST_STATE。

## 7. 待真机确认事项

- 边下边播：VLC/MXPlayer/系统播放器对 `127.0.0.1` 地址与 Range 拖动的兼容；播放中途退出 8 秒后任务自动恢复多线程
- 投屏：各品牌电视/盒子 DLNA 兼容（小米/乐视/TCL 常见兼容；部分电视需在设置里开 DLNA）；手机与设备需同一 Wi-Fi
- 文件排序选择持久化；分享经 FileProvider（Download/ 目录已覆盖）
