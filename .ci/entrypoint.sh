#!/bin/sh
# ============================================================================
# fcloud-ci-hub 容器入口（§11.2 / §11.3）
#
# 只用 Alpine 自带的 /bin/sh（ash），不依赖 bash / gettext / tzdata：
#   1. 目录与权限准备（/data/config-hub/{pg,storage,backups}）
#   2. PostgreSQL 首次 initdb 与建库建用户
#      （base 用 apk 装的 PG，没有官方镜像的 docker-entrypoint.sh）
#   3. 用 sed 渲染 nginx 配置（替代 envsubst）
#   4. 最后 exec 给 supervise.sh，由它负责进程守护
# ============================================================================
set -eu

DATA_ROOT="${FCLOUD_DATA_ROOT:-/data/config-hub}"
PGDATA="${DATA_ROOT}/pg"
STORAGE_DIR="${FCLOUD_STORAGE_ROOT:-${DATA_ROOT}/storage}"
BACKUP_DIR="${FCLOUD_BACKUP_DIR:-${DATA_ROOT}/backups}"

DB_NAME="${FCLOUD_DB_NAME:-fcloud_ci_hub}"
DB_USER="${FCLOUD_DB_USER:-fcloud}"
DB_PASSWORD="${FCLOUD_DB_PASSWORD:-fcloud}"
SERVER_PORT="${FCLOUD_SERVER_PORT:-8081}"

log() { echo "[entrypoint] $*"; }

# 库名/用户名会拼进 SQL 与 createdb 参数，只允许安全字符
case "${DB_USER}" in
    ''|*[!A-Za-z0-9_]*) log "FATAL: FCLOUD_DB_USER 只允许 [A-Za-z0-9_]"; exit 1 ;;
esac
case "${DB_NAME}" in
    ''|*[!A-Za-z0-9_]*) log "FATAL: FCLOUD_DB_NAME 只允许 [A-Za-z0-9_]"; exit 1 ;;
esac

mkdir -p "${DATA_ROOT}" "${STORAGE_DIR}" "${BACKUP_DIR}" \
         /var/log/supervisor /var/log/nginx /run/nginx \
         /var/run/postgresql /tmp

# ---------------------------------------------------------------------------
# 0. PG 可执行文件固定到 /usr/local/bin
#
#    Alpine 的版本化 PG 包（postgresql16/17/18）把二进制装在
#    /usr/libexec/postgresqlNN/，而不是 /usr/bin —— 直接 command -v postgres
#    会找不到。这里先把可能存在的目录都塞进 PATH，再统一软链到 /usr/local/bin，
#    supervise.sh 只认 /usr/local/bin 下的稳定名字。
# ---------------------------------------------------------------------------
for pg_dir in /usr/libexec/postgresql* /usr/lib/postgresql*/bin; do
    if [ -d "${pg_dir}" ]; then
        PATH="${pg_dir}:${PATH}"
    fi
done
export PATH

for bin in postgres initdb pg_isready psql createdb pg_ctl; do
    src="$(command -v "${bin}" 2>/dev/null || true)"
    if [ -n "${src}" ]; then
        ln -sf "${src}" "/usr/local/bin/${bin}"
    fi
done
if [ ! -x /usr/local/bin/postgres ]; then
    log "FATAL: postgres binary not found (looked in PATH, /usr/libexec/postgresql*, /usr/lib/postgresql*/bin)"
    exit 1
fi
log "postgres binaries: $(readlink -f /usr/local/bin/postgres 2>/dev/null || echo unknown)"

# ---------------------------------------------------------------------------
# 0.1 以 postgres 身份执行命令
#
#     只用 Alpine 自带的 busybox su：su postgres -s /bin/sh -c 'exec ...'
#     外面的 exec 会让目标进程直接替换掉中间 shell，pid 就是进程本身，
#     信号可以直达（supervise.sh 依赖这一点给 PG 发 INT）。
#     不依赖 su-exec，base 里装不装都能跑。
# ---------------------------------------------------------------------------
AS_PG_TMP=/tmp/fcloud-pg
mkdir -p "$AS_PG_TMP"

# $1 = 一整条命令字符串。路径由本脚本控制；SQL 一律走临时文件，
# 不把用户输入拼进 shell 命令，避免引号/注入问题。
run_as_pg() {
    su postgres -s /bin/sh -c "exec $1"
}

# ---------------------------------------------------------------------------
# 1. PG 数据目录准备（initdb 由本脚本执行）
#
#    - 空目录 → initdb（UTF8 / C locale / 本地 trust、TCP scram）
#    - 有 PG_VERSION → 直接启动
#    - 非空但没有 PG_VERSION → 报错退出（绝不删数据）
# ---------------------------------------------------------------------------
if [ ! -f "${PGDATA}/PG_VERSION" ]; then
    if [ -d "${PGDATA}" ] && [ -n "$(ls -A "${PGDATA}" 2>/dev/null)" ]; then
        log "FATAL: ${PGDATA} 非空但缺少 PG_VERSION，拒绝初始化以免误删数据"
        log "       确认是空目录后手动清空，或把旧的 PG_VERSION 放回去"
        exit 1
    fi
    log "empty PG data directory; running initdb (this happens once)"
    mkdir -p "${PGDATA}"
    chown postgres:postgres "${PGDATA}"
    chmod 700 "${PGDATA}"
    if ! run_as_pg "/usr/local/bin/initdb -D '${PGDATA}' -E UTF8 --locale=C --auth-local=trust --auth-host=scram-sha-256" \
            >/var/log/supervisor/initdb.log 2>&1; then
        log "FATAL: initdb failed, see /var/log/supervisor/initdb.log"
        tail -20 /var/log/supervisor/initdb.log 2>/dev/null || true
        exit 1
    fi
