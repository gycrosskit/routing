#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass an immutable release candidate version}"
[[ "$VERSION" =~ ^[0-9][0-9A-Za-z._-]*$ ]] || { echo 'Invalid release version' >&2; exit 1; }
# 保留旧产物和失败证据，由统一入口提供空任务目录。
[[ ! -e build/maven && ! -e build/prebuilt/routing-maven.tar.gz ]] || { echo 'Use a fresh task directory: staging or archive already exists' >&2; exit 1; }
bash gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false publishAllPublicationsToStagingRepository
python3 scripts/jitpack-metadata.py build/maven/com/github/gycrosskit/routing
python3 scripts/check-maven.py build/maven com.github.gycrosskit.routing "$VERSION" routing-core,routing-compiler ios_arm64,ios_simulator_arm64,ios_x64,ohos_arm64 routing-core,routing-core-android,routing-core-jvm,routing-core-iosarm64,routing-core-iossimulatorarm64,routing-core-iosx64,routing-core-ohosarm64,routing-compiler --max-jvm-major 65
mkdir -p build/prebuilt
COPYFILE_DISABLE=1 tar --no-xattrs -czf build/prebuilt/routing-maven.tar.gz -C build/maven com/github/gycrosskit/routing
(
    cd build/prebuilt
    shasum -a 256 routing-maven.tar.gz > routing-maven.tar.gz.sha256
    cat routing-maven.tar.gz.sha256
)
