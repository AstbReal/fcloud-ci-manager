# fcloud-ci-manager

> 面向 CI 流水线的配置分发中心。
> 本文件是《fcloud-ci-manager 设计与决策书（最终版）》的执行记录与补充决策说明。

---

## 0. 一句话定位

管理端（人）维护应用/模块/环境下的变量与文件；流水线（机器）通过 API Token 拉取指定
**应用 + 环境 + 模块**的配置（dotenv）与文件（tar.gz）。

---

## 1. 技术栈（锁定，不得替换）

| 项 | 版本/选型 |
| --- | --- |
| JDK | 17（`options.release = 17`） |
| 框架 | Spring Boot 2.7.17 |
| 构建 | Gradle 7.5.1（Groovy DSL，wrapper 已锁定） |
| ORM | MyBatis-Plus 3.5.3.2（`ASSIGN_ID` 雪花主键，BIGINT） |
| 数据库 | PostgreSQL 18（Alpine `postgresql` 元包，实测 18.4；16 亦兼容，见 D27） |
| 迁移 | Flyway 9.22.3（Boot 2.7 默认 8.5.13 不识别 PG16+，需显式升级） |
| 缓存 | 进程内 `LocalCache`（无外部依赖，见决策 D26） |
| 认证 | Spring Security + OAuth2 Client（GitLab）+ JJWT 0.11.5 |
| 文档 | springdoc-openapi-ui 1.6.15 |
| 其他 | Lombok、validation、aop、actuator、commons-compress 1.25.0 |
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus + vuedraggable@4 |
| 镜像 | Alpine 两级镜像：base（JDK17 + PostgreSQL + nginx）+ 应用层（见 D15 / D26） |

---

## 2. 仓库结构

```
fcloud-ci-manager/
├── backend/                     # 后端（Spring Boot 单模块 Gradle 工程）
│   ├── build.gradle             # Spring Boot 2.7.17，Java 17
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat    # Gradle 7.5.1 wrapper（已锁定版本）
│   ├── src/main/java/ci/fcloud/manager/
│   │   ├── FcloudCiManagerApplication.java
│   │   ├── config/              # Security/OAuth2/Web/OpenAPI/Async/Properties
│   │   ├── common/              # ApiResponse/异常/Crypto/Audited 注解
│   │   ├── auth/                # JWT/Cookie/OAuth SuccessHandler
│   │   ├── user/ env/ app/ module/ template/ token/
│   │   ├── pipeline/            # 流水线 API + Token 过滤器 + 限流 + 访问日志
│   │   ├── mcp/                 # MCP Server（协议层 + 14 个工具）
│   │   ├── pat/                 # AI 客户端访问令牌（绑定用户 + scope）
│   │   ├── audit/               # 审计切面 + 查询 + 清理
│   │   └── backup/              # 导出/恢复/定时任务
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/V1__init.sql … V5__mcp_pat.sql
├── frontend/                    # 前端（Vue3 + Vite + TS）
├── .ci/                         # 构建与部署
│   ├── base.Dockerfile          # 运行时底座：Node20 + JDK17 + nginx + PostgreSQL
│   ├── Dockerfile               # 应用镜像（基于 base，不额外装包）
│   ├── supervise.sh             # 进程守护：PG → nginx → app，崩溃自动重启
│   ├── nginx.conf.template      # server 块模板（entrypoint 渲染）
│   ├── entrypoint.sh            # initdb / 建库建用户 / 渲染 nginx 配置
│   ├── build.sh                 # 构建 base 与应用镜像
│   ├── docker-compose.yml       # 单镜像部署（生产形态）
│   ├── .env.example             # 上述 compose 的环境变量模板
│   ├── docker-compose.dev.yml   # 本地 PG
│   └── gitlab-lite/             # 测试用 OAuth2 提供方
├── docs/
└── README.md
```

---

## 3. 快速开始

### 3.1 本地开发依赖（PG）

```bash
docker compose -f .ci/docker-compose.dev.yml up -d
```

> 本机 Docker 在 WSL2 中，PG 跑在 WSL 的容器里（Redis 已移除，见 D26）；
> 后端用 Windows 的 `backend/gradlew` 启动，通过 WSL IP 访问。详见 §7。

### 3.2 启动后端

```bash
cd backend && ./gradlew bootRun
```

### 3.3 启动前端

```bash
cd frontend && npm install && npm run dev
```

包管理器使用 npm，`package-lock.json` 是唯一依赖事实源。Node 需要 18+（Vite 5 要求），推荐 20/22 LTS。

Vite 代理 `/api`、`/oauth2`、`/oauth` → `http://localhost:8080`。

### 3.4 端到端 OAuth 登录测试（GitLab Lite）

真实 GitLab 镜像约 2.5GB、需 4GB+ 内存。为便于本地/CI 端到端验证登录链路，
仓库内置了 `.ci/gitlab-lite/` —— 一个实现了 GitLab OAuth2 契约的轻量替身：

```bash
# 1) 构建 hub 镜像（首次需要先构建底座，见 §6）
bash .ci/build.sh base      # 仅首次；会拉 node:20-alpine
bash .ci/build.sh

# 2) 构建并启动 gitlab-lite + fcloud-ci-manager（同一 compose 网络）
docker compose -f .ci/gitlab-lite/docker-compose.yml up -d --build

# 3) 浏览器打开 http://127.0.0.1:18080 → 自动跳转到 GitLab 授权页
#    选择任意内置用户（root/alice/bob/noemail）授权即可登录
```

gitlab-lite 实现的端点（与真实 GitLab 契约一致）：

| 端点 | 说明 |
| --- | --- |
| `GET /oauth/authorize` | 授权页（HTML 表单选用户）；`&force_user=<username>` 一键批准（脚本化测试用） |
| `POST /oauth/authorize` | 同意授权 → 302 带 `code` |
| `POST /oauth/token` | 授权码换 `access_token`（支持 Basic / form 认证、refresh_token） |
| `GET /api/v4/user` | 用户信息（id/username/name/email/avatar_url） |
| `GET /api/v4/version` / `/-/health` | 版本 / 存活检查 |

内置用户：`root`、`alice`、`bob`（有 email）、`noemail`（email 为 null，验证 §6.1 回退）。

用 `GITLAB_EMAIL_NULL_FOR=noemail` 环境变量可让任意指定用户返回空 email。

已验证的端到端链路（`bash .dsh-tmp/oauth-verify.sh`）：