else
    log "existing PG data directory detected (PG_VERSION present)"
fi
chown -R postgres:postgres "${PGDATA}"
chmod 700 "${PGDATA}"
chown -R postgres:postgres /var/run/postgresql

# ---------------------------------------------------------------------------
# 2. 渲染 nginx 配置（sed 替代 envsubst，少装一个 gettext）
#
#    模板里只有这三个占位符需要替换，nginx 自己的 $host/$remote_addr 不受影响。
# ---------------------------------------------------------------------------
log "rendering nginx config (upstream port ${SERVER_PORT})"
GITLAB_HOST="${FCLOUD_GITLAB_LITE_HOST:-127.0.0.1}"
GITLAB_PORT="${FCLOUD_GITLAB_LITE_PORT:-8929}"
sed -e "s|\${FCLOUD_SERVER_PORT}|${SERVER_PORT}|g" \
    -e "s|\${FCLOUD_GITLAB_LITE_HOST}|${GITLAB_HOST}|g" \
    -e "s|\${FCLOUD_GITLAB_LITE_PORT}|${GITLAB_PORT}|g" \
    /etc/nginx/fcloud.conf.template > /etc/nginx/conf.d/fcloud.conf

# ---------------------------------------------------------------------------
# 3. 导出给子进程的环境（supervise.sh 与它拉起的进程都会继承）
# ---------------------------------------------------------------------------
export FCLOUD_DB_HOST=127.0.0.1
export FCLOUD_DB_PORT=5432
export FCLOUD_DB_NAME="${DB_NAME}"
export FCLOUD_DB_USER="${DB_USER}"
export FCLOUD_DB_PASSWORD="${DB_PASSWORD}"
export FCLOUD_SERVER_PORT="${SERVER_PORT}"
export FCLOUD_STORAGE_ROOT="${STORAGE_DIR}"
export FCLOUD_BACKUP_DIR="${BACKUP_DIR}"
export FCLOUD_MASTER_KEY_FILE="${DATA_ROOT}/master.key"

# 便于排障时核对实际生效的配置
{
  echo "# generated by entrypoint at $(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "FCLOUD_DB_HOST=${FCLOUD_DB_HOST}"
  echo "FCLOUD_DB_PORT=${FCLOUD_DB_PORT}"
  echo "FCLOUD_DB_NAME=${FCLOUD_DB_NAME}"
  echo "FCLOUD_DB_USER=${FCLOUD_DB_USER}"
  echo "FCLOUD_SERVER_PORT=${FCLOUD_SERVER_PORT}"
  echo "FCLOUD_STORAGE_ROOT=${FCLOUD_STORAGE_ROOT}"
  echo "FCLOUD_BACKUP_DIR=${FCLOUD_BACKUP_DIR}"
  echo "FCLOUD_MASTER_KEY_FILE=${FCLOUD_MASTER_KEY_FILE}"
} > "${DATA_ROOT}/runtime-env.txt" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 4. 后台等待 PG 就绪 → 建库建用户（幂等）
#
#    放在后台是因为 supervise.sh 还没启动 PG；它会在 60s 内就绪。
# ---------------------------------------------------------------------------
(
  log "background: waiting for PostgreSQL ..."
  i=0
  while [ "$i" -lt 120 ]; do
      if /usr/local/bin/pg_isready -h 127.0.0.1 -p 5432 -q 2>/dev/null; then
          log "background: PostgreSQL is ready"
          break
      fi
      i=$((i + 1))
      sleep 1
  done

  # 密码里的单引号按 SQL 规则转义，写进文件，不经过 shell 引号
  ESC_PW=$(printf '%s' "${DB_PASSWORD}" | sed "s/'/''/g")

  printf "SELECT 1 FROM pg_roles WHERE rolname='%s';\n" "${DB_USER}" \
      > "${AS_PG_TMP}/role-check.sql"
  if ! run_as_pg "/usr/local/bin/psql -h /var/run/postgresql -U postgres -tA -f ${AS_PG_TMP}/role-check.sql" \
        2>/dev/null | grep -q 1; then
      printf "CREATE ROLE %s LOGIN PASSWORD '%s';\n" "${DB_USER}" "${ESC_PW}" \
          > "${AS_PG_TMP}/role-create.sql"
      run_as_pg "/usr/local/bin/psql -h /var/run/postgresql -U postgres -f ${AS_PG_TMP}/role-create.sql" \
          >/dev/null 2>&1 || true
      log "background: role ${DB_USER} created"
  fi

  printf "SELECT 1 FROM pg_database WHERE datname='%s';\n" "${DB_NAME}" \
      > "${AS_PG_TMP}/db-check.sql"
  if ! run_as_pg "/usr/local/bin/psql -h /var/run/postgresql -U postgres -tA -f ${AS_PG_TMP}/db-check.sql" \
        2>/dev/null | grep -q 1; then
      run_as_pg "/usr/local/bin/createdb -h /var/run/postgresql -O ${DB_USER} ${DB_NAME}" \
          >/dev/null 2>&1 || true
      log "background: database ${DB_NAME} created"
  fi

  # PG15+ public schema 授权
  printf "GRANT ALL ON SCHEMA public TO %s;\n" "${DB_USER}" \
      > "${AS_PG_TMP}/grant.sql"
  run_as_pg "/usr/local/bin/psql -h /var/run/postgresql -U postgres -d ${DB_NAME} -f ${AS_PG_TMP}/grant.sql" \
      >/dev/null 2>&1 || true

  log "background: database bootstrap complete"
) > /var/log/supervisor/bootstrap.log 2>&1 &

log "preparation done; handing over to supervise.sh"
exec "$@"
