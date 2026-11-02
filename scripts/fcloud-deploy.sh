#!/usr/bin/env bash
#
# 从 fcloud-ci-hub 拉取模块文件，逐模块执行 invoke 任务。
#
# 必填环境变量：
#   FCLOUD_BASE     https://hub.example.com        不带尾斜杠
#   FCLOUD_TOKEN    chub_xxxxxxxx                  流水线 Token
#   FCLOUD_APP      pay                            应用 code
#   FCLOUD_ENV      prod                           环境 code
#   FCLOUD_MODULES  db-config,common-notify        模块 code，逗号分隔
#
# 可选：
#   TASK       deploy     invoke 任务名
#   STAGE      ./.fcloud-stage
#   KEEP       0          1 = 失败时也保留暂存目录
#   FAIL_FAST  1          0 = 单模块失败后继续跑后面的模块
#   CHMOD_SH   1          1 = 解包后给 *.sh 补可执行位（后端落盘是 0644）
#
# 退出码：0 全部成功；非 0 有模块失败。
#
set -euo pipefail

log() { printf '[fcloud] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

for v in FCLOUD_BASE FCLOUD_TOKEN FCLOUD_APP FCLOUD_ENV FCLOUD_MODULES; do
  [ -n "${!v:-}" ] || die "缺少环境变量 $v"
done

FCLOUD_BASE="${FCLOUD_BASE%/}"
API="$FCLOUD_BASE/api/v1/pipeline"
TASK="${TASK:-deploy}"
STAGE="${STAGE:-./.fcloud-stage}"
KEEP="${KEEP:-0}"
FAIL_FAST="${FAIL_FAST:-1}"
CHMOD_SH="${CHMOD_SH:-1}"

command -v curl >/dev/null || die "需要 curl"
command -v tar  >/dev/null || die "需要 tar"
command -v inv  >/dev/null || die "需要 invoke：pip install 'invoke>=2.2,<3'"

MODS=()
IFS=',' read -r -a _raw <<< "$FCLOUD_MODULES"
for m in "${_raw[@]}"; do
  m="$(printf '%s' "$m" | tr -d '[:space:]')"
  [ -n "$m" ] && MODS+=("$m")
done
[ ${#MODS[@]} -gt 0 ] || die "FCLOUD_MODULES 为空"

# ---------------- 1. 预检：Token 可用 + 第一个模块已绑定该环境 ----------------
log "预检 $FCLOUD_APP / $FCLOUD_ENV ..."
PRE_BODY="$(mktemp)"
PRE_CODE="$(curl -sS -o "$PRE_BODY" -w '%{http_code}' -G \
  -H "Authorization: Bearer $FCLOUD_TOKEN" \
  --data-urlencode "application=$FCLOUD_APP" \
  --data-urlencode "environment=$FCLOUD_ENV" \
  --data-urlencode "modules=${MODS[0]}" \
  "$API/config")" || die "无法连接 $FCLOUD_BASE"

if [ "$PRE_CODE" != "200" ]; then
  log "预检返回 HTTP $PRE_CODE：$(cat "$PRE_BODY")"
  rm -f "$PRE_BODY"
  case "$PRE_CODE" in
    401) die "Token 缺失 / 无效 / 过期 / 已吊销" ;;
    403) die "模块不属于该应用，或该应用未订阅此共享模块" ;;
    404) die "应用或环境不存在（检查 FCLOUD_APP / FCLOUD_ENV）" ;;
    429) die "触发限流（每 Token 每分钟 60 次），稍后重试" ;;
    *)   die "预检失败：模块可能未绑定到该环境" ;;
  esac
fi
rm -f "$PRE_BODY"
log "预检通过"

# ---------------- 2. 下载 artifacts.tar.gz ----------------
rm -rf "$STAGE"
mkdir -p "$STAGE"
TAR="$STAGE/artifacts.tar.gz"

MODS_JSON=""
for m in "${MODS[@]}"; do
  MODS_JSON="${MODS_JSON}${MODS_JSON:+,}\"$m\""