1. `/oauth2/authorization/gitlab` → 302 到 gitlab-lite 授权页
2. 授权 → 回调 `/oauth/gitlab/callback?code=...&state=...`
3. 后端换 token、拉 `/api/v4/user`、建号、签发 JWT、写 Cookie
4. 空库首登用户 → `SYSTEM_ADMIN`；第二用户 → `NONE`
5. `noemail` 用户 email 为 null → 回退 `noemail@<gitlab host 域名>` 并记录日志

> 注意：`.ci/gitlab-lite/` 仅用于测试，**非生产**。生产请接真实 GitLab，
> 按 §11.3 配置 `FCLOUD_GITLAB_BASE_URL` / `_CLIENT_ID` / `_CLIENT_SECRET`。

### 3.5 单镜像部署（compose）

```bash
bash .ci/build.sh base          # 首次；构建底座（会拉 node:20-alpine）
bash .ci/build.sh               # 构建应用镜像 fcloud-ci-manager:latest
cp .ci/.env.example .ci/.env    # 填 GitLab 参数与 FCLOUD_JWT_SECRET
docker compose -f .ci/docker-compose.yml up -d
```

- 数据全部在命名卷 `fcloud-data`：PG 数据、模块文件、备份包、`master.key`
- `down -v` 会连卷一起删（等于清库）；只停服务用 `down` 或 `stop`
- 健康检查走 `/actuator/health/liveness`，首次启动约 60–90 秒
- 端口默认 `8080`，改 `.env` 里的 `FCLOUD_HTTP_PORT`
- 该 compose 只跑一个容器：PG / nginx / JVM 由镜像内 `supervise.sh` 拉起
- 日志：PG / nginx / JVM 都写容器 stdout，`docker compose -f .ci/docker-compose.yml logs -f hub`
  可看全部；轮转由 json-file driver 负责（20m × 3）。应用另写一份日志文件到数据卷
  `logs/app.log`（Logback 自轮转：单文件 50MB、保留 7 份、总量上限 500MB），路径可用
  `FCLOUD_LOG_FILE` 覆盖。要只看某一路：`docker logs fcloud-ci-manager 2>&1 | grep -E '^(LOG|FATAL):'`（PG）、
  `grep '^\[supervise\]'`（监管与重启记录）

---

## 4. 环境变量

见《设计决策书》§11.3。关键项：

| 变量 | 必填 | 默认 | 说明 |
| --- | :-: | --- | --- |
| `FCLOUD_GITLAB_BASE_URL` | ✓ | — | 自建 GitLab 根地址 |
| `FCLOUD_GITLAB_CLIENT_ID` / `_SECRET` | ✓ | — | OAuth 应用凭据 |
| `FCLOUD_JWT_SECRET` | ✓ | — | ≥32 字符 |
| `FCLOUD_MASTER_KEY` | | 自动生成 | Base64 32 字节 |
| `FCLOUD_SERVER_PORT` | | 8081（容器内） | Spring Boot 端口 |
| `FCLOUD_STORAGE_ROOT` | | /data/config-hub/storage | 文件根目录 |
| `FCLOUD_LOG_FILE` | | /data/config-hub/logs/app.log | 应用日志文件（console 同时输出） |
| `FCLOUD_MCP_ENABLED` | | `true` | MCP 端点总开关，关掉后 `/mcp` 返回 404 |
| `FCLOUD_MCP_WRITE_ENABLED` | | `false` | 写工具是否可见（仍需令牌带 `mcp:write`） |
| `FCLOUD_MCP_REVEAL_ENABLED` | | `false` | 明文读取工具是否可见（仍需 `mcp:secret` + 管理员） |
| `FCLOUD_MCP_RATE_PER_MINUTE` | | `120` | 每个令牌每分钟调用上限 |

---

## 5. 文档未覆盖细节的决策记录

> 原则：简单、显式、可审计。

