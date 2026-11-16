#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
operation="${1:-}"; shift || true
[[ "${1:-}" == --platform && $# -ge 2 ]] || { echo 'Usage: scripts/build.sh compile|package --platform android|ios|harmony|maven' >&2; exit 2; }
platform="$2"; shift 2
if [[ "$platform" == harmony && -n "${HARMONY_CLT_HOME:-}" ]]; then
  export DEVECO_SDK_HOME="$HARMONY_CLT_HOME/sdk"
  export DEVECO_NODE_HOME="$HARMONY_CLT_HOME/tool/node"
  export NODE_HOME="$DEVECO_NODE_HOME"
  export PATH="$NODE_HOME/bin:$HARMONY_CLT_HOME/ohpm/bin:$HARMONY_CLT_HOME/bin:$PATH"
fi
if [[ "$operation" == compile ]]; then
  [[ $# == 0 ]] || { echo 'Unexpected arguments' >&2; exit 2; }
  case "$platform" in
    android) tasks=(:routing-core:compileDebugKotlinAndroid :routing-compiler:compileKotlin) ;;
    ios) tasks=(:routing-core:compileKotlinIosArm64 :routing-core:compileKotlinIosSimulatorArm64 :routing-compiler:compileKotlin) ;;
    harmony) tasks=(:routing-core:compileKotlinOhosArm64 :routing-compiler:compileKotlin) ;;
    *) echo 'Unsupported compile platform' >&2; exit 2 ;;
  esac
  [[ ${#tasks[@]} -gt 0 ]] || { echo 'No production target on this platform' >&2; exit 2; }
  args=(--no-daemon --max-workers=1 -Dorg.gradle.parallel=false)
  [[ ! -f scripts/ci-repositories.gradle ]] || args+=(--init-script "$PWD/scripts/ci-repositories.gradle")
  bash gradlew "${args[@]}" "${tasks[@]}"
  exit 0
fi
[[ "$operation" == package ]] || { echo 'Unsupported operation' >&2; exit 2; }
if [[ "$platform" == maven ]]; then exec bash scripts/archive-prebuilt.sh "$@"; fi
echo 'No native HAR channel for this component' >&2; exit 2