done
BODY_JSON="$(printf '{"application":"%s","environment":"%s","modules":[%s]}' \
  "$FCLOUD_APP" "$FCLOUD_ENV" "$MODS_JSON")"

log "下载 ${#MODS[@]} 个模块的文件 ..."
CODE="$(curl -sS -o "$TAR" -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $FCLOUD_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$BODY_JSON" \
  "$API/artifacts")" || die "下载失败：无法连接 $FCLOUD_BASE"

if [ "$CODE" != "200" ]; then
  log "下载返回 HTTP $CODE：$(cat "$TAR")"
  case "$CODE" in
    400) die "参数错误：modules 必填，或模块未绑定该环境" ;;
    403) die "模块不属于该应用" ;;
    404) die "未找到匹配的文件：这些模块在该环境下一个文件都没有" ;;
    429) die "触发限流，稍后重试" ;;
    *)   die "下载失败" ;;
  esac
fi
log "已下载 $(du -h "$TAR" | cut -f1)"

# ---------------- 3. 校验包结构 ----------------
LIST="$STAGE/.members"
tar -tzf "$TAR" > "$LIST"
[ -s "$LIST" ] || die "tar 包是空的"

# 第二道闸：fileKey 理论上不该含 ..，仍然拒绝解包
if grep -qE '(^|/)\.\.(/|$)' "$LIST"; then
  die "tar 包内含路径穿越条目，已拒绝解包"
fi

for m in "${MODS[@]}"; do
  if grep -q "^$m/" "$LIST"; then
    log "  $m: $(grep -c "^$m/" "$LIST" || true) 个文件"
  else
    log "  警告：$m 在该环境下没有任何文件"
  fi
done

# ---------------- 4. 解包 ----------------
tar -xzf "$TAR" -C "$STAGE" --no-same-owner
if [ "$CHMOD_SH" = "1" ]; then
  find "$STAGE" -type f -name '*.sh' -exec chmod +x {} + 2>/dev/null || true
fi

# ---------------- 5. 逐模块执行 invoke ----------------
FAIL=0
for m in "${MODS[@]}"; do
  DIR="$STAGE/$m"
  if [ ! -d "$DIR" ]; then
    log "跳过 $m：没有下载到文件"
    continue
  fi
  if [ ! -f "$DIR/tasks.py" ]; then
    log "跳过 $m：目录下没有 tasks.py（现有：$(ls -A "$DIR" 2>/dev/null | tr '\n' ' '))"
    continue
  fi

  log "== $m → inv $TASK"
  if ! ( cd "$DIR" && inv --list >/dev/null 2>&1 ); then
    log "ERROR $m：tasks.py 加载失败，单独调试：cd $DIR && inv --list"
    FAIL=1
    [ "$FAIL_FAST" = "1" ] && break
    continue
  fi
  if ! ( cd "$DIR" && inv "$TASK" ); then
    log "ERROR $m：任务 $TASK 执行失败"
    FAIL=1
    [ "$FAIL_FAST" = "1" ] && break
    continue
  fi
done

# ---------------- 6. 部署留痕 ----------------
if [ -d "$STAGE" ]; then
  ( cd "$STAGE" \
    && find . -type f ! -name 'artifacts.tar.gz' ! -name '.members' ! -name '.deployed.sha256' -print0 \
       | sort -z | xargs -0 -r sha256sum ) > "$STAGE/.deployed.sha256" || true
  if [ -s "$STAGE/.deployed.sha256" ]; then
    log "部署指纹写入 $STAGE/.deployed.sha256（$(wc -l < "$STAGE/.deployed.sha256") 个文件）"
  fi
fi

# ---------------- 7. 清理 ----------------
if [ "$FAIL" -eq 0 ]; then
  [ "$KEEP" = "1" ] && log "保留暂存目录：$STAGE" || rm -rf "$STAGE"
  log "全部完成"
  exit 0
fi

log "有模块执行失败，暂存目录保留在 $STAGE"
exit 1
