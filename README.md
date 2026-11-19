# 极速下载 SwiftDownload（Android）

多线程 HTTP 下载工具：断点续传、HLS 加密流、磁力搜索与 BT 选文件、浏览器嗅探、
边下边播、DLNA 投屏、局域网投递、文件哈希校验。Kotlin 编写，原生 jlibtorrent，
无第三方 UI / 网络框架。

## 当前版本

**v2.6.0**（versionCode 11），源码位于 `SwiftDownload-v2.5.3/` —— 目录名是历史遗留，
内部版本、文档与交付均以 v2.6.0 为准（该目录被本机进程占用，暂未改名）。

- 单元测试：**139/139 通过**
- Git Tag：`v2.5.3`（清理前基线）、`v2.6.0`（当前版本）

## 目录

| 路径 | 说明 |
| --- | --- |
| `SwiftDownload-v2.5.3/` | **当前源码工程**（实际版本 v2.6.0），含完整文档与 139 个单元测试 |
| `SwiftDownload-v2.6.0-Phase1..4.patch` | 四个阶段的独立补丁存档（仅本地，不入库） |
| `SwiftDownload-v2.6.0.bundle` | 提交的可移植 Git bundle（仅本地，不入库） |

旧工作副本与 v2.5.3 的源码 zip / APK 已从仓库清理；需要历史产物可检出 `v2.5.3` tag。

## 构建

见 [`SwiftDownload-v2.5.3/README.md`](SwiftDownload-v2.5.3/README.md)。
环境：JDK 17、Android SDK 36、Gradle 8.9（wrapper 已配置镜像）。

```bash
cd SwiftDownload-v2.5.3
./scripts/build.sh
```

## 文档入口

- [用户手册](SwiftDownload-v2.5.3/docs/USER_GUIDE.md)
- [架构说明](SwiftDownload-v2.5.3/docs/ARCHITECTURE.md)
- [变更记录](SwiftDownload-v2.5.3/docs/CHANGELOG.md)
- [回归报告](SwiftDownload-v2.5.3/docs/REGRESSION.md)

## 注意

Release 签名使用随仓库提供的 `keystore/swiftdl.jks`，口令写在 `app/build.gradle.kts` 中。
这是自用项目的历史设计，**请勿直接信任来源不明的同名安装包**。
