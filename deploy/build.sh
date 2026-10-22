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

# pnpm 版本由 frontend/package.json 的 packageManager 字段锁定，corepack 按它取版本；
# 不依赖全局安装，也不会退回 npm 绕开 pnpm-lock。
pnpm_run() {
  if command -v pnpm >/dev/null 2>&1; then
    pnpm "$@"
  elif command -v corepack >/dev/null 2>&1; then
    # --pm-on-fail=ignore：没执行过 corepack enable 时，pnpm 会因 packageManager
    # 版本校验拒绝运行；这里显式放行（corepack 仍会按 packageManager 取对应版本）
    corepack pnpm --pm-on-fail=ignore "$@"
  else
    echo "!! 未找到 pnpm / corepack：需要 Node 18+，并执行 corepack enable pnpm" >&2
    exit 1
  fi
}

echo "==> [1/4] 构建前端（pnpm）"
# 非交互环境下 pnpm 清理 node_modules 会要求 TTY 确认，CI=true 让它直接执行
export CI=true
pnpm_run --dir frontend install --frozen-lockfile --config.confirmModulesPurge=false
pnpm_run --dir frontend run build

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
