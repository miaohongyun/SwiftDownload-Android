# 极速下载 SwiftDownload v2.5

「极速下载」v2.5 —— **稳定性与体验修复版**。在 v2.4（视频嗅探 + 清晰度可选下载）基础上，完成一次覆盖全工程的缺陷排查与修复，核心是修掉 6 个会导致崩溃或功能不可用的真实缺陷，并补齐若干交互盲区。v1.0 核心行为仍保持兼容。

> v1.0 → v2.0 的迁移方式：逆向 v1.0 APK 提取行为规格（数据库 Schema / 通知通道 / 引擎语义 / UI 文案），逐项保真重建，再叠加新功能。回归对照详见 [docs/REGRESSION.md](docs/REGRESSION.md)。

## v2.5 修复清单

| # | 问题 | 影响 | 修复位置 |
| --- | --- | --- | --- |
| 1 | 探测（HEAD/GET）在**主线程**执行 | Android 9+ 抛 `NetworkOnMainThreadException`；且必然卡顿 UI 数秒 | `DownloadEngine` 探测移入后台线程池，新增 `awaitReady()` |
| 2 | ticker / 引擎 / BT 回调线程**直接刷新 RecyclerView** | 触发 `Called from the invalid thread` 崩溃 | `DownloadService.notifyUi()` 统一 post 到主线程 |
| 3 | 任务通知 ID 与前台通知 ID **同为 1** | 首个任务覆盖前台通知；完成时又把它 cancel 掉 | 任务通知改用 `1000 + id` 独立段位 |
| 4 | 任一分段失败后，**其余分段仍跑完**并触发 `onFinished` | 任务状态"先失败后完成"，且合并不完整文件 | 失败即置位并终止其余分段；`segDone` 前检查失败标志 |
| 5 | 分段计数器**边注册边启动线程** | 先完成的一段看到不完整的 `size`，被误判为"全部完成" | 先登记全部分段计数再启动线程 |
| 6 | 服务 `reload()` 直接 `clear()` 内存任务表 | 进度回退、文件名变回 URL 推断名 | 改为合并式加载，保留内存中的更新值 |
| 7 | `m3u8` 任务**重启后不恢复** | 进程被杀后 HLS 任务永远停在"下载中" | `resumeActiveAfterRestart` 补 m3u8 分支；清晰度与 Referer 落库持久化 |
| 8 | 外部 magnet / .torrent 链接**被静默丢弃** | `bindService` 是异步的，Intent 早于 binder 就绪 | 暂存 Intent，服务连接后补处理 |
| 9 | 流服务器**串行处理客户端** | 一个慢客户端堵住所有连接，播放器表现为"卡住" | 每个客户端独立线程 |
| 10 | 边下边播**每次拉取都新建 HTTP 连接** | 拖动进度时反复握手，播放严重卡顿 | `TaskBacking` 复用长连接按序续读 |
| 11 | Android 11+ **分区存储拦截公共目录写入** | 下载一开始即失败，报错晦涩 | `StorageRoots` 探测可写性，不可写时降级到应用专属目录 |
| 12 | `Content-Disposition` 文件名**从不生效** | 服务器真实文件名被忽略 | 探测时优先采用，并同步更新落盘路径 |
| 13 | 重新下载**不清进度文件** | 实际变成"继续下载" | 重建任务前彻底清除 `.part` 与 `.swiftprog` |
| 14 | m3u8 任务暂停后继续**从头下** | 长视频白下 | 记录已完成分片数，恢复时跳过 |
| 15 | 设置页改动**没有保存时机** | 输入框吞掉点击，"改了没反应" | 显式「保存」按钮 + 失焦自动保存 |
| 16 | 单任务限速**不显示当前值** | 用户看不到自己设过多少 | 弹窗回显 + 按钮标记「限速 5M」 |
| 17 | 排队原因被当作**失败原因**红色显示 | "下载失败：等待 Wi-Fi 连接" | 排队原因单独展示，仅失败态显示红色 |
| 18 | Activity **从不 unbindService** | 服务无法真正停止，前台通知长期驻留 | `onDestroy` 解绑并取消 scope |
| 19 | 文件页**扫描深度不足** | 开启文件分类后子目录文件看不到 | 扫描深度提至 4 层，并覆盖降级目录 |
| 20 | 任务速度**首帧恒为 0 B/s** | 页面自算差分所致 | 改用服务端 1 秒窗口采样 |

