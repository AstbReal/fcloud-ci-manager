# ============================================================================
# fcloud-ci-hub 应用镜像（§11.2）
#
# 运行时底座是 .ci/base.Dockerfile 产出的 base 镜像，已包含：
#   Node 20（base 自带）/ OpenJDK 17 / nginx / PostgreSQL / curl / su-exec
#
# 本层不额外 apk 安装任何东西：
#   - 进程守护用 .ci/supervise.sh（Alpine 自带 /bin/sh）
#   - 缓存用进程内 LocalCache（不装 redis）
#   - entrypoint 用 POSIX sh，nginx 配置用 sed 渲染（不装 bash / gettext）
#   - 时区不装 tzdata：容器跑 UTC，JVM 用 -Duser.timezone=Asia/Shanghai
#
# 构建步骤（详见 .ci/build.sh）：
#   bash .ci/build.sh base   # 首次；会拉 node:20-alpine
#   bash .ci/build.sh        # 应用镜像，复用上面的 base 层
#
# 与旧版 deploy/Dockerfile 的关键差别：
#   1. 底座改为 node:20-alpine + apk 的 PostgreSQL，没有官方 docker-entrypoint.sh，
#      initdb 与建库建用户由 .ci/entrypoint.sh 自己完成；
#   2. PG 可执行文件路径在运行时由 entrypoint 链接到 /usr/local/bin，
#      不写死 /usr/bin 或 /usr/lib/postgresqlNN/bin；
#   3. 无 supervisor / redis / bash / gettext / tzdata 五个 apk 依赖。
# ============================================================================

ARG BASE_IMAGE=fcloud-ci-hub-base:latest
FROM ${BASE_IMAGE}

ENV TZ=UTC \
    LANG=C.UTF-8 \
    FCLOUD_DB_NAME=fcloud_ci_hub \
    FCLOUD_DB_USER=fcloud \
    FCLOUD_DB_PASSWORD=fcloud \
    FCLOUD_SERVER_PORT=8081 \
    FCLOUD_STORAGE_ROOT=/data/config-hub/storage \
    FCLOUD_BACKUP_DIR=/data/config-hub/backups \
    FCLOUD_BACKUP_RETAIN=3 \
    FCLOUD_AUDIT_RETAIN_DAYS=7 \
    FCLOUD_ACCESS_LOG_RETAIN_DAYS=30 \
    FCLOUD_RATE_LIMIT_PER_MINUTE=60 \
    FCLOUD_COOKIE_SECURE=false \
    FCLOUD_MASTER_KEY_FILE=/data/config-hub/master.key

RUN (id postgres >/dev/null 2>&1 \
        || adduser -S -D -H -s /bin/sh postgres) \
    && mkdir -p /var/log/supervisor /var/log/nginx /run/nginx \
                /etc/nginx/conf.d /data/config-hub/pg \
                /data/config-hub/storage /data/config-hub/backups \
    && rm -f /etc/nginx/http.d/default.conf

# ---- 应用 ----
WORKDIR /app
COPY backend/build/libs/fcloud-ci-hub-1.0.0.jar /app/app.jar
COPY .ci/frontend-dist /app/static

# ---- 配置与脚本 ----
COPY .ci/nginx.conf.template /etc/nginx/fcloud.conf.template
COPY .ci/nginx-main.conf /etc/nginx/nginx.conf
COPY .ci/entrypoint.sh /app/entrypoint.sh
COPY .ci/supervise.sh /app/supervise.sh
RUN chmod +x /app/entrypoint.sh /app/supervise.sh \
    && chown -R postgres:postgres /data/config-hub

EXPOSE 8080
VOLUME ["/data/config-hub"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["/app/supervise.sh"]
