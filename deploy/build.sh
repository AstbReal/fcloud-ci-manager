#!/usr/bin/env bash
# ============================================================================
# 构建单镜像（§11.2 / §11.5）
#
# 前端与后端产物在宿主机预构建（因为构建环境无法拉取 node/gradle 基础镜像），
# 然后由 deploy/Dockerfile 拷入运行时镜像。
#
# 用法：bash deploy/build.sh [镜像标签]
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-fcloud-ci-hub:latest}"
NODE_BIN='C:/Users/zzesu/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/node/bin'
PNPM='C:/Users/zzesu/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/pnpm/bin/pnpm.mjs'

echo "==> [1/4] 构建前端"
if [ -x "${NODE_BIN}/node" ] || [ -x "${NODE_BIN}/node.exe" ]; then
  NODE="${NODE_BIN}/node"
  [ -x "${NODE_BIN}/node.exe" ] && NODE="${NODE_BIN}/node.exe"
  "$NODE" "$PNPM" --dir frontend install --prefer-offline
  "$NODE" "$PNPM" --dir frontend run build
else
  (cd frontend && npm install && npm run build)
fi

echo "==> [2/4] 收集前端产物到 deploy/frontend-dist"
rm -rf deploy/frontend-dist
mkdir -p deploy/frontend-dist
cp -r frontend/dist/. deploy/frontend-dist/

echo "==> [3/4] 构建后端 jar（Gradle 7.5.1）"
./backend/gradlew -p backend clean bootJar -x test

echo "==> [4/4] 构建 Docker 镜像 ${TAG}"
REPO_WSL="$(wsl -e wslpath -a "$(pwd)" 2>/dev/null || wslpath -a "$(pwd)")"
wsl -e bash -lc "cd '${REPO_WSL}' && docker build -f deploy/Dockerfile -t ${TAG} ."

echo "==> 完成：${TAG}"
wsl -e bash -lc "docker images ${TAG%%:*} --format '{{.Repository}}:{{.Tag}}  {{.Size}}'"