## 功能总览

| 模块 | 说明 |
| --- | --- |
| 多线程 HTTP 下载 | Range 探测 / 分段并发 / 64KB 缓冲 / `.swiftprog` 断点续传 / SpeedSampler 速度采样（v1.0 全保持） |
| 磁力搜索聚合 | 5 个规则源并发搜索（TorrentKitty / 动漫花园 / 磁力猫 / 柠檬BT / SkrBT），infohash 去重合并、来源数+做种排序、失效源自动跳过、通用磁力扫描兜底 |
| BT 引擎 | jlibtorrent 1.2.0.18（armeabi-v7a + arm64-v8a），元数据到达自动回填任务名，统一并入通知体系 |
| 内置浏览器 | 多标签 WebView / 地址栏（含☆收藏）/ 进度条 / 首页（搜索 + 网页/磁力引擎入口）/ 页面下载与磁力链接接管 |
| 书签（v2.1） | 首页自定义网站：添加 / 编辑 / 置顶 / 删除，地址栏☆一键收藏当前页，「我的 → 书签管理」集中管理 + 恢复默认 |
| 边下边播（v2.2） | HTTP 视频任务：本地 HTTP 服务器接管，已下字节读盘、缺字节拉取并回写引擎，播放器断开 8 秒后自动恢复多线程补齐 |
| DLNA 投屏（v2.2） | SSDP 发现局域网 MediaRenderer → SOAP SetAVTransportURI/Play；本地文件服务器支持 Range；设备选择器 + 停止投屏 |
| 文件管理（v2.2 升级） | 排序（时间/名称/大小 × 升降，记忆）/ 类型彩色图标 / 分享 / 重命名 / 长按菜单 |
| 主壳 | 底部导航四页（浏览器 / 传输 / 文件 / 我的），磁力 scheme 与 .torrent 文件接管 |
| 下载控制（v2.3） | 「我的 → 下载设置」：全局限速 / 仅 Wi-Fi / 时段调度（可跨零点）/ 文件自动分类 / m3u8 开关 / HTTP 代理；传输页可对单任务限速；失败任务的「刷新链接」原地替换失效直链 |
| m3u8 下载（v2.3） | 识别 `.m3u8` 直链 → 自动选最高码率变体 → 顺序下载分片合并为单文件；支持限速与暂停 |
| 视频嗅探（v2.4） | 内置浏览器拦截 m3u8/mp4 请求 → 解析 master playlist 列出清晰度（分辨率+码率）→ 用户选定后下载；请求携带来源页 Referer；解析失败自动退化为原始地址下载。不依赖任何平台私有接口 |
| 稳定性（v2.3） | 进程重启后自动重建 RUNNING/PROBING 任务；暂停/排队态可直接边下边播；共享状态改 ConcurrentHashMap；`.swiftprog` 原子写；磁力 URI 补齐 `xl`/`tr`；解析器可插拔 |
| 缺陷修复（v2.5） | 20 项，见上方清单 |

## 工程结构

