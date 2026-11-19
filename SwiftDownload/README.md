# 极速下载 SwiftDownload

Android 下载工具：多线程 HTTP、断点续传、内置浏览器与视频嗅探、HLS 加密流、磁力聚合搜索、
BT 选文件、边下边播、DLNA 投屏、局域网投递、文件哈希校验。Kotlin + 原生 jlibtorrent，
无第三方 UI / 网络框架。

当前版本：**v2.9.0**（versionCode 14）。共 **209 个 JVM 单元测试，209/209 通过**。

## 文档

| 文档 | 内容 |
| --- | --- |
| [用户手册](docs/USER_GUIDE.md) | 安装、权限、每个功能怎么用、错误提示含义、常见问题、已知限制 |
| [架构说明](docs/ARCHITECTURE.md) | 模块划分、线程模型、任务状态机、数据完整性约束、数据库与存储布局、架构债 |
| [变更记录](docs/CHANGELOG.md) | 各版本行为变化与修复明细 |
| [回归报告](docs/REGRESSION.md) | 每个版本的实测过程、失败用例根因、修复验证 |

## 功能总览

| 模块 | 说明 |
| --- | --- |
| 多线程 HTTP | Range 探测 / 分段并发 / 慢连接 Work Stealing 接管 / `.swiftprog` 带区间断点 / 1 秒速度采样 |
| 完整性验收 | 校验 206、`Content-Range`、每段字节数、合并长度；不合格一律 FAILED，不产出"假完成"文件 |
| 网络健壮性 | 统一 HttpUtil（301/302/303/307/308 跨协议、最多 5 跳、Range 每跳重放）、2xx 防呆、未知长度流式下载 |
| 内置浏览器 | 单手底部工具栏（后退/前进/刷新/收藏/标签/抓资源）+ 极简首页 / 代理走 WebView / SSL 询问 + 错误引导页 / 广告拦截（网络 + DOM 注入）/ 一键抓本页磁力·种子·电驴 / 桌面-手机 UA 切换 / 书签 / 嗅探角标（N）/ 剪贴板智能识别 |
| 视频嗅探 | mp4/m3u8/mkv/flv/mov/webm；解析清晰度；来源页 Referer + Cookie；多视频批量下载 / 多链接批量建任务 |
| m3u8 / HLS | AES-128 解密（CBC/PKCS5Padding）、EXT-X-BYTERANGE、fMP4（EXT-X-MAP）init 段拼接、TS 无重编码封装 MP4；4 线程分片 + 3 次重试、按序拼接回滚；SAMPLE-AES/DRM 明确拒绝 |
| 磁力搜索 | 内置 + 自定义源并发、infohash 去重合并、来源数 + 做种排序；源管理支持新增/删除/连通性测试，搜索状态栏展开每源命中/403/超时明细；空壳/拦截源离屏 WebView 等 JS 渲染兜底 |
| BT | jlibtorrent 1.2.0.18，代理/SOCKS5 + 自动停种策略 + 上传限速 + 公开 Tracker；元数据回填、选文件下载（IGNORE 优先级）、顺序下载开关、`setPieceDeadline` 按需边下边播与就绪预览 |
| 边下边播 | 本地 HTTP 服务接管；已下读盘、缺失拉取并回写；HEAD + 并发 Range 安全；播放器断开 8 秒后恢复多线程；BT piece 预读调度 |
| DLNA 投屏 | SSDP 发现 → DIDL-Lite 元数据 → SOAP 播放/停止；WakeLock/WiFiLock 保活；MediaStore 文件零拷贝投屏 |
| 下载韧性 | 刷新链接保留断点（大小一致+Range 校验）、网络瞬断指数退避重试（3s→10s→30s，0–5 次）、空间前置校验、任务详情弹窗（原始/最终 URL 可复制） |
| 下载控制 | 全局限速、仅 Wi-Fi、时段调度、并发队列（1–5，默认 3）、规则实时联动、自动分类、单任务限速、状态筛选（全部/进行中/完成/失败） |
| 文件 | 完成后经 MediaStore 导出公共 `Download/极速下载/`；MD5/SHA-1/SHA-256 校验；ZIP 安全解压（防 Zip Slip）、文本/日志/字幕内置查看、排序/分享/重命名 |
| 备份生态 | 「我的 → 备份与恢复」一键导出/导入 JSON：书签、磁力源、广告规则、全部下载设置（带版本校验、合并/覆盖） |
| 局域网投递 | 「我的 → 局域网传链接」开启内嵌 Web 控制台：粘贴链接、上传 .torrent、实时查看任务列表 |
| UI 适配 | targetSdk 36 Edge-to-Edge 安全边距、锁定浅色主题、动态数字等宽（tnum）、浏览器标签 × 关闭与平滑滚动 |

## 工程结构

