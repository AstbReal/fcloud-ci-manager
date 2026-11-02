#!/bin/sh
# ============================================================================
# 极简进程监管（替代 supervisor，Alpine 自带 /bin/sh 即可运行，无需 apk）
#
# 负责：
#   1. 按顺序拉起 PostgreSQL → nginx → Spring Boot
#   2. 任一进程退出后自动重启（2 秒间隔）
#   3. 子进程直连容器 stdout/stderr，`docker logs` / `kubectl logs` 可见全部输出
#   4. 收到 TERM/INT 时先停 app，再停 nginx，最后给 PG 发 INT 做快速关闭
#
# 与 supervisor 的差异：没有 Web UI / supervisorctl；状态看 stdout 与 pid 文件。
# 用法：/app/supervise.sh（作为容器的 PID 1）
#
# ⚠️ 不要给子进程加 `>>文件 2>&1`：一旦 fd1/fd2 指向文件，该进程就与容器的
#    日志管道彻底断开，`docker logs` 只会剩下本脚本自己打的 [supervise] 行。
#    日志落盘与轮转交给 docker 的 json-file driver（compose 已配 20m × 3）。
# ============================================================================
set -u

# LOG_DIR 只给 entrypoint 的 initdb.log 用，运行期日志不再落文件
LOG_DIR=/var/log/supervisor
RUN_DIR=/var/run
FLAG="${RUN_DIR}/fcloud-shutting-down"
RESTART_DELAY=2

mkdir -p "$LOG_DIR" "$RUN_DIR" /run/nginx /var/run/postgresql
rm -f "$FLAG" "${RUN_DIR}"/fcloud-*.pid

log() { echo "[supervise] $*"; }

# 在独立子 shell 里守护一个进程；用文件标记跨进程传递退出信号
#
# 子进程的 fd1/fd2 直接继承本脚本（即容器的 stdout/stderr），不重定向到文件：
# 这是 `docker logs` 能看到 PG / nginx / JVM 输出的前提。
start_service() {
    name=$1
    shift
    (
        while [ ! -f "$FLAG" ]; do
            "$@" &
            child=$!
            echo "$child" >"${RUN_DIR}/fcloud-${name}.pid"
            log "${name} started (pid=${child})"
            wait "$child"
            code=$?
            rm -f "${RUN_DIR}/fcloud-${name}.pid"
            [ -f "$FLAG" ] && break
            log "${name} exited (code=${code}), restarting in ${RESTART_DELAY}s"
            sleep "$RESTART_DELAY"
        done
    ) &
}

stop_child() {
    name=$1
    sig=${2:-TERM}
    pidfile="${RUN_DIR}/fcloud-${name}.pid"
    if [ -f "$pidfile" ]; then
        pid=$(cat "$pidfile" 2>/dev/null || true)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill -"$sig" "$pid" 2>/dev/null || true
        fi
    fi
}

shutdown() {
    [ -f "$FLAG" ] && return
    touch "$FLAG"
    log "shutting down: app → nginx → postgresql"
    stop_child app TERM
    stop_child nginx TERM
    sleep 2
    # PG 用 INT 触发 fast shutdown，等它自己收拾干净
    stop_child postgresql INT
    sleep 3
    for name in app nginx postgresql; do
        pidfile="${RUN_DIR}/fcloud-${name}.pid"
        [ -f "$pidfile" ] && stop_child "$name" KILL
    done
    rm -f "${RUN_DIR}"/fcloud-*.pid
    log "bye"
    exit 0
}

trap shutdown TERM INT

# ---------------------------------------------------------------------------
# 1. PostgreSQL
# ---------------------------------------------------------------------------
log "starting postgresql"
# PG 拒绝以 root 运行，用 su-exec 切到 postgres；su-exec 会 exec 成 postgres，
# pid 文件记录的就是它本身，INT/TERM 能直达。
start_service postgresql su-exec postgres /usr/local/bin/postgres \
    -D /data/config-hub/pg \
    -c listen_addresses=127.0.0.1 \
    -c port=5432 \
    -c unix_socket_directories=/var/run/postgresql

i=0
while [ "$i" -lt 60 ]; do
    if /usr/local/bin/pg_isready -h 127.0.0.1 -p 5432 -q 2>/dev/null; then
        log "postgresql is ready"
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "$i" -ge 60 ]; then
    log "WARN: postgresql not ready after 60s, starting the rest anyway"
fi

# ---------------------------------------------------------------------------
# 2. nginx（配置由 entrypoint 预先渲染）
# ---------------------------------------------------------------------------
log "starting nginx"
start_service nginx /usr/sbin/nginx -g "daemon off;"

# ---------------------------------------------------------------------------
# 3. Spring Boot
# ---------------------------------------------------------------------------
log "starting app"
start_service app /usr/lib/jvm/java-17-openjdk/bin/java \
    -Duser.timezone=Asia/Shanghai \
    -XX:MaxRAMPercentage=70 \
    -jar /app/app.jar

# 等任一子进程树结束（正常情况下只有收到信号才会走到这里）
wait
log "all services stopped"
