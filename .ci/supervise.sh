#!/bin/sh
# ============================================================================
# 极简进程监管（替代 supervisor，Alpine 自带 /bin/sh 即可运行，无需 apk）
#
# 负责：
#   1. 按顺序拉起 PostgreSQL → nginx → Spring Boot
#   2. 任一进程退出后自动重启（2 秒间隔），日志落 /var/log/supervisor/*.log
#   3. 收到 TERM/INT 时先停 app，再停 nginx，最后给 PG 发 INT 做快速关闭
#
# 与 supervisor 的差异：没有 Web UI / supervisorctl；状态看日志与 pid 文件。
# 用法：/app/supervise.sh（作为容器的 PID 1）
# ============================================================================
set -u

LOG_DIR=/var/log/supervisor
RUN_DIR=/var/run
FLAG="${RUN_DIR}/fcloud-shutting-down"
RESTART_DELAY=2

mkdir -p "$LOG_DIR" "$RUN_DIR" /run/nginx /var/run/postgresql
rm -f "$FLAG" "${RUN_DIR}"/fcloud-*.pid

log() { echo "[supervise] $*"; }

# 在独立子 shell 里守护一个进程；用文件标记跨进程传递退出信号
start_service() {
    name=$1
    shift
    (
        while [ ! -f "$FLAG" ]; do
            "$@" >>"${LOG_DIR}/${name}.log" 2>&1 &
            child=$!
            echo "$child" >"${RUN_DIR}/fcloud-${name}.pid"
            wait "$child"
            code=$?
            rm -f "${RUN_DIR}/fcloud-${name}.pid"
            [ -f "$FLAG" ] && break
            echo "[supervise] ${name} exited (code=${code}), restarting in ${RESTART_DELAY}s" \
                >>"${LOG_DIR}/${name}.log"
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
start_service postgresql /usr/local/bin/postgres \
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
