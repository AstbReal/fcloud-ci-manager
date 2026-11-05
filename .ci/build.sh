#!/usr/bin/env bash
# ============================================================================
# fcloud-ci-manager 镜像构建（§11.2 / §11.5）
#
# 两级镜像：
#   .ci/base.Dockerfile  → fcloud-ci-manager-base:latest
#        Node 20 / OpenJDK 17 / nginx / PostgreSQL / curl / su-exec
#   .ci/Dockerfile       → fcloud-ci-manager:latest
#        在 base 之上补 supervise.sh（sh 进程守护）与应用产物
#
# 用法：
#   bash .ci/build.sh base     # 只构建底座（首次需要，会拉 node:20-alpine）
#   bash .ci/build.sh [tag]    # 构建应用镜像（要求 base 已存在，不会自动拉取）
#   BASE_IMAGE=xxx bash .ci/build.sh
#
# 前端与后端产物在宿主机预构建（构建环境拉不到 node/gradle 基础镜像），
# 再由 .ci/Dockerfile 拷入运行时镜像。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."          # 仓库根

BASE_IMAGE="${BASE_IMAGE:-fcloud-ci-manager-base:latest}"

die() { echo "!! $*" >&2; exit 1; }

# Windows 上 Docker 在 WSL 里：优先用本机 docker，否则转发到 WSL 执行
docker_build() {
  local -a args=("$@")
  if command -v docker >/dev/null 2>&1; then
    docker "${args[@]}"
  elif command -v wsl >/dev/null 2>&1; then
    local repo_wsl
    repo_wsl="$(wsl -e wslpath -a "$(pwd)" 2>/dev/null || wslpath -a "$(pwd)")"
    wsl -e bash -lc "cd '${repo_wsl}' && docker ${args[*]}"
  else
    die "找不到 docker，也找不到 wsl"
  fi
}

docker_has_image() {
  if command -v docker >/dev/null 2>&1; then
    docker image inspect "$1" >/dev/null 2>&1
  else
    wsl -e bash -lc "docker image inspect '$1' >/dev/null 2>&1"
  fi
}

# ---------------- 只构建底座 ----------------
if [ "${1:-}" = "base" ]; then
  echo "==> 构建底座 ${BASE_IMAGE}（.ci/base.Dockerfile）"
  docker_build build -f .ci/base.Dockerfile -t "${BASE_IMAGE}" .ci
  echo "==> 完成：${BASE_IMAGE}"
  exit 0
fi

TAG="${1:-fcloud-ci-manager:latest}"

if ! command -v npm >/dev/null 2>&1; then
  echo "!! 未找到 npm：需要 Node 18+（自带 npm）" >&2
  exit 1
fi

echo "==> [1/4] 构建前端（npm）"
export CI=true
npm --prefix frontend ci --no-audit --no-fund
npm --prefix frontend run build

echo "==> [2/4] 收集前端产物到 .ci/frontend-dist"
rm -rf .ci/frontend-dist
mkdir -p .ci/frontend-dist
cp -r frontend/dist/. .ci/frontend-dist/

echo "==> [3/4] 构建后端 jar（Gradle 7.5.1）"
./backend/gradlew -p backend clean bootJar -x test

echo "==> [4/4] 构建应用镜像 ${TAG}"
if ! docker_has_image "${BASE_IMAGE}"; then
  die "底座镜像 ${BASE_IMAGE} 不存在。先执行：bash .ci/build.sh base
   （该步骤会拉取 node:20-alpine，之后可重复使用）"
fi
docker_build build \
  -f .ci/Dockerfile \
  --build-arg "BASE_IMAGE=${BASE_IMAGE}" \
  -t "${TAG}" .

echo "==> 完成：${TAG}"
if command -v docker >/dev/null 2>&1; then
  docker images "${TAG%%:*}" --format '{{.Repository}}:{{.Tag}}  {{.Size}}'
else
  wsl -e bash -lc "docker images '${TAG%%:*}' --format '{{.Repository}}:{{.Tag}}  {{.Size}}'"
fi