| # | 决策点 | 采用方案 | 理由 |
| --- | --- | --- | --- |
| D1 | `.ci/docker-compose.dev.yml` 的镜像 tag | 只用 `postgres:16-alpine` | 本地已有的镜像，不额外拉取；生产镜像内跟随 Alpine 走 18（D27），SQL 兼容 |
| D27 | 生产镜像里的 PG 大版本 | 不钉版本，跟随 Alpine 的 `postgresql` 元包（当前 18.4）；本地开发依赖仍是 `postgres:16-alpine` | 钉 16 需要 Alpine community 仓库、且要重建 base；业务 SQL 只用标准 DDL/DML，18 兼容。风险：Flyway 9.22.3 官方支持到 15，PG18 首次启动需确认迁移成功；跨大版本换数据必须走逻辑备份/恢复，物理数据目录不兼容 |
| D28 | 模板承载文件 + 既有模块绑定 | 模板新增 `fch_template_file`；变量/文件加 `origin`（MODULE/TEMPLATE）。绑定只补缺失 key，同名项保留模块自有；解绑默认保留内容；共享模块禁止绑定 | 60 个存量应用的自有变量/文件不能被模板同步误删；共享模块是引用制、模板是快照 + 显式同步，混用会让订阅方被动变化。文件副本落 `fch_file`，流水线/MCP/矩阵/备份的读取路径零改动 |
| D2 | `fcloud.storage.keep-versions` 等可配项 | 在 `application.yml` 显式暴露 | 文档 §2.4 说"可配置"，但不给默认名；用 `FCLOUD_FILE_KEEP_VERSIONS` 显式化 |
| D3 | 前端构建产物拷贝 | `bootJar` 依赖 `copyFrontend` 任务 | §11.5 说"构建时拷入"；用 Gradle 任务而非 Dockerfile 单独 COPY，保证 `bootRun` 也能看到 |
| D4 | 实体基类不启用逻辑删除 | `BaseEntity` 无 `deleted` 字段，`mybatis-plus.global-config.db-config` 不配 `logic-delete-field` | §3 的 schema 没有 `deleted` 列；删除一律真实删除 + 级联（§5.3/§5.4），符合"显式、可审计" |
| D5 | 仅系统管理员的 console 端点路径 | 统一放 `/api/console/admin/**`，由 SecurityConfig `hasRole("SYSTEM_ADMIN")` 拦截 | §6.3 矩阵中"用户管理/审计/备份/Token"等仅系统管理员；用 URL 前缀集中声明比逐方法散落更显式可审计 |
| D6 | 认证/授权失败的响应格式 | 自定义 `authenticationEntryPoint` / `accessDeniedHandler`，返回 `ApiResponse` JSON | §5 要求统一包装 `{code,message,data}`；默认的 302 跳登录页对前端不友好 |
| D7 | `/users/options` 的路径 | 放 `/api/console/auth/user-options` | §5.1 规定"登录"即可访问，与"仅系统管理员"的 `/api/console/admin/users` 区分开 |
| D8 | `PermissionService` 的成员查询可注入 | 私有 `membershipLookup` 函数字段 + `setMembershipLookupForTest()` | MyBatis-Plus 的 lambda 查询在无真实 TableInfo 的单测里无法 mock；抽出查找函数后 §6.3 矩阵可被完整单测覆盖 |
| D9 | `VariableResolver` 的应用级覆盖二次过滤 | SQL 过滤后再用 Java `stream.filter` 兜底 | 防止查询条件被改动时把别的应用的覆盖泄漏给其他应用（纵深防御） |
| D10 | `/dev-login` 模拟登录入口 | `@ConditionalOnProperty(fcloud.dev-login-enabled=true)` 的 servlet | 本机无 GitLab 实例，用于验证 M2 登录链路；默认关闭，生产镜像不设置该变量 |
| D11 | `BaseEntity` 的审计列按表 opt-in | 基类只声明 `id`；`createdAt`/`updatedAt` 由子类覆写 getter + `@TableField`；`AuditMetaObjectHandler` 用 `hasSetter` 判断 | §3 各表审计列不一致（`fch_module_env`/`fch_template_var` 两者皆无，`fch_variable_override` 只有 `updated_at`）；统一声明会生成不存在的列导致 SQL 报错 |
| D12 | 不启用逻辑删除 | `BaseEntity` 无 `deleted`，`mybatis-plus.global-config.db-config` 不配 `logic-delete-field` | 同 D4；§3 schema 无该列 |
| D13 | `Environment.parentEnvId` 的显式/隐式设置 | 加非持久化字段 `parentEnvIdSet`，自定义 setter 在反序列化时置位 | 需区分"不修改继承关系"（JSON 未携带）与"解除继承"（JSON 传 `null`） |
| D14 | 请求体中文导致 `Invalid UTF-8` | 验收脚本统一用 ASCII 载荷 | Git Bash/curl 在 Windows 下以 GBK 发送，服务端按 UTF-8 解析；非产品缺陷，属测试脚本环境问题 |
| D15 | 运行时底座 | 两级镜像：`.ci/base.Dockerfile`（`node:20-alpine` + `apk` 装 openjdk17 / nginx / postgresql / curl / su-exec）产出 `fcloud-ci-manager-base`；`.ci/Dockerfile` 在其上补 `supervise.sh` 与应用产物 | 本机 Docker Hub 部分镜像源不可达，只保留一个可复用的底座层，避免每次构建都重装 JDK/PG。组件版本仍满足 §1.3（JDK17 / PG / nginx），发行版为 Alpine |
| D16 | 前后端构建位置 | 前端 `vite build` 与后端 `bootJar` 在宿主机预构建，产物由 `.ci/build.sh` 收集后 `docker build` 拷入 | 构建环境无法拉取 `node`/`gradle` 基础镜像做多阶段构建；这符合 §11.5「前端构建产物拷入 static」的意图 |
| D17 | SPA fallback | 服务端 `SpaFallbackController` `forward:/index.html` + nginx `try_files` 双保险 | §11.2 要求 nginx `try_files $uri $uri/ /index.html`；应用内也做一份，便于 `bootRun` 直接调试 |
| D18 | `/dev-login` 在 nginx 的位置 | 必须显式 `location /dev-login { proxy_pass ... }`，否则被 `location /` 的 `try_files` 吞掉返回 index.html | 实测踩坑：SPA fallback 会把未列出前缀的后端路径也当作前端路由。验收脚本曾因此拿不到 cookie |
| D19 | 上传体积限制的位置 | 容器层 `max-file-size` / `max-request-size` 放宽到 `${FCLOUD_MAX_UPLOAD_MB:2048}MB`，单文件 50MB 由 `FileService` 校验；备份恢复在 nginx 单独放开 `client_max_body_size 0` | Spring 默认 10MB 会先拦截返回 500；备份包可能几百 MB，需要恢复接口能收下整包，而模块文件仍按 §2.4 限制 50MB |
| D20 | 健康检查 | `management.endpoint.health.probes.enabled=true` + `management.health.group.readiness.include=readinessState,db` | §11.2 要求 `/actuator/health/liveness` 与 `/readiness`（含 DB）。Boot 2.7 需显式开启 probes，否则 404 |
| D21 | OAuth 回调路径 | Spring `redirect-uri` 配标准路径 `{baseUrl}/login/oauth2/code/gitlab`，nginx 把 §6.1 的对外契约 `/oauth/gitlab/callback` 作为别名精确反代到 `/login/oauth2/code/gitlab` | Spring Security 5.7 的 `OAuth2LoginAuthenticationFilter` **硬编码**监听 `{baseUrl}/login/oauth2/code/{registrationId}`，无法配置改变；`redirect-uri` 必须与该路径一致，否则回调时 `[authorization_request_not_found]`。用 nginx 别名对外呈现 §6.1 契约，后端复用 Spring 完整校验流程 |
| D22 | OAuth 会话策略 | `SessionCreationPolicy.IF_REQUIRED`（从 STATELESS 改） | OAuth2 授权码流程用 `HttpSessionOAuth2AuthorizationRequestRepository` 在 session 暂存 authorizationRequest（含 state 校验）；STATELESS 会导致回调时找不到授权请求。登录完成后业务请求仍走 JWT Cookie，不受影响 |
| D23 | 反向代理后的 redirect_uri | nginx 传 `X-Forwarded-Host/Port/Proto` + Spring `server.forward-headers-strategy=framework`，并允许 `FCLOUD_OAUTH_REDIRECT_URI` 显式覆盖 | 容器内 nginx 监听 8080、宿主映射 18080，Spring 靠 `{baseUrl}` 推断会得到缺端口/错端口的地址；显式配置 + 转发头两路保证 redirect_uri 正确 |
| D24 | email 回退 | `OAuth2LoginSuccessHandler` 里判断 email 为空时调用 `GitLabUserMapper.resolveEmail()` 回退 `{username}@{gitlab host 域名}` 并 `log.warn` | §6.1 要求 email 为 null 时回退并记录日志；回退域名取自 `FCLOUD_GITLAB_BASE_URL` 的 host |
| D25 | 测试用 GitLab 替身 | `.ci/gitlab-lite/`：基于 `postgres:16-alpine` + Python 实现 `/oauth/authorize`、`/oauth/token`、`/api/v4/user` 三个端点，与真实 GitLab 契约一致 | 本机 Docker Hub 拉不到 `gitlab/gitlab-ce`（约 2.5GB），用它端到端验证 OAuth 授权码流程、email 回退、首登管理员；真实部署仍接 GitLab |
| D26 | 去掉 5 个 apk 依赖 | Redis → 进程内 `LocalCache`；supervisor → `.ci/supervise.sh`；bash → POSIX sh；gettext → `sed`；tzdata → 容器 UTC + JVM `-Duser.timezone=Asia/Shanghai` | 底座只提供 JDK/PG/nginx，其余用应用自身能力替代，镜像更薄。**代价**：黑名单/缓存/限流只在单进程内有效，不支持多副本；容器 OS 层日志时间为 UTC |

