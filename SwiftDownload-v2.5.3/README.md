# 极速下载 SwiftDownload

Android 下载工具：多线程 HTTP、断点续传、内置浏览器与视频嗅探、m3u8 下载、磁力聚合搜索、BT、
边下边播、DLNA 投屏、文件管理。Kotlin + 原生 jlibtorrent，无第三方 UI / 网络框架。

当前版本：**v2.5.3**（versionCode 10）。下载行为与 v2.5.2 一致，本版为代码清理与文档完善。

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
| 多线程 HTTP | Range 探测 / 分段并发 / 64KB 缓冲 / `.swiftprog` 断点续传 / 1 秒窗口速度采样 |
| 完整性验收 | 校验 206、`Content-Range`、每段字节数、合并长度；不合格一律 FAILED，不产出"假完成"文件 |
| 内置浏览器 | 多标签 WebView / 地址栏收藏 / 首页（搜索 + 书签 + 磁力引擎）/ 页面下载与磁力链接接管 |
| 视频嗅探 | 拦截 m3u8/mp4 请求 → 解析清晰度 → 选定下载；携带来源页 Referer；失败退化为原地址 |
| m3u8 / HLS | 未加密 TS 流：选最高码率、分片续传、合并为 `.ts`；AES / fMP4 / ByteRange 明确拒绝 |
| 磁力搜索 | 5 个规则源并发、infohash 去重合并、来源数 + 做种排序、失效源跳过、通用扫描兜底 |
| BT | jlibtorrent 1.2.0.18（arm64-v8a / armeabi-v7a），元数据回填、暂停恢复、删除停止做种 |
| 边下边播 | 本地 HTTP 服务接管 HTTP 视频任务；已下读盘、缺失拉取并回写；播放器断开 8 秒后恢复多线程 |
| DLNA 投屏 | SSDP 发现 MediaRenderer → SOAP 播放/停止；本地文件服务支持 Range |
| 文件管理 | 时间/名称/大小排序（记忆）、类型图标、打开、分享、重命名、删除 |
| 下载控制 | 全局限速、仅 Wi-Fi、时段调度（跨零点）、自动分类、m3u8 开关、HTTP 代理、单任务限速 |
| 任务操作 | 暂停/继续、重新下载（保留原始参数）、刷新链接（清旧代次）、删除（含/不含文件）、批量操作 |

## 工程结构

```
SwiftDownload/
├── app/
│   ├── libs/jlibtorrent-1.2.0.18.jar          # Java 层
│   ├── src/main/jniLibs/                      # arm64-v8a / armeabi-v7a 原生库
│   ├── src/main/java/com/swift/download/
│   │   ├── DownloadTask.kt                    # 任务模型 + 状态枚举
│   │   ├── DownloadEngine.kt                  # 多线程 HTTP 引擎（纯 JVM 可测）
│   │   ├── DownloadService.kt                 # 前台服务：任务表 / 调度 / 通知 / 流会话
│   │   ├── DownloadDatabase.kt                # SQLite v1→v3 迁移
│   │   ├── StorageRoots.kt                    # 下载根目录探测与降级
│   │   ├── MainActivity.kt                    # 四页主壳 + Intent 接管 + 投屏/边播接线
│   │   ├── MediaTypes.kt / Format.kt          # 媒体类型判定 / 格式化
│   │   ├── OpenFileReceiver.kt                # FileProvider 打开文件
│   │   ├── browser/                           # 多标签浏览器 + 书签管理
│   │   ├── transfers/                         # 传输页
│   │   ├── files/                             # 文件页 + 排序
│   │   ├── magnet/                            # 磁力源 / 解析器 / 聚合搜索 / 源管理
│   │   ├── player/                            # 边下边播服务 + m3u8 + 嗅探
│   │   ├── dlna/                              # SSDP + SOAP + 设备选择器
│   │   ├── bt/TorrentEngine.kt                # jlibtorrent 封装
│   │   ├── settings/                          # 设置 / 网络与时段规则 / 限速器
│   │   └── ui/                                # 代码构建 UI 助手
│   └── src/test/                              # 90 个 JVM 单元测试
├── docs/                                      # 用户手册 / 架构 / 变更 / 回归
├── keystore/swiftdl.jks                       # 自签发布密钥（自用）
└── scripts/build.sh                           # 测试 + Release 一键构建
```

