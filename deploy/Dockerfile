# ============================================================================
# fcloud-ci-hub 单镜像（§11.2）
#
# 组成：PostgreSQL 16 + Redis 7 + OpenJDK 17 + nginx + supervisord
#
# 基础镜像说明（本机网络受限，Docker Hub 无法拉取 ubuntu/gradle/node）：
#   - 以 postgres:16-alpine 作为运行时底座（已自带 PostgreSQL 16）
#   - Redis 7 二进制从 redis:7.2-alpine 复制（版本与 §1 锁定一致）
#   - OpenJDK 17 / nginx / supervisord 由 apk 安装
#   - 前端与后端产物在宿主机预构建后拷入（见 deploy/build.sh）
#
# 该取舍已在 README「决策记录 D15」中说明：
#   §1.3 要求 Ubuntu 22.04 + PGDG 源；在无法访问 Docker Hub 的构建环境下，
#   改用 Alpine + 官方 postgres:16 镜像，组件版本（PG16 / Redis7 / JDK17）不变。
# ============================================================================

# ---------- 阶段 1：提取 Redis 7 二进制 ----------
FROM redis:7.2-alpine AS redis7

# ---------- 阶段 2：运行时 ----------
FROM postgres:16-alpine

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
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

# OpenJDK 17 + nginx + supervisor + 工具
RUN apk add --no-cache \
        openjdk17-jre-headless \
        nginx \
        supervisor \
        bash \
        curl \
        tzdata \
        gettext \
        unzip \
        su-exec \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && mkdir -p /var/log/supervisor /var/log/nginx /run/nginx \
                /etc/nginx/conf.d /etc/supervisor/conf.d \
    && rm -f /etc/nginx/http.d/default.conf 2>/dev/null || true

# Redis 7 二进制（版本锁定 §1.1：Redis 7）
COPY --from=redis7 /usr/local/bin/redis-server /usr/local/bin/redis-server
COPY --from=redis7 /usr/local/bin/redis-cli /usr/local/bin/redis-cli
RUN chmod +x /usr/local/bin/redis-server /usr/local/bin/redis-cli \
    && /usr/local/bin/redis-server --version

# ---- 应用 ----
WORKDIR /app
COPY build/libs/fcloud-ci-hub-1.0.0.jar /app/app.jar
COPY deploy/frontend-dist /app/static

# ---- 配置与脚本 ----
COPY deploy/supervisord-main.conf /etc/supervisor/supervisord.conf
COPY deploy/supervisord.conf /etc/supervisor/conf.d/fcloud.conf
COPY deploy/nginx.conf.template /etc/nginx/fcloud.conf.template
COPY deploy/nginx-main.conf /etc/nginx/nginx.conf
COPY deploy/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# /data/config-hub 为 PVC 挂载点（§11.2）
RUN mkdir -p /data/config-hub/pg /data/config-hub/storage /data/config-hub/backups \
    && chown -R postgres:postgres /data/config-hub

EXPOSE 8080
VOLUME ["/data/config-hub"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf", "-n"]