```
SwiftDownload/
├── app/
│   ├── libs/jlibtorrent-1.2.0.18.jar          # Java 层（Maven Central）
│   ├── src/main/jniLibs/                      # arm64-v8a / armeabi-v7a 原生库
│   ├── src/main/java/com/swift/download/
│   │   ├── DownloadTask.kt                    # 任务模型（v1.0 字段 + kind/hash）
│   │   ├── DownloadEngine.kt                 # 多线程 HTTP 引擎（v1.0 移植）
│   │   ├── DownloadService.kt                 # 前台服务 + 双通知通道 + 轮询回写
│   │   ├── DownloadDatabase.kt               # SQLite v1→v3 自动迁移（kind/hash/variant/referer）
│   │   ├── StorageRoots.kt                    # v2.5 下载根目录解析（分区存储探测 + 降级）
│   │   ├── OpenFileReceiver.kt                # FileProvider 打开文件
│   │   ├── MediaTypes.kt                      # 媒体类型判定（边下边播/投屏共用）
│   │   ├── MainActivity.kt                    # 四页主壳 + intent 接管 + 投屏/边下边播接线
│   │   ├── Format.kt                          # 格式化（1024 进制）
│   │   ├── magnet/                            # 磁力搜索聚合（源/规则/聚合/UI）
│   │   ├── browser/                           # 内置浏览器（多标签）+ 书签管理
│   │   │   ├── BrowserPage.kt                # 多标签浏览器 + 首页书签卡片 + ☆收藏
│   │   │   └── BookmarkManagerPage.kt        # 我的→书签管理（列表/编辑/置顶/删除/恢复默认）
│   │   ├── player/                            # v2.2 边下边播（本地 HTTP 服务器 + StreamMath）
│   │   ├── dlna/                              # v2.2 DLNA 投屏（SSDP/SOAP/设备选择器）
│   │   ├── bt/TorrentEngine.kt                # jlibtorrent 封装
│   │   ├── files/                             # 文件页 + 排序（FileSorts）
│   │   ├── transfers/TransfersPage.kt         # 传输页（v1.0 主界面移植 + 批量操作/速度）
│   │   └── ui/Ui.kt                           # 代码构建 UI 助手（v2.2 + 涟漪/胶囊/图标）
│   └── src/test/                              # 48 个 JVM 单元测试
├── docs/REGRESSION.md                          # 回归测试报告
├── keystore/swiftdl.jks                        # 自签发布密钥
└── scripts/                                    # 构建/安装辅助脚本
```

## 构建

- 环境：JDK 17+ / Gradle 8.9 / AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 36 / minSdk 24
- 镜像：`settings.gradle.kts` 已配置阿里云 Maven 镜像；Gradle wrapper 使用腾讯镜像分发源
- 命令：`./gradlew assembleRelease`（产物：`app/build/outputs/apk/release/app-release.apk`）
- 签名：`keystore/swiftdl.jks`（store/key 密码 `swiftdl2026`，alias `swiftdl`）
- 测试：`./gradlew testDebugUnitTest`

## 单元测试

`app/src/test` 内 48 个 JVM 测试全部通过，覆盖：体积/速度/剩余时间格式化（1024 进制守护）、
Content-Disposition 文件名提取、hex/base32 infohash 归一化与非法 hash 拒绝、磁力链接解析、
TorrentKitty/动漫花园规则解析与通用兜底扫描、聚合去重/来源合并/排序、分段切割数学、SpeedSampler 窗口差分、
边下边播 Range 解析与已下载区段（12 用例）、DLNA 设备描述解析与 SOAP 报文（7 用例）、文件排序比较器（4 用例）、
引擎回写 externalWrite（2 用例）。
测试首轮抓出 2 个真实缺陷（`urn:btih:` 前缀解析停在 `urn`；大写 hash 未归一化导致聚合漏去重），已修复并由测试守护。

## 已知事项（真机确认）

1. 磁力源实时连通性随域名轮换波动：失效源自动跳过；可在「我的 → 磁力源管理」长按源卡片修改搜索地址（需含 `{kw}` 占位符）。
2. 冷门磁力资源 DHT 元数据等待约 1~3 分钟出文件名（引擎内做最长 180 秒的轮询等待）。
3. Android 13+ 首次启动弹通知权限；「极速下载运行中」常驻通知为 v1.0 原语义。
4. 边下边播：需 HTTP 直链视频且服务器返回 Content-Length；BT 任务不支持。播放器建议 VLC/MXPlayer（系统播放器部分机型对 127.0.0.1 兼容差）。拖动进度取决于视频封装（mp4 需 moov 前置）。
5. 投屏：手机与电视/盒子需同一 Wi-Fi；DLNA 兼容性因品牌而异（小米/乐视/TCL 常见兼容）；部分电视需在设置中开启 DLNA / 多屏互动。