## 构建

环境：JDK 17、Gradle 8.9、AGP 8.5.2、Kotlin 1.9.24、compileSdk 36、minSdk 24。

```bash
# 全部测试 + Release APK
./scripts/build.sh

# 或分步
./gradlew testDebugUnitTest
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- `settings.gradle.kts` 已配置阿里云 Maven 镜像，wrapper 使用腾讯镜像分发源。
- 签名：`keystore/swiftdl.jks`，alias `swiftdl`。**私钥与口令随源码存放，仅适合自用**；
  对外发布前必须轮换并改用环境变量/本机凭据。

## 测试

```bash
./gradlew testDebugUnitTest
```

当前 **90 个 JVM 用例，90/90 通过**。覆盖：

- 格式化（1024 进制）、Content-Disposition 文件名提取、存储根目录探测；
- infohash 归一化（hex/base32）、磁力链接解析、各源规则解析、聚合去重与排序；
- 分段切割数学、SpeedSampler 窗口差分、`externalWrite` 回写；
- 边下边播 Range 解析与已下载区段、DLNA 描述解析与 SOAP 报文、文件排序；
- m3u8 解析与不支持特性识别（AES / MAP / BYTERANGE）；
- **下载完整性（4 例）**：本地裸 `ServerSocket` 故障服务器，覆盖正常分段、忽略 Range、
  短响应、错误 `Content-Range`；后三种必须失败且不产出正式文件。

`RealEngineE2eTest`（真实下载 + SHA-1 比对 + 中断续传）与 `RealWorldProbeTest`（真实 HLS 形态）
依赖外网，不可达时由 `Assume` 自动跳过。

> 单测必须保持 `testOptions.unitTests.isReturnDefaultValues = true`。否则 `android.util.Log`
> 会抛 "not mocked"，把 `catch` 里的错误回调整个吞掉，故障路径在测试中表现为"无响应"。

## 设计要点：为什么不会再"假完成"

v2.5.2 之前，异常服务器响应可能让不完整或错位的文件被标成"已完成"。现在的硬约束：

1. 请求过分段的任务，响应必须是 206；200 直接失败（服务器忽略了 Range）。
2. `Content-Range` 的起点与总长度必须与请求/探测一致。
3. 每段必须收到恰好 `end - start + 1` 字节；少一个即失败，多读的丢弃。
4. 写偏移用线程私有 `myPos`；边播回写只能推进到本段边界。
5. 合并前校验各段计数与 `.part` 长度；合并前先关闭文件句柄。
6. HLS 只接受未加密 TS；其余特性在开始前拒绝，不产出不可播放的"完成"文件。

细节与线程模型见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 已知事项与限制

1. **HLS**：只支持未加密的标准 TS 分片；AES-128、fMP4（`EXT-X-MAP`）、`EXT-X-BYTERANGE` 会明确拒绝。
2. **BT**：不能选择种子内单个文件；冷门资源元数据等待约 1–3 分钟，超时会明确失败。
3. **边下边播**：仅 HTTP 直链且服务器返回 `Content-Length`；BT 不支持；mp4 的 moov 在文件末尾时拖动无效。
4. **投屏**：需同一 Wi-Fi，DLNA 兼容性因品牌而异，部分电视需手动开启"多屏互动"。
5. **存储**：Android 11+ 无法写公共 `Download` 时，自动降级到
   `Android/data/com.swift.download/files/Download`。
6. **磁力源**：域名轮换会导致个别源失效，搜索会自动跳过；可在「我的 → 磁力源管理」改地址。
7. **平台视频**：需要登录/动态签名的会员内容不在能力范围内。
8. **架构债**：签名与明文口令、`minSdk 24` 的 `startForegroundService`、仅 ARM 原生库、
   本地流服务绑定 `0.0.0.0` 无鉴权、无 CI —— 均为自用场景下的有意取舍，详见架构文档第 11 节。

## 版本

| 版本 | 主题 |
| --- | --- |
| v2.5.3 | 代码清理 + 文档完善（下载行为同 2.5.2） |
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