（后续里程碑的补充决策继续追加到此表。）

---

## 6. 里程碑进度

| 里程碑 | 内容 | 状态 |
| --- | :-: | :-: |
| M1 | Gradle 骨架、application.yml、Flyway schema、健康检查、docker-compose.dev | ✅ 已验证 |
| M2 | Spring Security + GitLab OAuth + JWT Cookie + 首登管理员 + `/auth/me` | ✅ 已验证 |
| M3 | 环境 CRUD + 继承链；应用 CRUD；模块 CRUD + 绑定环境 + 共享模块 + 订阅 | ✅ 已验证 |
| M4 | 变量矩阵（定义/全局/覆盖/继承解析/加密/导入导出/reveal）+ 文件版本 + 成员 | ✅ 已验证 |
| M5 | 模板库 + 实例化 + diff 同步；模块复制/分组/排序 | ✅ 已验证 |
| M6 | 流水线 API + Token + 限流 + 访问日志 + 审计 + 备份恢复 + 定时任务 | ✅ 已验证 |
| M7 | 前端全页面 | ✅ 已验证 |
| M8 | 单镜像 Dockerfile + entrypoint + 启动验证 | ✅ 已验证 |

### M1 验证记录

- `./backend/gradlew -p backend compileJava` → BUILD SUCCESSFUL（Gradle 7.5.1，Java 17）
- `./backend/gradlew -p backend bootRun` → `Started FcloudCiManagerApplication in 3.568 seconds`
- Flyway → `Successfully validated 1 migration`，`Schema "public" is up to date`
- 数据库表 → 17 张业务表 + `fch_flyway_schema_history` 全部创建（version=1, success=t）
- actuator → `/actuator/health` 返回 200

### M2 验证记录

通过 `/dev-login` 模拟 GitLab OAuth 回调（仅在 `fcloud.dev-login-enabled=true` 时注册，
生产镜像不设置该变量），用 curl 逐条验证：

| # | 验收项 | 结果 |
| --- | --- | --- |
| 1 | 空库首个登录用户 → `SYSTEM_ADMIN` | ✅ alice 登录后 `globalRole=SYSTEM_ADMIN` |
| 2 | 第二个登录用户 → `NONE` | ✅ bob 登录后 `globalRole=NONE` |
| 3 | `GET /auth/me` 返回当前用户 | ✅ 200，含 id/name/nickname/email/globalRole |
| 4 | `PUT /auth/me` 仅改昵称 | ✅ 200，nickname 更新为 Bob-2 |
| 5 | 未登录访问 console API | ✅ 401（`{"code":40100,"message":"未登录或会话已过期"}`） |
| 6 | 非管理员访问 `/api/console/admin/users` | ✅ 403（`{"code":40300,...}`） |
| 7 | `POST /auth/logout` 清 Cookie + 黑名单 | ✅ 200；旧 token 复用返回 401 |
| 8 | 黑名单 key `jwt:blacklist:{jti}` 生效（当时在 Redis，现为 LocalCache） | ✅ 登出后旧 token 立即失效 |

### M3 验证记录

`bash .dsh-tmp/m3-check.sh`（18 个场景全部符合预期）：

| # | 场景 | 结果 |
| --- | --- | --- |
| 1 | 建 dev → test，并把 test 的 parentEnvId 设为 dev | ✅ 200，继承链建立 |
| 2 | 把 dev 的父环境设成 test（成环） | ✅ `40000 不允许设置环境继承环（... 的祖先链包含自身）` |
| 3 | 把 dev 的父环境设成自身 | ✅ `40000 父环境不能是环境自身` |
| 4 | 重复 code 建环境 | ✅ `40900 环境 code 已存在: dev` |
| 5 | 环境列表（含 parent） | ✅ 200，返回 parentEnvId |
| 6/7 | 建 payment-service / order-service | ✅ 200，createdBy 记录操作人 |
| 8 | 应用下建私有模块 + 绑定 dev/test | ✅ 200，`environment-ids` 返回两个环境 |
| 9 | 建共享模块 common-notify | ✅ 200，`applicationId` 为空、`isShared=true` |
| 10 | order-service 订阅共享模块 | ✅ 200 |
| 11 | 共享模块订阅方列表 | ✅ 200，返回 order-service |
| 12 | 看板数据 | ✅ 200，私有模块 + 订阅模块 + 订阅数都正确 |
| 13 | 重复订阅 | ✅ `40900 该应用已订阅此共享模块` |
| 14 | 订阅私有模块 | ✅ `40000 只能订阅共享模块: db-config` |
| 15 | 删除有订阅方的共享模块 | ✅ `40900 共享模块仍有 1 个订阅方，删除前请先取消订阅` |
| 16 | 跨应用复制模块（连同值） | ✅ 200，生成到 order-service 下 |
| 17 | 删应用但确认名不匹配 | ✅ `40000 确认名称不匹配，需输入应用 code: order-service` |
| 18 | 模块排序 + 改分组 | ✅ 200 |

### M4 验证记录

`bash .dsh-tmp/m4-full.sh`（Python 验收脚本，35 项断言全部通过）：

