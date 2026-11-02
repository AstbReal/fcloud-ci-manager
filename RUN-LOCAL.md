# 本地测试运行指南（Windows + WSL Docker）

> 本机环境：Docker 在 WSL2（Ubuntu）中；后端在 Windows 侧以 `java -jar` 运行。
> **jar 默认不含前端**（`bootJar` 会跳过 `copyFrontend`）：本地前端用 Vite（:5173），API 走 8080。
> 需要旧的"单 jar 自带前端"形态时才加 `-PwithFrontend` 重新打包。

## 1. 启动依赖容器（WSL）

```bash
# PG 16（WSL 中的 Docker，端口映射到 Windows 127.0.0.1）；Redis 已移除（D26）
wsl -e bash -c "cd /mnt/d/code/fcloud-ci-manager && docker compose -f .ci/docker-compose.dev.yml up -d"

# GitLab OAuth 模拟服务（授权码流程，支持一键登录 force_user）
wsl -e bash -c "cd /mnt/d/code/fcloud-ci-manager/.ci/gitlab-lite && docker compose up -d --build gitlab-lite"
```

- PG：`127.0.0.1:5432`（库 `fcloud_ci_hub`，用户/密码 `fcloud/fcloud`）
- GitLab-lite：`127.0.0.1:8929`（内置用户 root / alice / bob / noemail）

## 2. 启动后端（Windows）

`application.yml` 已配好本地默认值（均可用环境变量覆盖，不影响生产镜像）：

| 配置 | 本地默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 容器内 `FCLOUD_SERVER_PORT=8081` 覆盖 |
| OAuth client-id/secret | `test-client-id/secret` | 指向 gitlab-lite |
| `fcloud.gitlab.base-url` | `http://127.0.0.1:8929` | 生产环境变量覆盖 |
| `fcloud.master-key-file` | `.local-data/master.key` | ⚠️ 路径变化 = 换密钥 |
| `fcloud.storage.root` / `backup.dir` | `.local-data/...` | 本地文件落点 |

### 方式 A：bootRun（推荐本地开发）

```bash
cd D:/code/fcloud-ci-hub/backend
./gradlew bootRun --args='--server.port=8080 --fcloud.jwt.secret=0123456789abcdef0123456789abcdef0123456789'
```

- `--args` 必须带：① `--server.port` 盖掉沙箱注入的 `SERVER__PORT`；② jwt.secret 无默认值，不传启动失败。
- 如果在自己终端（非 agent 环境）跑，`--server.port` 可省略，secret 仍建议传。

### 方式 B：java -jar

```bash
cd D:/code/fcloud-ci-hub/backend
./gradlew bootJar -x test
env -u SERVER__PORT FCLOUD_JWT_SECRET=0123456789abcdef0123456789abcdef0123456789 java -jar build/libs/fcloud-ci-manager-1.0.0.jar
```

### ⚠️ 已知坑（本机 WorkBuddy 环境）

1. **`SERVER__PORT=49702` 陷阱**：WorkBuddy 后台任务环境会注入 `SERVER__PORT`，
   Spring Boot 宽松绑定会把它当成 `server.port`（优先级高于 application.yml），
   导致端口随机且冲突。启动命令必须 `env -u SERVER__PORT`。
2. **WSL 休眠会停掉容器**（exit 255）：compose 已加 `restart: unless-stopped`，
   但若容器已退出，`docker start fcloud-dev-pg fcloud-gitlab-lite` 即可。
3. **`gradlew bootRun` 不可用**（Gradle daemon 拿不到注入的环境变量，
   同上一条 SERVER__PORT 问题），统一用 `java -jar` 方式启动。
4. **curl `-b cookies.txt` 不发送 HttpOnly cookie**：脚本测试时用
   `-H "Cookie: fcloud_token=$TOKEN"` 手动带。
5. **主密钥路径变化 = 历史密文全部解不开**（表现为接口 500 `decrypt failed`）：
   自动生成的 `master.key` 落在 `fcloud.master-key-file` 配置路径下，
   改了默认路径（或从 `/data/config-hub` 迁到 `.local-data`）必须把旧密钥文件
   一起拷贝过去，否则只能用新密钥、加密变量全部轮换。当前旧密钥已恢复。
6. **沙箱会脱敏命令回显里的 token 类字符串**：命令回显看到的 Bearer token
   可能和实际执行的不一致，以接口返回为准，不要据此怀疑token失效。

## 3. 启动前端（Vite，独立于 jar）

```bash
cd frontend
pnpm install            # 首次；pnpm 版本由 packageManager 字段锁定（corepack 自动取）
pnpm dev --host 127.0.0.1 --port 5173
# 直接用 node 跑也可以：
# node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173
```

没装 pnpm 时执行 `corepack enable pnpm`；Node 需 18+（Vite 5 要求），推荐 20/22 LTS。

`vite.config.ts` 把 `/api`、`/oauth2`、`/oauth`、`/login/oauth2`、`/actuator` 反代到 8080，
并且 `changeOrigin: false`，所以 OAuth 的 `redirect_uri` 会指回 `http://127.0.0.1:5173/login/oauth2/code/gitlab`。

## 4. 访问与测试

| 入口 | 地址 |
| --- | --- |
| 前端（登录页） | http://127.0.0.1:5173/login |
| 健康检查 | http://127.0.0.1:8080/actuator/health |
| OAuth 登录入口 | http://127.0.0.1:5173/oauth2/authorization/gitlab |
| 一键登录（脚本） | 授权 URL 后加 `&force_user=root` |

- 浏览器打开 `http://127.0.0.1:5173` → 跳 gitlab-lite 授权页 → 选 root 批准 → 回到 5173 首页。
- 8080 只提供 API：直接访问 `http://127.0.0.1:8080/` 返回 404 是预期行为。
- **空库首登即 SYSTEM_ADMIN**（§6.1）。

## 5. 已验证链路（2026-10-19）

- [x] OAuth 授权码全流程（authorize → code → token → user → JWT Cookie → /auth/me）
- [x] 首登管理员自举（bootstrap: first user root@gitlab.local granted SYSTEM_ADMIN）
- [x] 环境 / 应用 / 模块 / 变量 / 环境覆盖 CRUD
- [x] 加密变量落库为密文（AES-256-GCM）
- [x] Token 签发（明文仅一次）→ 流水线 dotenv 拉取
- [x] prod 覆盖生效（10.0.2.9）、dev 回退全局值（10.0.1.5）
- [x] 缺 modules → 400；无效 Token → 401

## 6. 停止

```bash
wsl -e bash -c "docker stop fcloud-dev-pg fcloud-gitlab-lite"
# 后端：结束 java 进程
```
