# 极速下载 SwiftDownload（Android）

多线程 HTTP 下载工具：断点续传、m3u8/HLS、磁力搜索与 BT、边下边播、DLNA 投屏、文件管理。
Kotlin 编写，原生 jlibtorrent，无第三方 UI / 网络框架。

## 目录

| 路径 | 说明 |
| --- | --- |
| `SwiftDownload-v2.5.3/` | 当前源码工程（versionCode 10 / 2.5.3），含完整文档与 90 个单元测试 |
| `SwiftDownload/` | 早期工作副本（实际为 v2.5） |
| `SwiftDownload-v2.5.3-源码.zip` | v2.5.3 源码打包 |
| `极速下载-v2.5.3-release.apk` | v2.5.3 发布安装包 |
| `极速下载-v2.5.3-使用指南.md` | 桌面导出的用户手册 |

## 构建

见 [`SwiftDownload-v2.5.3/README.md`](SwiftDownload-v2.5.3/README.md)。
环境：JDK 17、Android SDK 36、Gradle 8.9（wrapper 已配置镜像）。

```bash
cd SwiftDownload-v2.5.3
./scripts/build.sh
```

## 注意

Release 签名使用随仓库提供的 `keystore/swiftdl.jks`，口令写在 `app/build.gradle.kts` 中。
这是自用项目的历史设计，**请勿直接信任来源不明的同名安装包**。