| 分组 | 验收项 | 结果 |
| --- | --- | :-: |
| §13-4 | 全局值写入后 dev/test/prod 三环境都继承 `10.0.1.5` | ✅ |
| §13-4 | prod 设覆盖 → 仅 prod 变 `10.0.2.9`，dev/test 不变 | ✅ |
| §13-4 | prod 单元格标记 `override=true` | ✅ |
| §13-4 | 清 prod 覆盖 → 回退继承全局值 | ✅ |
| §2.2 | 在 test 设覆盖 → prod 沿父链继承，标记 `inherited=true` | ✅ |
| §13-5 | 管理员可见加密变量明文，`canReveal=true` | ✅ |
| §13-5 | 管理员 reveal 返回明文 | ✅ |
| §13-5 | 操作员看矩阵为 `••••••`、`masked=true`、`canReveal=false` | ✅ |
| §13-5 | 操作员 reveal 他人加密变量 → `40300` | ✅ |
| §13-5 | 操作员可写入新值（轮换），但仍读不到明文 | ✅ |
| §13-5 | 创建人可读自己创建的加密变量 | ✅ |
| §13-5 | 操作员导出 dotenv → 加密值为 `__MASKED__` | ✅ |
| §13-5 | 管理员导出 dotenv → 加密值为明文 | ✅ |
| §7 | reveal 写入高危审计（`isCritical=true`） | ✅ |
| §13-6 | 订阅方列表含 m4app 与 m4app2 | ✅ |
| §13-6 | 有订阅方时删除被阻断（`40900`） | ✅ |
| §13-6 | 取消订阅后可删除 | ✅ |
| §13-9 | v1–v4 连续上传，`currentVersion=4` | ✅ |
| §13-9 | 版本列表仅剩 v2/v3/v4（保留最近 3 个） | ✅ |
| §13-9 | 指定版本 v4 可下载，内容正确 | ✅ |
| §13-9 | 省略 version 下载最新版 | ✅ |

### M5 验证记录

`bash .dsh-tmp/m5-full.sh`（Python 验收脚本，29 项断言全部通过）：

| 分组 | 验收项 | 结果 |
| --- | --- | :-: |
| §2.5 | 建模板并带 3 个变量定义 | ✅ |
| §2.5 | 实例化生成模块骨架，记录 `template_id` 与 `template_synced_at` | ✅ |
| §2.5 | 明文默认值写入 `global_value`（DB_HOST / DB_PORT） | ✅ |
| §2.5 | **加密类型的默认值不落库** | ✅ |
| §2.5 | 必填标记随定义复制 | ✅ |
| §2.5 | 刚实例化时 diff 三分类全空 | ✅ |
| §2.5 | 改模板后 diff.added = [GIT_TOKEN] | ✅ |
| §2.5 | diff.changed = [DB_HOST]，并带模板侧/模块侧定义对照 | ✅ |
| §2.5 | diff.removed = [DB_PORT] | ✅ |
| §2.5 | `hasUpdate` 徽标在模板变更后为 true | ✅ |
| §2.5 | **同步不覆盖已填的全局值**（10.9.9.9 保持） | ✅ |
| §2.5 | **同步不覆盖已填的环境覆盖**（10.9.9.10 保持） | ✅ |
| §2.5 | 同步采纳定义变更（remark 更新） | ✅ |
| §2.5 | removedAction=`KEEP` 保留模块变量 | ✅ |
| §2.5 | removedAction=`DELETE` 删除 1 个 | ✅ |
| §2.5 | 全部同步后 `hasUpdate=false` | ✅ |
| §5.4 | 跨应用复制「仅骨架」：定义复制、值不带 | ✅ |
| §5.4 | 跨应用复制「连同值」：global_value 一并复制 | ✅ |
| §5.4 | 改分组 / 分组生效 / 模块排序 | ✅ |
| §5.8 | 从模块另存为模板（含变量定义） | ✅ |
| §5.8 | 明文 global_value 转为模板默认值 | ✅ |
| §5.8 | **加密变量默认值不进入模板** | ✅ |
| §5.8 | 删模板后模块 `template_id` 置空 | ✅ |

### M6 验证记录（对应 §13 第 7、8、10、11 条）

`bash .dsh-tmp/m6-full.sh`（Python 验收脚本，41 项断言全部通过）：

**§4.2 GET /api/v1/pipeline/config** 实际响应：

```dotenv
# module: db-config
DB_HOST=10.0.1.5
DB_PORT=5432
DB_PASSWORD=prod-secret
# module: common-notify
NOTIFY_URL=https://im.internal/hook
DB_HOST=overridden-by-notify
```

| 分组 | 验收项 | 结果 |
| --- | --- | :-: |
| §4.1 | Token 创建时完整展示一次，列表不含明文/hash | ✅ |
| §4.2 | `Content-Type: text/plain; charset=utf-8` | ✅ |
| §4.2 | 按请求参数顺序输出 `# module:` 分组 | ✅ |
| §4.2 | 全局值输出；prod 覆盖生效；加密变量输出解密明文 | ✅ |
| §4.2 | 同名 key 后出现的模块覆盖先出现的（last-wins） | ✅ |
| §2.2 | dev 无覆盖 → 回退到全局值 | ✅ |
| §4.2 | 订阅的共享模块可展开 | ✅ |
| §13-7 | modules 缺省 / 为空 → 400 | ✅ |
| §13-8 | 无效 Token / 缺失 Authorization → 401 | ✅ |
| §13-8 | 跨应用模块 → 403 | ✅ |
| §13-8 | **每分钟 60 次限额，第 61+ 次 → 429** | ✅ |
| §4.3 | 返回 `application/gzip`；tar.gz 结构 `{moduleCode}/{fileKey}` | ✅ |
| §4.3 | 未找到文件 → 404；modules 必填 → 400 | ✅ |
| §4.4 | 单文件下载 200；`Content-Disposition` 带原始文件名 | ✅ |
| §4.4 | 指定 `version=1` 可下载，内容正确 | ✅ |
| §7 | Token 签发入审计；吊销入审计且 `isCritical=true` | ✅ |
| §7 | **流水线调用不进审计** | ✅ |
| §8 | 访问日志可查询 | ✅ |
| §9.1 | 立即备份生成 `fcloud-backup-*.tar.gz` 并可下载 | ✅ |
| §9.1 | manifest 含全部配置段，不含审计/访问日志 | ✅ |
| §9.1 | 包内有 `files/` 二进制；**不含主密钥** | ✅ |
| §9.1 | 加密变量的 `global_value` 与覆盖值均以密文导出 | ✅ |
| §9.1 | Token 仅导出 hash 与前缀 | ✅ |
| §9.2 | 备份保留最近 3 份 | ✅ |
| §9.2 | ABORT：code 冲突 → 整体回滚报错（40900） | ✅ |
| §9.2 | MERGE：按自然键跳过已存在，仅补缺失 | ✅ |
| §9.2 | OVERWRITE：清空后导入成功，文件一并恢复 | ✅ |

