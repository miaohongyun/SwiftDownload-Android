#!/usr/bin/env bash
# 极速下载 v2.0 一键构建（在工程根目录执行）
set -e
cd "$(dirname "$0")/.."
./gradlew --no-daemon testDebugUnitTest assembleRelease
echo
echo "产物: app/build/outputs/apk/release/app-release.apk"
ls -la app/build/outputs/apk/release/app-release.apk