```
SwiftDownload/
├── app/
│   ├── libs/jlibtorrent-1.2.0.18.jar          # Java 层
│   ├── src/main/jniLibs/                      # arm64-v8a / armeabi-v7a 原生库
│   ├── src/main/java/com/swift/download/
│   │   ├── DownloadTask.kt                    # 任务模型（含 cookie/userAgent/exportUri）
│   │   ├── DownloadEngine.kt                  # 多线程 HTTP 引擎 + Work Stealing（纯 JVM 可测）
│   │   ├── DownloadService.kt                 # 前台服务：调度 / 通知 / 流会话 / 导出 / 远程面板
│   │   ├── DownloadDatabase.kt                # SQLite v1→v5 增量迁移（仅 ALTER TABLE）
│   │   ├── HttpUtil.kt                        # 统一连接打开器（重定向 / UA / Cookie）
│   │   ├── FileHash.kt / HashDialog.kt        # 哈希计算与校验弹窗
│   │   ├── MediaStoreExport.kt                # 公共 Download 导出
│   │   ├── QueueRules.kt / BtTaskRules.kt     # 队列与任务状态纯逻辑
│   │   ├── browser/                           # 多标签浏览器 + AdBlocker + 嗅探结果
│   │   ├── transfers/                         # 传输页（BT 选文件 / 状态筛选 / 任务详情）
│   │   ├── files/                             # 文件页 + 排序 + ZIP 解压 + 文本查看
│   │   ├── magnet/                            # 磁力源 / 解析器 / 聚合搜索
│   │   ├── player/                            # StreamServer / HLS(fMP4+TS 封装) / 嗅探 / AES
│   │   ├── dlna/                              # SSDP + DIDL-Lite SOAP + 设备选择器
│   │   ├── bt/                                # TorrentEngine + 代理/停种/Tracker + piece 调度
│   │   ├── remote/                            # 局域网 Web 投递面板
│   │   ├── settings/                          # 设置 / 规则 / 重试 / 备份编解码与恢复
│   │   └── ui/                                # 代码构建 UI 助手
│   └── src/test/                              # 209 个 JVM 单元测试
├── docs/                                      # 用户手册 / 架构 / 变更 / 回归
├── keystore/swiftdl.jks                       # 自签发布密钥（自用）
└── scripts/build.sh                           # 测试 + Release 一键构建
```

## 构建

环境：JDK 17、Gradle 8.9、AGP 8.5.2、Kotlin 1.9.24、compileSdk 36、minSdk 26（v2.9.0 起）。