### M7 验证记录

前端技术栈：Vue 3 + Vite 5 + TypeScript + Pinia + Vue Router 4 + Element Plus + vuedraggable 4。

`bash .dsh-tmp/m7-full.sh`（28 项断言全部通过）：

| 分组 | 验收项 | 结果 |
| --- | --- | :-: |
| 产物 | `index.html` 可访问，含 `<div id="app">` 挂载点 | ✅ |
| 产物 | `/` 返回 SPA 入口 | ✅ |
| 产物 | `index.html` 引用 `/assets/*.js|css`，静态资源可加载 | ✅ |
| SPA | `/login` `/applications` `/templates` `/environments` `/users` `/tokens` `/audit` `/backups` `/profile` `/pipeline-docs` 均返回 SPA（刷新不 404） | ✅ |
| 链路 | 登录后 `/auth/me` 可用（角色 SYSTEM_ADMIN） | ✅ |
| 链路 | 应用看板 / 工作台待办（四类键齐全）/ 成员选择器 | ✅ |
| 链路 | 环境管理 / 应用简表 / 共享模块 / 模板库 / 用户管理 | ✅ |
| 链路 | 审计日志 / 访问日志 / 备份记录（分页） | ✅ |
| 链路 | 变量矩阵 / 成员列表 / 文件列表 | ✅ |
| 鉴权 | 未登录访问 console API → 401 | ✅ |
| 鉴权 | 未登录仍可打开 `/login` 页面 | ✅ |

**页面清单**（对应 §10.2 全部 12 个路由，均可用）：

| 路由 | 页面 | 关键实现 |
| --- | --- | --- |
| `/login` | 登录 | GitLab 登录按钮 + "记住我"（`?remember=1`）+ 首登即管理员提示 |
| `/` | 工作台 | 四类待办卡片（缺必填/模板待同步/Token 到期/24h 高危），空态"一切正常" |
| `/applications` | 应用看板 | 左栏 280px 共享模块库 + 右侧应用卡片墙；拖拽订阅（toast 可撤销）/ 复制 / 改分组 |
| `/modules/:id` | 模块详情 | 变量矩阵（全局值 + 环境列，`↳继承`/覆盖圆点/`••••••`+查看）、文件版本、成员、模板同步条 |
| `/templates` | 模板库 | CRUD + 变量定义编辑 + 实例化 |
| `/environments` | 环境管理 | 列表 + 抽屉编辑 + 继承链选择 + 上移/下移排序 |
| `/users` | 用户管理 | 列表 + 全局角色切换 |
| `/tokens` | Token 管理 | 应用下拉 + 签发（明文仅弹一次）+ 吊销 |
| `/audit` | 审计日志 | 动作/高危/时间筛选 + 高危红色标记 + 分页 |
| `/backups` | 备份恢复 | 记录 + 立即备份 + 下载 + 上传恢复（三策略，OVERWRITE 需输入确认） |
| `/profile` | 个人设置 | 昵称编辑、GitLab 信息、退出 |
| `/pipeline-docs` | 流水线接入 | 三个接口的 curl 示例、错误码、dotenv 说明 |

### M8 验证记录（§13-12 单镜像）

镜像：`fcloud-ci-manager:latest`。**以下为 2026-10-19 的历史记录**，当时底座是 `postgres:16-alpine`、用 supervisord 且内置 Redis；
当前形态（两级镜像 + `supervise.sh` + 进程内缓存）见 D15 / D26。

`docker run` 命令（与 §13-12 一致）：

```bash
docker run -d --name fcloud-ci-manager-test \
  -e FCLOUD_GITLAB_BASE_URL="https://gitlab.example.com" \
  -e FCLOUD_GITLAB_CLIENT_ID="..." \
  -e FCLOUD_GITLAB_CLIENT_SECRET="..." \
  -e FCLOUD_JWT_SECRET="0123456789abcdef0123456789abcdef0123456789" \
  -v fcloud-data:/data/config-hub \
  -p 18080:8080 \
  fcloud-ci-manager:latest
```

启动与进程状态：

```
app          RUNNING   pid 29
nginx        RUNNING   pid 28
postgresql   RUNNING   pid 26
redis        RUNNING   pid 27
```

端口监听（符合 §11.2：只有 8080 对外）：

| 端口 | 监听地址 | 组件 |
| --- | --- | --- |
| 8080 | `0.0.0.0` | nginx（对外唯一端口） |
| 8081 | 内部 | Spring Boot（仅 nginx 反代） |
| 5432 | `127.0.0.1` | PostgreSQL 16 |
| 6379 | `127.0.0.1` | Redis 7 |

验证结果：

| 项目 | 结果 |
| --- | :-: |
| health liveness / readiness（走 nginx :8080） | ✅ 200 |
| nginx 静态页面 `GET /` | ✅ 200，返回 SPA 入口 |
| SPA fallback `GET /login` | ✅ 200 |
| 静态资源 `/assets/*.js` | ✅ 200 |
| API 反代 `GET /api/console/auth/me`（未登录） | ✅ 401（正确驳回） |
| Flyway 自动迁移 | ✅ 17 张业务表 + `fch_flyway_schema_history`（version=1, success=t） |
| 宿主机访问映射端口 `18080` | ✅ 200 |
| **重启后数据仍在** | ✅ 用户表/应用表数据保留，登录态仍有效 |

---

## 7. MCP 服务（AI 客户端接入）

内置 MCP Server，AI 客户端（WorkBuddy / Claude Desktop / Cursor 等）可以直接查配置、
解释取值来源、对比环境差异，也可以改值、导入 dotenv、上传文件。
协议为 **Streamable HTTP，单端点 `POST /mcp`**，与 UI 共用同一套数据与权限。

完整设计与取舍见 `design/mcp-server-design.md`；本节只讲怎么用。

### 7.1 端点与凭证

```
POST http://<host>:8080/mcp
Authorization: Bearer <token>
Content-Type: application/json
```

| 凭证 | 前缀 | 主体 | 能力 |
| --- | --- | --- | --- |
| 个人访问令牌（PAT） | `chub_pat_` | 真实用户 | 跟随该用户的全局角色 + scope；写操作进审计 |
| 流水线 Token | `chub_` | 机器（uid=0） | 只读；写工具不会出现在工具列表里 |

PAT 在 **Token 管理 → AI 访问令牌** 签发，明文只展示一次。三个 scope：

- `mcp:read`（必有）— 查看应用、模块、配置、差异
- `mcp:write` — 改值、导入 dotenv、建/删变量、上传文件
- `mcp:secret` — 读取加密变量明文（高危，默认不给）

### 7.2 客户端配置

**系统管理 → MCP 服务 → 接入教程** 里有完整三步（签发令牌 → 配客户端 → 验证连通），配置片段可一键复制。以下是配置本身：

```json
{
  "mcpServers": {
    "fcloud-ci-manager": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": { "Authorization": "Bearer chub_pat_xxxxxxxx" }
    }
  }
}
```

只支持 stdio 的客户端需要一个转发壳（stdin JSON-RPC → POST /mcp），业务逻辑不重复实现。

### 7.3 工具清单

**只读（`mcp:read`）**

| 工具 | 作用 |
| --- | --- |
| `list_applications` | 应用列表（code / 名称 / 模块数） |
| `list_environments` | 环境列表与继承链 |
| `list_modules` | 模块列表（所属应用 / 是否共享 / 绑定环境） |
| `get_config` | 按 应用+环境+模块 生成最终 dotenv |
| `get_variable_matrix` | 变量矩阵：每个 key 在各环境的取值与来源 |
| `explain_config_value` | 解释某个值命中了哪一层覆盖/继承 |
| `diff_config` | 两个环境的配置差异 |
| `list_module_files` | 模块在指定环境下的文件清单 |

**写（`mcp:write`）**

| 工具 | 作用 | 备注 |
| --- | --- | --- |
| `set_variable_value` | 改单值（GLOBAL / ENV） | key 不存在直接报错，不隐式创建 |
| `import_dotenv` | 批量导入 | **默认 dryRun=true**，传 `false` 才落地 |
| `create_variable` | 新建变量定义 | `PLAIN` / `ENCRYPTED` |
| `delete_variable` | 删除定义及全部覆盖 | 仅系统管理员，写 critical 审计 |
| `upload_file` | 上传文件（base64） | 落新版本，保留 3 版 |
| `reveal_secret` | 读加密变量明文 | 需 `mcp:secret` + 服务端开关 + 管理员 |

### 7.4 开关与默认值

三个开关在 **系统管理 → MCP 服务** 里改，保存后 5 秒内对所有客户端生效，不需要重启。
环境变量是**部署默认值**，只有在页面没改过时才起作用。

| 开关 | 默认 | 关掉的后果 |
| --- | --- | --- |
| 启用 MCP 端点 | 开 | `/mcp` 返回 404，客户端全部断开 |
| 允许写入 | 关 | 5 个写工具不出现 |
| 允许读取密钥明文 | 关 | 1 个明文工具不出现 |

开关只管"工具是否可见"，实际能否执行还要看 PAT 的 scope 和用户角色——
开关打开但令牌只读，写工具依然不出现。页面上每个开关旁边都标了它影响几个工具。

限流、结果上限、会话时长是部署参数（`FCLOUD_MCP_RATE_PER_MINUTE` 等），需改环境变量后重启。

### 7.5 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `401 invalid token` | 令牌错误 / 过期 / 已吊销 | 重新签发；吊销后本地缓存同步清除，立即失效 |
| `404 session not found` | 服务重启，内存会话丢失 | 客户端重新 `initialize` 即可 |
| 加密变量显示 `••••••` | 默认脱敏，这是预期行为 | 确需明文：令牌加 `mcp:secret` + 服务端开 `reveal-enabled` |
| 写工具不出现 | `write-enabled=false`，或令牌没有 `mcp:write` | 两个条件都要满足 |
| `/mcp` 返回 HTML | 反代未放行该路径 | nginx 需 `location = /mcp` 精确匹配（模板已含） |
| 工具返回 `isError: true` | 业务失败（模块不存在、环境未绑定等） | 按提示修正参数，不是服务故障 |

### 7.6 实测记录（2026-10-21）

模拟客户端跑完整握手（initialize → initialized → tools/list → tools/call），
只读令牌 8 个工具、写令牌 13 个、secret 令牌 9 个，视图符合 scope 预期。

| 场景 | 结果 |
| --- | --- |
| 为什么会取到这个值 | `PRIVATE_SCC = 3132`，来源 `MODULE_OVERRIDE @sit`，全局值 4342 被覆盖 |
| 拉一份完整配置 | dotenv 两行，与 `/api/v1/pipeline/config` 输出一致 |
| 两环境差异巡检 | `PRIVATE_SCC 3132 → 4342`，其余相同 |
| 写值 / 回滚 | 改 `sit` 的值 → 矩阵立即反映 → 改回，数据无残留 |
| 批量导入 | 默认 dryRun 给出 `CREATE ×2 / SKIP ×1`，`dryRun:false` 后落地 |
| 越权 | 只读令牌硬调写工具 → `isError`；越权吊销他人令牌 → `40300` |
| 审计 | `MCP_*` 记录带 `user_email`，detail 只有 key/scope，无值；delete/reveal 标 critical |

---

## 8. §13 验收清单（12 条端到端）

对**全新数据卷**的单镜像容器执行（`bash .dsh-tmp/run-acceptance.sh`）：
**61/61 项断言通过，12 条全部 ✅**