```bash
# 全部测试 + Release APK
./scripts/build.sh

# 或分步
./gradlew testDebugUnitTest
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- `settings.gradle.kts` 已配置阿里云 Maven 镜像，wrapper 使用腾讯镜像分发源。
- v2.9.0 的 `com.frostwire:jlibtorrent-android:2.1.2.1` 来自 FrostWire 官方仓库，
  AAR 实体托管在 GitHub Release；国内网络不可达时可通过镜像预取到
  `~/.m2/repository/com/frostwire/jlibtorrent-android/2.1.2.1/`（构建已优先读取 `mavenLocal()`）。
- 签名：`keystore/swiftdl.jks`，alias `swiftdl`。**私钥与口令随源码存放，仅适合自用**；
  对外发布前必须轮换并改用环境变量/本机凭据。

## 测试

```bash
./gradlew testDebugUnitTest
```

当前 **209 个 JVM 用例，209/209 通过**（v2.5.3 的 90 → v2.6.0 的 139 → v2.7.0 的 184 → v2.8.0 的 200 → v2.9.0 的 209）。覆盖：

- 下载完整性：正常分段 / 忽略 Range / 短响应 / 错误 Content-Range / 404 错误页 / Chunked 无长度 / Cookie 校验；
- 网络：跨端口 302 重定向、finalUrl 分段复用；
- 并发安全：StreamServer HEAD + 双连接并发 Range、TaskBacking 并发 fetch、Work Stealing 慢段接管；
- 断点恢复：动态切分后的三列 `.swiftprog` 还原、旧两列格式兼容、刷新链接保留断点判定；
- 韧性：瞬断/5xx 可恢复分类、硬错误不可恢复、退避序列、空间阈值边界与文案；
- HLS：AES-128 加解密往返、BYTERANGE 解析、fMP4 `EXT-X-MAP` 解析、多 Key 轮换、端到端加密流逐字节校验、缓存目录清理、封装格式决策；
- BT：文件优先级、最大视频选择、就绪判定、listener 防重、代理生效/停种策略/上传限速/Tracker 追加、piece 覆盖与预读；
- 浏览器：AdBlocker 域名/URL/自定义规则匹配、scheme 白名单、多链接去重提取与分类、DOM 批量磁力/种子/电驴/hash 提取；
- 磁力：自定义源地址校验与纯 Kotlin JSON 往返、异常→诊断状态分类、离屏兜底结果合并去重；
- 文件工具：Zip Slip 路径穿越防护与边界、文本类型判定；
- 备份：JSON 序列化与往返还原、版本校验、文件名格式；
- 系统适配：DB v5 增量列、队列上限、导出路径、投屏 DIDL 元数据、VPN 网卡过滤、局域网协议与端口复用、状态筛选计数；
- 哈希：MD5/SHA-1/SHA-256 标准向量与比对归一化。

`RealEngineE2eTest`（真实下载 + SHA-1 比对 + 中断续传）与 `RealWorldProbeTest`（真实 HLS 形态）
依赖外网，不可达时由 `Assume` 自动跳过。

> 单测必须保持 `testOptions.unitTests.isReturnDefaultValues = true`，否则 `android.util.Log`
> 会抛 "not mocked"，把 `catch` 里的错误回调整个吞掉，故障路径在测试中表现为"无响应"。

## 设计要点：为什么不会再"假完成"

v2.5.2 之前，异常服务器响应可能让不完整或错位的文件被标成"已完成"。现在的硬约束：

1. 请求过分段的任务，响应必须是 206；200 直接失败（服务器忽略了 Range）。
2. `Content-Range` 的起点与总长度必须与请求/探测一致。
3. 每段必须收到恰好 `end - start + 1` 字节；少一个即失败，多读的丢弃。
4. 写偏移用线程私有 `myPos`；边播回写只能推进到本段边界。
5. 合并前校验各段计数与 `.part` 长度；合并前先关闭文件句柄。
6. HLS 分片按序落盘，单片失败回滚到分片边界；fMP4 / SAMPLE-AES 在开始前拒绝。
7. Work Stealing 切分在锁内完成"收缩终点 + 登记新段"；`.swiftprog` 持久化三列（起点/终点/字节），
   恢复时按区间还原而非静态重算，保证动态断点不丢。

细节与线程模型见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 已知事项与限制

1. **HLS**：支持 AES-128、ByteRange 与 fMP4（`EXT-X-MAP` + init 段拼接，TS 可无重编码封装为 MP4）；`SAMPLE-AES` 等 DRM 仍然拒绝。
2. **未知长度**：无 `Content-Length` 时单线程流式下载，不支持断点续传。
3. **边下边播**：仅 HTTP 直链且服务器返回 `Content-Length`；BT 不支持；mp4 的 moov 在文件末尾时拖动无效。
4. **投屏**：需同一 Wi-Fi，DLNA 兼容性因品牌而异；部分电视需手动开启"多屏互动"。
5. **存储**：Android 11+ 无法写公共 `Download` 时自动降级到应用专属目录，完成后可导出到公共目录。
6. **局域网面板**：默认关闭、无鉴权，仅建议在可信 Wi-Fi 下临时开启，用完即关。
7. **磁力源**：域名轮换会导致个别源失效，搜索会自动跳过；可在「我的 → 磁力源管理」改地址。
8. **平台视频**：需要登录/动态签名的会员内容不在能力范围内。
9. **架构债**：签名与明文口令、`minSdk 24` 兼容分支、仅 ARM 原生库、无 CI —— 自用场景下的有意取舍。

## 版本

| 版本 | 主题 |
| --- | --- |
| v2.8.0 | 真实用性重构：BT 会话死锁修复+DHT穿透+诊断透出、浏览器单手工具栏+极简首页+代理/SSL修复、广告 DOM 注入、磁力源管理+离屏兜底+一键抓资源 |
| v2.9.0 | 升级 jlibtorrent 2.1.2.1 修复 Android 11+ BT 不可达；浏览器重做为拇指单手布局（无顶部标签条/六按钮，底部五键）；磁力源清理 + 影视站直搜（免代理） |
| v2.7.0 | 五阶段体验升级：刷新保留断点/自动重试/空间校验/任务详情、BT 代理停种 Tracker+按需边播+fMP4/TS 封装、浏览器净化+细节+批量建任务、Edge-to-Edge+筛选+ZIP/文本工具+备份 |
| v2.6.0 | 并发/网络/引擎/交互四阶段升级：Work Stealing、HLS AES、Cookie、MediaStore、局域网投递 |
| v2.5.3 | 代码清理 + 文档完善 |
| v2.5.2 | 数据完整性：完整性验收、HLS 拒绝不支持特性、换链接/重下彻底重置 |
| v2.5.1 | 边下边播数据错位修复 |
| v2.5 | 稳定性：主线程网络、跨线程刷新、分段失败竞态等 20 项 |
| v2.4 | 视频嗅探 + 清晰度选择 |
| v2.3 | m3u8 下载、限速/时段/代理/分类 |
| v2.2 | 边下边播、DLNA 投屏、文件管理升级 |
| v2.1 | 书签 |
| v2.0 | 磁力搜索、BT、内置浏览器 |
| v1.0 | 多线程 HTTP 下载基础 |

完整明细见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。