| # | 验收项 | 结果 | 关键证据 |
| :-: | --- | :-: | --- |
| 1 | 空库首次 OAuth 登录 → 系统管理员；第二个用户 → NONE | ✅ 3/3 | 空库 `fch_user` 0 行 → 首个用户 `SYSTEM_ADMIN`；第二个 `NONE` 且看不到任何模块 |
| 2 | 环境 dev→test→prod 继承链；成环配置被拒绝 | ✅ 3/3 | 链建立成功；成环返回 `40000 不允许设置环境继承环`；自引用被拒 |
| 3 | 建应用 + 模板实例化：骨架生成，明文默认值落全局值 | ✅ 3/3 | 2 个变量骨架；`APP_PORT` 全局值 `8080`；加密类型默认值未落库 |
| 4 | 矩阵：全局值 → 各环境继承；prod 覆盖 → 仅 prod 变化 | ✅ 4/4 | 三环境均继承 `8080`；prod 覆盖后仅 prod 为 `9090` |
| 5 | 加密变量：管理员可见 / 操作员脱敏 / 可写不可读 / 创建人可读 / reveal 审计 | ✅ 7/7 | 管理员见明文；操作员见 `••••••` 且 `masked=true`；reveal 他人 → 403；轮换后可写仍不可读；创建人可读自己；reveal 写高危审计 |
| 6 | 共享模块：订阅后实时跟随；应用级覆盖优先；删源模块前列出订阅方 | ✅ 6/6 | 改源值后两应用立即跟随；应用级覆盖只影响本应用；订阅方列表含两应用；有订阅时删除 → `40900` |
| 7 | 流水线：必须带 modules；dotenv 分组与顺序；共享模块展开；继承回退；缺模块 400 | ✅ 7/7 | 见下方真实响应；`modules` 缺省 → 400；模块不存在 → 400 且指明模块名 |
| 8 | Token 无效 401、跨应用模块 403、61 次/分钟 429 | ✅ 3/3 | 无效 Token → 401；跨应用 → 403；第 61 次起 → 429 |
| 9 | 文件 v1–v4 仅留 3 版；指定版本可下；artifacts tar.gz 结构；50MB 拒绝 | ✅ 5/5 | `currentVersion=4`；版本仅剩 v2/v3/v4；`?version=4` 内容正确；tar 内含 `e2e-mod/schema.sql`；51MB → `40000 文件超过 50MB 限制` |
| 10 | 审计：操作入库、7 天清理任务可执行、流水线调用**不**入审计；访问日志 30 天 | ✅ 4/4 | `TOKEN_ISSUE`/`VARIABLE_REVEAL` 入库；无任何 PIPELINE 审计；访问日志有记录；清理任务 `@Scheduled(cron="0 0 3 * * *")` |
| 11 | 备份：包结构正确、密文导出、保留 3 份；恢复三策略 | ✅ 10/10 | manifest 含全部配置段；加密值为密文；无主密钥；Token 仅 hash；保留 3 份；ABORT→40900 / MERGE→0 / OVERWRITE→0，恢复后数据完整 |
| 12 | 单镜像：docker run → health UP、nginx 页面、API 反代、重启后数据仍在 | ✅ 6/6 | liveness/readiness 200；页面 200；API 反代 401（正确）；重启后数据与登录态保留 |

第 7 条实测 dotenv 响应：

```dotenv
# module: e2e-mod
APP_PORT=8080
APP_SECRET=rotated-by-op
OP_OWN_SECRET=op-own
# module: e2e-shared
SHARED_URL=https://v2.internal
```

---

## 9. 单元测试

`./backend/gradlew -p backend test` → **55 tests, 0 failures**：

| 测试类 | 用例数 | 覆盖 |
| --- | ---: | --- |
| `VariableResolverTest` | 13 | **§2.2 变量解析优先级**五种路径全覆盖 |
| `PermissionServiceTest` | 11 | **§6.3 权限矩阵** + §6.4 加密可见性 |
| `EnvironmentServiceTest` | 6 | §5.2 / §2.2 继承链与防环 |
| `CryptoServiceTest` | 7 | §6.5 AES-256-GCM 与主密钥来源 |
| `DotenvCodecTest` | 18 | §4.2 dotenv 转义/分组/last-wins + §10.5 导入解析与类型推断 |

**§2.2 用例清单**：
1. 应用级覆盖·当前环境 优先于模块级覆盖与全局值
2. 无应用级覆盖时取模块级覆盖·当前环境
3a. 当前环境无覆盖时沿父链找模块级覆盖（prod→test）
3b. 同一环境内应用级覆盖优先于模块级覆盖（含父链环境）
3c. 近环境优先于远环境（prod 无 → test 有 → dev 有）
4. 全链无覆盖时取全局值
5. 无覆盖且无全局值 → `found=false`（该 key 不输出）
- 私有模块（applicationId=null）跳过应用级覆盖
- 应用级覆盖只对所属应用生效（纵深防御二次过滤）
- 父链成环时不死循环
- 加密变量 `revealPlain=false` 脱敏 / `true` 解密明文（含覆盖值）
- 变量为 null → `found=false`

**§6.3 用例清单**：
- 系统管理员：可见/可改任意模块，`canRevealEncrypted=true`
- 模块管理员：查看✓ 加密明文✓ 增删改✓ 管理成员✓
- 模块操作员：查看✓ 增删改✓ 但不可看他人加密明文、不可管理成员
- 操作员可读自己创建的加密变量
- 无权限用户（NONE 且非成员）：全部✗
- 未登录用户：全部✗
- 模块成员按模块独立授权（A 模块成员 ≠ B 模块成员）
- `requireCanEdit` / `requireCanManageMembers` 越权抛 403

---

## 10. 已知环境限制（本机）

### 10.1 WSL2 默认 8 秒空闲回收导致容器频繁退出（已修复）

**现象**：每次 PowerShell 调用后 WSL VM 被回收（`uptime` 始终显示 `up 0 min`），
导致 WSL 里的 PostgreSQL 容器随之退出，表现为后端
`HikariPool-1 - Connection is not available, request timed out` / `EOFException`。

**根因**：WSL2 默认 `vmIdleTimeout` 很短（约 8 秒），当宿主机没有任何
WSL 会话时自动关闭虚拟机。

**修复**：在 `%USERPROFILE%\.wslconfig` 写入：

```ini
[wsl2]
vmIdleTimeout=3600000
```

然后执行一次 `wsl --shutdown` 使其生效。修复后跨调用 uptime 持续增长
（11 分钟 → 12 分钟），容器保持 Up。

### 10.2 Docker 与后端分处两处

- PostgreSQL 16 运行在 **WSL 的 Docker** 中（`.ci/docker-compose.dev.yml`）。
- 后端 Spring Boot 用 **Windows 的 `backend/gradlew -p backend bootRun`** 启动，
  通过 `FCLOUD_DB_HOST=172.29.214.70`（WSL IP）访问容器内的 PG。
- **所有脚本统一用 Git Bash 执行**（`D:\Tools\Git\bin\bash.exe`），不用 PowerShell 拼脚本：

```bash
# Git Bash 中
bash .dsh-tmp/ensure-deps.sh          # 确保 WSL 里 PG 就绪
bash .dsh-tmp/start-backend.sh        # 用 gradlew 启动后端（自动带 WSL IP）
bash .dsh-tmp/m2-check.sh             # M2 验收脚本
```

### 10.3 其他

- 本机未部署 GitLab，`FCLOUD_GITLAB_BASE_URL` 仅作 OAuth 客户端配置占位，
  不影响除登录外的接口验证；M2 的登录链路用 `fcloud.dev-login-enabled=true`
  的 `/dev-login` 模拟入口验证（默认关闭，生产镜像不启用）。
