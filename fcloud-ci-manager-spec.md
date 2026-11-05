# fcloud-ci-manager 设计与决策书（最终版）

> 版本：v1.0（2026-10-17）  
> 状态：全部设计分支已闭环，可直接进入实施  
> 用途：本文件是需求访谈全部决议的固化，作为**唯一事实源**交付给本地 WorkBuddy 执行。  
> 阅读方式：先读 §1–§3 建立心智模型，再按 §11 的实施顺序推进；凡本文件未写明的细节，按"简单、显式、可审计"三原则自行决定，但不得违反已定决策。

---

## 0. 一句话定位

**面向 CI 流水线的配置分发中心**：管理端（人）维护应用/模块/环境下的变量与文件；流水线（机器）通过 API Token 拉取指定 应用 + 环境 + 模块 的配置（dotenv）与文件（tar.gz）。

---

## 1. 技术栈（锁定，不得替换）

### 1.1 后端

| 项   | 版本/选型                                                                                                      |
| --- | ---------------------------------------------------------------------------------------------------------- |
| JDK | **17**（源兼容 17；沙箱里若只有高版本 JDK，用 `--release 17` 编译）                                                           |
| 框架  | **Spring Boot 2.7.17**                                                                                     |
| 构建  | **Gradle（Groovy DSL）**；⚠️ 必须用 **Gradle 7.5.1**              |
| ORM | **MyBatis-Plus 3.5.3.2**（`ASSIGN_ID` 雪花主键，BIGINT）                                                          |
| 数据库 | **PostgreSQL 18**（跟随 Alpine `postgresql` 元包；16 亦兼容，见 README D27）                                      |
| 迁移  | **Flyway**（⚠️ Boot 2.7 默认 Flyway 8.5.13 不识别 PG16+，需显式升级 `flyway-core:9.22.3`）                              |
| 缓存  | **进程内缓存**（`LocalCache`：TTL + 固定窗口计数；原 Redis 7 已移除，见 README D26）                                      |
| 认证  | Spring Security + **OAuth2 Client**（GitLab 通用 Provider）+ **JJWT 0.11.5**                                   |
| 文档  | **springdoc-openapi-ui 1.6.15**（SB2.7 适配版本）                                                                |
| 其他  | Lombok、spring-boot-starter-validation、spring-boot-starter-aop、actuator、**commons-compress 1.25.0**（tar.gz） |

### 1.2 前端

**Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus + vuedraggable@4（sortablejs）+ axios**

### 1.3 镜像（单镜像，内置全部组件）

**Alpine 两级镜像：base（OpenJDK 17 + PostgreSQL + nginx）+ 应用层（`supervise.sh` 守护，无 Redis/supervisor/bash/gettext/tzdata）**

---

## 2. 领域模型

```
Application 应用 ──┬── ConfigModule 模块（私有，application_id 非空）
                   │      ├── Variable 变量（模块内 key 唯一）
                   │      │      ├── global_value       全环境同值（可空）
                   │      │      └── override(env[, app]) 环境覆盖 / 订阅应用覆盖
                   │      └── ModuleFile 文件（module+env+file_key 唯一）
                   │             └── FileVersion 版本（保留最近 3 个）
                   └── Subscription 订阅 ──→ Shared Module 共享模块（application_id 为空）
Environment 环境：全局实体，parent_env_id 构成继承链
ModuleMember：用户 × 模块 → MODULE_ADMIN / MODULE_OPERATOR
```

### 2.1 术语

| 术语  | 定义                                               |
| --- | ------------------------------------------------ |
| 应用  | 一个部署单元（如 `payment-service`），拥有多个私有模块与订阅关系        |
| 模块  | 一组变量 + 文件的配置单元；模块分私有（属于某应用）与**共享**（独立库，多个应用订阅）   |
| 环境  | 全局管理（dev/test/staging/prod…），可增删改、可设父环境形成继承链     |
| 全局值 | 变量在所有环境下的同值；与"环境覆盖"二选一                           |
| 覆盖  | 某变量在某环境下的独立值；共享模块还可有"订阅应用级覆盖"                    |
| 订阅  | 应用引用共享模块；**引用制，实时跟随源模块**，不是快照复制                  |
| 模板  | 系统管理员维护的**变量定义集合**（key/类型/备注/默认值/必填），用于拖拽实例化模块骨架 |

### 2.2 变量解析优先级（核心算法，必须严格实现）

对「共享模块 + 应用 A + 环境 E + 变量 V」，按以下顺序取第一个命中的值：

```
1. override(V, E, A)          应用级覆盖 · 当前环境
2. override(V, E, null)       模块级覆盖 · 当前环境
3. 沿 E 的父链逐级重复 1、2    应用级与模块级覆盖，按父→祖父…逐环境依次尝试
4. V.global_value             全局值
5. 缺失                       该 key 不输出
```

- 私有模块跳过第 1 步（无 application_id）。
- 父链遍历需**防环**（visited set），环境配置时也拒绝成环。
- 明文变量直接返回值；加密变量返回**解密后的明文**（仅流水线 API），console 读取时按 §6.4 脱敏。

### 2.3 共享模块的订阅与覆盖

- 订阅关系存 `fch_module_subscription(module_id, application_id)`，删除共享模块时级联删除订阅，**删除前弹窗列出全部订阅方**。
- 应用可对共享模块的个别变量设置**应用级覆盖**（`fch_variable_override.application_id` 非空），未覆盖的变量跟随源模块实时变化。
- 应用拉取时，订阅的共享模块与私有模块地位相同（同一 dotenv 输出），模块顺序按请求参数顺序。

### 2.4 文件规则

- 存储路径：`{storage.root}/{appCode 或 _shared}/{moduleCode}/{envCode}/v{version}/{fileKey}`
- 维度：`module + environment + file_key` 唯一；同名 key 上传即生成新版本
- **fileKey 只能是文件名**：不含 `/`、`\`，不能是 `.` 或 `..`（模块 × 环境已是唯一维度，
  再叠目录会让同一份配置出现两个 key）；需要区分就改文件名或拆模块。
  服务端在 `FileService.requireFlatFileKey()` 统一校验，控制台上传 / MCP `upload_file` 同一条路径。
- **版本保留最近 3 个**，第 4 个上传后自动删最旧（磁盘文件 + 记录）
- 单文件 ≤ **50MB**；单模块所有文件总量 ≤ **500MB**（可配置）
- 文件不做内容加密（备份包含二进制）

### 2.5 模板规则

- 模板 = **变量定义 + 文件定义**，**仅系统管理员**可维护；变量字段：key、类型、备注、默认值、是否必填、排序；文件字段：fileKey、内容、备注
- 模块通过 `template_id` **保持关联**；`template.updated_at > module.template_synced_at` 时 UI 显示"有更新"徽标
- 实例化：复制定义到模块变量/文件；**明文默认值写入 global_value，加密类型的默认值不落库**；文件复制到模块已绑定的每个环境
- **既有私有模块可以绑定/解绑模板**：绑定只补缺失 key，模块已有的同名变量/文件保留自有（`origin = MODULE`），不会被覆盖；解绑默认保留已下发内容（`origin` 改回 MODULE），可选删除
- **共享模块禁止绑定模板**：共享是引用制（订阅方实时跟随），模板是私有快照 + 显式同步，两者语义不同
- **diff 式选择性同步**：变量新增（勾选引入）/ 定义变更（类型/备注/必填，勾选采纳）/ 模板已删除（二选：删除 或 保留为模块自有）；文件新增/更新自动对齐，模板已删除的文件按同样二选处理
- 只有 `origin = TEMPLATE` 的行参与 diff / 同步；**同步永远不覆盖已填的值**
- 模块里的模板副本用 `origin = TEMPLATE` 标记；用户手动上传同名文件即接管为 `MODULE`，后续模板同步不再覆盖
- 支持"从现有模块另存为模板"（仅变量定义）

---


## 3. 数据模型（Flyway `V1__init.sql`）

> 所有时间列用 `TIMESTAMPTZ`（存 UTC，展示 Asia/Shanghai）；主键 BIGINT 雪花。

```sql
-- 1. 用户（email 唯一标识，来自 GitLab）
CREATE TABLE fch_user (
  id            BIGINT PRIMARY KEY,
  email         VARCHAR(255) NOT NULL UNIQUE,
  name          VARCHAR(128),
  nickname      VARCHAR(128),
  avatar_url    VARCHAR(512),
  gitlab_id     VARCHAR(64),
  global_role   VARCHAR(32) NOT NULL DEFAULT 'NONE',  -- SYSTEM_ADMIN | NONE
  last_login_at TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. 环境（全局，自引用继承链）
CREATE TABLE fch_environment (
  id            BIGINT PRIMARY KEY,
  code          VARCHAR(64) NOT NULL UNIQUE,          -- dev/test/prod
  name          VARCHAR(128) NOT NULL,
  color         VARCHAR(32) DEFAULT '#2563eb',
  sort_order    INT NOT NULL DEFAULT 0,
  is_default    BOOLEAN NOT NULL DEFAULT FALSE,
  parent_env_id BIGINT REFERENCES fch_environment(id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. 应用
CREATE TABLE fch_application (
  id          BIGINT PRIMARY KEY,
  code        VARCHAR(128) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  sort_order  INT NOT NULL DEFAULT 0,
  created_by  BIGINT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4. 模块（私有 application_id 非空；共享为空）
CREATE TABLE fch_module (
  id                 BIGINT PRIMARY KEY,
  application_id     BIGINT REFERENCES fch_application(id) ON DELETE CASCADE,
  code               VARCHAR(128) NOT NULL,
  name               VARCHAR(128) NOT NULL,
  description        VARCHAR(512),
  is_shared          BOOLEAN NOT NULL DEFAULT FALSE,
  group_name         VARCHAR(128),                    -- 看板内分组
  template_id        BIGINT,                          -- 软关联 fch_template
  template_synced_at TIMESTAMPTZ,
  sort_order         INT NOT NULL DEFAULT 0,
  created_by         BIGINT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_module_app_code ON fch_module(application_id, code) WHERE application_id IS NOT NULL;
CREATE UNIQUE INDEX uk_module_shared_code ON fch_module(code) WHERE application_id IS NULL;

-- 5. 共享模块订阅
CREATE TABLE fch_module_subscription (
  id             BIGINT PRIMARY KEY,
  module_id      BIGINT NOT NULL REFERENCES fch_module(id) ON DELETE CASCADE,
  application_id BIGINT NOT NULL REFERENCES fch_application(id) ON DELETE CASCADE,
  created_by     BIGINT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (module_id, application_id)
);

-- 6. 模块 × 环境绑定（模块出现在哪些环境）
CREATE TABLE fch_module_env (
  id             BIGINT PRIMARY KEY,
  module_id      BIGINT NOT NULL REFERENCES fch_module(id) ON DELETE CASCADE,
  environment_id BIGINT NOT NULL REFERENCES fch_environment(id) ON DELETE CASCADE,
  UNIQUE (module_id, environment_id)
);

-- 7. 变量（模块内 key 唯一）
CREATE TABLE fch_variable (
  id           BIGINT PRIMARY KEY,
  module_id    BIGINT NOT NULL REFERENCES fch_module(id) ON DELETE CASCADE,
  var_key      VARCHAR(255) NOT NULL,
  value_type   VARCHAR(32) NOT NULL DEFAULT 'PLAIN',  -- PLAIN | ENCRYPTED
  global_value TEXT,                                   -- NULL = 无全局值
  remark       VARCHAR(512),
  is_required  BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order   INT NOT NULL DEFAULT 0,
  created_by   BIGINT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (module_id, var_key)
);

-- 8. 变量覆盖（环境级 / 订阅应用级）
CREATE TABLE fch_variable_override (
  id             BIGINT PRIMARY KEY,
  variable_id    BIGINT NOT NULL REFERENCES fch_variable(id) ON DELETE CASCADE,
  environment_id BIGINT NOT NULL REFERENCES fch_environment(id) ON DELETE CASCADE,
  application_id BIGINT REFERENCES fch_application(id) ON DELETE CASCADE, -- NULL=模块级；非空=订阅应用级
  value          TEXT NOT NULL,
  updated_by     BIGINT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_var_override ON fch_variable_override(variable_id, environment_id, COALESCE(application_id, 0));

-- 9. 文件（逻辑文件）
CREATE TABLE fch_file (
  id             BIGINT PRIMARY KEY,
  module_id      BIGINT NOT NULL REFERENCES fch_module(id) ON DELETE CASCADE,
  environment_id BIGINT NOT NULL REFERENCES fch_environment(id) ON DELETE CASCADE,
  file_key       VARCHAR(255) NOT NULL,
  remark         VARCHAR(512),
  current_version INT NOT NULL DEFAULT 0,
  created_by     BIGINT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (module_id, environment_id, file_key)
);

-- 10. 文件版本
CREATE TABLE fch_file_version (
  id            BIGINT PRIMARY KEY,
  file_id       BIGINT NOT NULL REFERENCES fch_file(id) ON DELETE CASCADE,
  version       INT NOT NULL,
  storage_path  VARCHAR(1024) NOT NULL,   -- 相对 storage.root 的路径
  original_name VARCHAR(512) NOT NULL,
  mime_type     VARCHAR(255),
  size          BIGINT NOT NULL,
  uploaded_by   BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (file_id, version)
);

-- 11. 模块成员（模块级角色）
CREATE TABLE fch_module_member (
  id         BIGINT PRIMARY KEY,
  module_id  BIGINT NOT NULL REFERENCES fch_module(id) ON DELETE CASCADE,
  user_id    BIGINT NOT NULL REFERENCES fch_user(id) ON DELETE CASCADE,
  role       VARCHAR(32) NOT NULL,   -- MODULE_ADMIN | MODULE_OPERATOR
  created_by BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (module_id, user_id)
);

-- 12. 流水线 API Token（每应用一个/多个均可，但交互只给单 Token 入口；SHA-256 存储）
CREATE TABLE fch_api_token (
  id             BIGINT PRIMARY KEY,
  application_id BIGINT NOT NULL REFERENCES fch_application(id) ON DELETE CASCADE,
  name           VARCHAR(128) NOT NULL,
  token_hash     VARCHAR(64) NOT NULL UNIQUE,   -- hex(sha256)
  token_prefix   VARCHAR(16) NOT NULL,          -- 展示用，如 chub_ab12
  expires_at     TIMESTAMPTZ,
  revoked        BOOLEAN NOT NULL DEFAULT FALSE,
  created_by     BIGINT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at   TIMESTAMPTZ
);

-- 13. 模板
CREATE TABLE fch_template (
  id          BIGINT PRIMARY KEY,
  code        VARCHAR(128) NOT NULL UNIQUE,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  created_by  BIGINT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 14. 模板变量定义
CREATE TABLE fch_template_var (
  id            BIGINT PRIMARY KEY,
  template_id   BIGINT NOT NULL REFERENCES fch_template(id) ON DELETE CASCADE,
  var_key       VARCHAR(255) NOT NULL,
  value_type    VARCHAR(32) NOT NULL DEFAULT 'PLAIN',
  remark        VARCHAR(512),
  default_value TEXT,
  is_required   BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order    INT NOT NULL DEFAULT 0,
  UNIQUE (template_id, var_key)
);

-- 14.1 模板文件定义（V10）
CREATE TABLE fch_template_file (
  id            BIGINT PRIMARY KEY,
  template_id   BIGINT NOT NULL REFERENCES fch_template(id) ON DELETE CASCADE,
  file_key      VARCHAR(255) NOT NULL,
  remark        VARCHAR(512),
  storage_path  VARCHAR(1024) NOT NULL,
  original_name VARCHAR(512) NOT NULL,
  mime_type     VARCHAR(255),
  size          BIGINT NOT NULL,
  sha256        VARCHAR(64),
  created_by    BIGINT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (template_id, file_key)
);

-- 14.2 来源标记（V10）：变量 / 文件
--   fch_variable.origin VARCHAR(16) NOT NULL DEFAULT 'MODULE'
--   fch_file.origin     VARCHAR(16) NOT NULL DEFAULT 'MODULE'
--   fch_file_version.sha256 VARCHAR(64)
-- MODULE = 模块自有；TEMPLATE = 模板下发。diff/同步只处理 TEMPLATE。

-- 15. 审计日志（保留 7 天）
CREATE TABLE fch_audit_log (
  id          BIGINT PRIMARY KEY,
  user_id     BIGINT,
  user_email  VARCHAR(255),
  action      VARCHAR(64) NOT NULL,
  target_type VARCHAR(64),
  target_id   VARCHAR(64),
  detail      TEXT,                    -- JSON 字符串
  is_critical BOOLEAN NOT NULL DEFAULT FALSE,
  ip          VARCHAR(64),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON fch_audit_log(created_at);

-- 16. 流水线访问日志（保留 30 天）
CREATE TABLE fch_access_log (
  id             BIGINT PRIMARY KEY,
  token_id       BIGINT,
  application_id BIGINT,
  endpoint       VARCHAR(512),
  method         VARCHAR(16),
  status         INT,
  duration_ms    BIGINT,
  ip             VARCHAR(64),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_access_created ON fch_access_log(created_at);

-- 17. 备份记录
CREATE TABLE fch_backup_record (
  id         BIGINT PRIMARY KEY,
  file_name  VARCHAR(512) NOT NULL,
  file_path  VARCHAR(1024) NOT NULL,
  size       BIGINT,
  type       VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',  -- MANUAL | SCHEDULED
  created_by BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 4. 流水线 API 契约（机器侧，只读）

### 4.1 鉴权

```
Authorization: Bearer chub_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

- Token 创建时完整展示一次；库中只存 `hex(sha256(token))` 与前缀
- 进程内缓存校验结果（TTL 5 分钟），吊销时同步删除缓存
- Token 作用域 = 所属应用（含其订阅的共享模块）；**全环境通用**（不做按环境隔离）
- 401：缺失/无效/过期/吊销；403：模块不属于该应用；429：限流

### 4.2 拉取变量（dotenv）

```
GET /api/v1/pipeline/config?application=payment-service&environment=prod&modules=db-config,common-notify
```

| 参数          |  必填 | 说明                                 |
| ----------- | :-: | ---------------------------------- |
| application |  ✓  | 应用 code                            |
| environment |  ✓  | 环境 code                            |
| modules     |  ✓  | 逗号分隔的模块 code；**必须显式指定**，缺省/空 → 400 |

- 响应：`Content-Type: text/plain; charset=utf-8`，按请求参数顺序逐模块输出
- 每个模块前输出 `# module: {code}` 注释行；变量按 `sort_order`
- 同名 key 后出现的模块覆盖先出现的（last-wins，按 `# module` 分组可见）
- 值转义：含空格/`#`/单引号/换行/`$` 时用单引号包裹，内部单引号转 `'\''`
- 加密变量输出**解密明文**
- **服务端不得将任何变量值写入日志**（含 debug 级别）
- 模块未绑定目标环境或不存在 → 400 并指明哪个模块

示例响应：

```dotenv
# module: db-config
DB_HOST=10.0.2.9
DB_PORT=5432
DB_PASSWORD='p@ss word'
# module: common-notify
NOTIFY_URL=https://im.internal/hook
```

### 4.3 批量下载文件

```
POST /api/v1/pipeline/artifacts
Body: { "environment": "prod", "modules": ["db-config"], "files": ["schema.sql"] }
```

- `files` 省略 = 该模块在该环境下的全部文件
- 响应：`application/gzip` 流式 tar.gz，目录结构 `{moduleCode}/{fileKey}`
- 未找到文件 → 404；`modules` 必填

### 4.4 单文件下载

```
GET /api/v1/pipeline/files/{moduleId}/{fileId}?version=3
```

- `version` 省略 = 最新版本；响应 `application/octet-stream`，`Content-Disposition` 带原始文件名

---

## 5. 管理端 API 清单（console，JWT Cookie）

> 统一前缀 `/api/console`，响应包装 `{ code: 0, message: "ok", data: ... }`，分页 `{ records, total, page, size }`。

### 5.1 认证 / 用户

| 方法   | 路径                 | 说明                   | 权限    |
| ---- | ------------------ | -------------------- | ----- |
| GET  | `/auth/me`         | 当前用户信息               | 登录    |
| PUT  | `/auth/me`         | 改昵称（仅 nickname）      | 登录    |
| POST | `/auth/logout`     | 清 Cookie + 黑名单 | 登录    |
| GET  | `/users?keyword=`  | 用户列表                 | 系统管理员 |
| PUT  | `/users/{id}/role` | 设全局角色 `{role}`       | 系统管理员 |
| GET  | `/users/options`   | 成员选择器用（id/姓名/邮箱/头像）  | 登录    |

### 5.2 环境

| 方法     | 路径                   | 说明                          | 权限    |
| ------ | -------------------- | --------------------------- | ----- |
| GET    | `/environments`      | 列表（含 parent）                | 登录    |
| POST   | `/environments`      | 新建                          | 系统管理员 |
| PUT    | `/environments/{id}` | 改（含 parent、color、isDefault） | 系统管理员 |
| DELETE | `/environments/{id}` | 删（级联清绑定/覆盖/文件），二次确认         | 系统管理员 |
| PUT    | `/environments/sort` | `{ids:[]}` 排序               | 系统管理员 |

### 5.3 应用 / 看板

| 方法     | 路径                    | 说明                                  | 权限    |
| ------ | --------------------- | ----------------------------------- | ----- |
| GET    | `/applications/board` | 看板数据：应用+模块(含分组)+共享库+订阅关系            | 登录    |
| GET    | `/applications`       | 简表                                  | 登录    |
| POST   | `/applications`       | 新建                                  | 系统管理员 |
| PUT    | `/applications/{id}`  | 改                                   | 系统管理员 |
| DELETE | `/applications/{id}`  | body `{confirmName}`，级联删模块/Token/订阅 | 系统管理员 |
| PUT    | `/applications/sort`  | 排序                                  | 系统管理员 |

### 5.4 模块 / 订阅 / 分组 / 复制

| 方法     | 路径                                               | 说明                                | 权限     |
| ------ | ------------------------------------------------ | --------------------------------- | ------ |
| POST   | `/applications/{appId}/modules`                  | 建私有模块（可带 `templateId` 实例化）        | 系统管理员  |
| GET    | `/applications/{appId}/modules`                  | 应用下模块                             | 登录     |
| GET    | `/shared-modules`                                | 共享模块列表（含订阅数）                      | 登录     |
| POST   | `/shared-modules`                                | 建共享模块                             | 系统管理员  |
| GET    | `/modules/{id}`                                  | 详情（含矩阵数据、成员、文件、模板状态）              | 成员/管理员 |
| PUT    | `/modules/{id}`                                  | 改名称/描述/绑定环境                       | 系统管理员  |
| DELETE | `/modules/{id}`                                  | 删；共享模块返回订阅方列表                     | 系统管理员  |
| PUT    | `/modules/{id}/group`                            | `{groupName}` 拖拽改分组               | 系统管理员  |
| PUT    | `/modules/sort`                                  | 模块排序                              | 系统管理员  |
| POST   | `/modules/{id}/copy`                             | `{targetAppId, withValues}` 跨应用复制 | 系统管理员  |
| POST   | `/applications/{appId}/subscriptions`            | `{moduleId}` 订阅（拖拽）               | 系统管理员  |
| DELETE | `/applications/{appId}/subscriptions/{moduleId}` | 取消订阅（可撤销）                         | 系统管理员  |
| POST   | `/modules/{id}/subscriptions`                    | `{appIds}` 批量订阅，返回 `{requested,changed,skipped}` | 登录     |
| DELETE | `/modules/{id}/subscriptions`                    | `{appIds}` 批量取消订阅（DELETE 带 body） | 登录     |
| GET    | `/modules/{id}/subscribers`                      | 共享模块订阅方                           | 登录     |

### 5.5 变量（矩阵）

| 方法     | 路径                                              | 说明                                                            | 权限     |
| ------ | ----------------------------------------------- | ------------------------------------------------------------- | ------ |
| GET    | `/modules/{id}/variables`                       | 矩阵：定义 + 各环境解析结果（加密值脱敏 `••••`）                                 | 查看     |
| POST   | `/modules/{id}/variables`                       | 新增定义                                                          | 编辑     |
| PUT    | `/modules/{id}/variables/{varId}`               | 改定义（key 不可改，可改类型/备注/必填）                                       | 编辑     |
| DELETE | `/modules/{id}/variables/{varId}`               | 删定义（连带覆盖）                                                     | 编辑     |
| PUT    | `/modules/{id}/variables/sort`                  | `{ids:[]}` 拖拽排序                                               | 编辑     |
| PUT    | `/modules/{id}/variables/{varId}/value`         | `{scope: GLOBAL\|ENV, environmentId?, applicationId?, value}` | 编辑     |
| DELETE | `/modules/{id}/variables/{varId}/override`      | 清覆盖（回退继承）                                                     | 编辑     |
| POST   | `/modules/{id}/variables/import`                | `{dotenvText, applyTo, environmentId?}` 粘贴导入                  | 编辑     |
| GET    | `/modules/{id}/variables/export?environmentId=` | dotenv 文本（加密值按权限脱敏）                                           | 查看     |
| POST   | `/modules/{id}/variables/{varId}/reveal`        | `{environmentId?}` 返回明文，**写高危审计**                             | 见 §6.4 |

### 5.6 文件

| 方法     | 路径                                   | 说明                                                   | 权限 |
| ------ | ------------------------------------ | ---------------------------------------------------- | -- |
| GET    | `/modules/{id}/files?environmentId=` | 文件列表（含版本数）                                           | 查看 |
| POST   | `/modules/{id}/files`                | multipart：`file,fileKey,environmentId,remark` 上传即新版本 | 编辑 |
| GET    | `/files/{fileId}/versions`           | 版本列表                                                 | 查看 |
| GET    | `/files/{fileId}/download?version=`  | 下载                                                   | 查看 |
| DELETE | `/files/{fileId}`                    | 删文件及全部版本                                             | 编辑 |
| PUT    | `/files/{fileId}`                    | 改备注                                                  | 编辑 |

### 5.7 成员（拖拽授权）

| 方法     | 路径                                 | 说明               | 权限    |
| ------ | ---------------------------------- | ---------------- | ----- |
| GET    | `/modules/{id}/members`            | 成员列表             | 查看    |
| POST   | `/modules/{id}/members`            | `{userId, role}` | 模块管理员 |
| PUT    | `/modules/{id}/members/{memberId}` | `{role}`         | 模块管理员 |
| DELETE | `/modules/{id}/members/{memberId}` | 移除（可撤销）          | 模块管理员 |

### 5.8 模板（仅系统管理员）

| 方法     | 路径                               | 说明                                                                                |
| ------ | -------------------------------- | --------------------------------------------------------------------------------- |
| GET    | `/templates`                     | 列表（含变量数）                                                                          |
| POST   | `/templates`                     | 新建（带 vars）                                                                        |
| GET    | `/templates/{id}`                | 详情                                                                                |
| PUT    | `/templates/{id}`                | 改（带 vars 全量保存）                                                                    |
| DELETE | `/templates/{id}`                | 删（模块 template_id 置空）                                                              |
| POST   | `/templates/{id}/instantiate`    | `{applicationId, code, name}`                                                     |
| GET    | `/modules/{id}/template-diff`    | `{added, changed, removed, conflicts, files, removedFiles}`；只比对模板来源项                |
| POST   | `/modules/{id}/template-sync`    | `{acceptAdded:[], acceptChanged:[], removedKeys:[], removedFileKeys:[], removedAction: DELETE\|KEEP}` |
| POST   | `/modules/{id}/save-as-template` | `{code, name}` 从模块反哺模板（仅变量）                                                          |
| GET    | `/modules/{id}/template-preview?templateId=` | 绑定前预览：新增/冲突/文件状态                                                  |
| POST   | `/modules/{id}/template-bind`    | `{templateId}` 绑定已有私有模块；同名项保留自有                                          |
| POST   | `/modules/{id}/template-unbind`  | `{deleteContent:false}` 解绑；默认保留内容                                         |
| POST   | `/templates/{id}/files`          | multipart：`file, fileKey, remark?` 上传/替换模板文件                                  |
| DELETE | `/templates/{id}/files/{fileId}` | 删除模板文件                                                                           |
| POST   | `/templates/{id}/bind-preview`   | `{moduleCode, applicationIds?}` 批量绑定预览                                       |
| POST   | `/templates/{id}/bind-batch`     | `{moduleCode, applicationIds?}` 批量绑定已有模块                                    |
| GET    | `/templates/{id}/bindings`       | 已绑定模块清单（应用 / 模块 / 上次同步时间 / 是否待同步）                            |

### 5.9 Token / 审计 / 访问日志 / 备份 / 工作台

| 方法     | 路径                                                            | 说明                                                   | 权限    |
| ------ | ------------------------------------------------------------- | ---------------------------------------------------- | ----- |
| GET    | `/applications/{appId}/tokens`                                | Token 列表（脱敏）                                         | 系统管理员 |
| POST   | `/applications/{appId}/tokens`                                | `{name, expiresAt?}` → 返回明文一次                        | 系统管理员 |
| DELETE | `/tokens/{id}`                                                | 吊销                                                   | 系统管理员 |
| GET    | `/audit-logs?action=&critical=&userId=&from=&to=&page=&size=` | 审计查询                                                 | 系统管理员 |
| GET    | `/access-logs?appId=&from=&to=&page=&size=`                   | 访问日志                                                 | 系统管理员 |
| GET    | `/backups`                                                    | 备份记录                                                 | 系统管理员 |
| POST   | `/backups`                                                    | 立即备份                                                 | 系统管理员 |
| GET    | `/backups/{id}/download`                                      | 下载备份包                                                | 系统管理员 |
| POST   | `/backups/restore`                                            | multipart `file + strategy(ABORT\|MERGE\|OVERWRITE)` | 系统管理员 |
| GET    | `/backups/remote`                                             | 制品库（Gitee 仓库）上可用的备份列表                                | 系统管理员 |
| POST   | `/backups/{id}/upload`                                        | 手动重试上传到制品库                                           | 系统管理员 |
| POST   | `/backups/restore-remote`                                     | `{remotePath, strategy}` 从制品库恢复                     | 系统管理员 |
| GET    | `/settings/gitee`                                             | 制品库配置（token 加密存储，只回显尾 4 位）                            | 系统管理员 |
| PUT    | `/settings/gitee`                                             | 保存 `{enabled, baseUrl, owner, repo, branch, dir, token}` | 系统管理员 |
| POST   | `/settings/gitee/test`                                        | 校验 token 与仓库可读性                                      | 系统管理员 |
| GET    | `/dashboard/todos`                                            | 待办聚合（见 §9.3）                                         | 登录    |

**制品库异地备份**：开关打开后，每份本地备份成功后额外推一份到制品库。
内网的 Gitee 制品库对外是 **JFrog Artifactory V7 兼容接口**，因此按 Artifactory 协议实现：

| 动作 | 接口 |
| --- | --- |
| 上传 | `PUT {base}/artifactory/{repoKey}/{dir}/{fileName}`（body 为文件二进制，流式） |
| 下载 | `GET {base}/artifactory/{repoKey}/{dir}/{fileName}` |
| 删除 | `DELETE {base}/artifactory/{repoKey}/{dir}/{fileName}` |
| 清单 | `GET {base}/api/storage/{repoKey}/{dir}?list&deep=0&listFolders=0` |
| 探活 | `GET {base}/api/system/ping` + `GET {base}/api/storage/{repoKey}` |

鉴权：配了用户名走 Basic（用户名 + Token），否则 `Authorization: Bearer {token}`。
上传用 `FileSystemResource` 流式，不把整包读进内存；Artifactory 本身不限单文件，代码里只兜底 2GB。

三个设计约定：

1. **本地副本始终保留**，上传失败只更新 `remote_status=FAILED` + `remote_error`，不改变备份本身的 `SUCCESS`；
2. **制品库份数跟随 `fcloud.backup.retain`**（默认 3 份），每次上传成功后自动清理更旧的包（手动重试也走同一路径）；
3. 恢复时先把包下载到临时文件，再走与上传恢复相同的后台任务，因此两者共用冲突检测与策略。

---

## 6. 认证、会话与权限

### 6.1 GitLab OAuth（自建实例）

- 配置：`FCLOUD_GITLAB_BASE_URL`（如 `https://gitlab.your.com`）、`FCLOUD_GITLAB_CLIENT_ID`、`FCLOUD_GITLAB_CLIENT_SECRET`
- scope 仅 `read_user`；授权入口 `GET /oauth2/authorization/gitlab`；回调 `GET /oauth/gitlab/callback`（Spring Security OAuth2 Client 处理）
- 用户信息映射：`email`（唯一键，去重）、`name`（姓名）、`username` → `nickname`、`avatar_url`、`id` → `gitlab_id`
- **email 为 null 时**（GitLab 隐私设置）：回退 `{username}@{gitlab host 的域名}`，并记录日志
- **首次登录自动建号**；**用户表为空时，第一个登录者自动获得 `SYSTEM_ADMIN`**；此后新用户默认 `NONE`（无任何模块可见）
- 登录成功后：签发 JWT → 写 Cookie → 302 到 `/`（前端 `?login=success` 可省略）

### 6.2 会话

- JWT（HS256）放 **httpOnly + SameSite=Lax + Secure（可配置）Cookie**，名 `fcloud_token`
- 有效期：默认 **24h**；登录页勾选"记住我" → **7 天**（由前端传参给后端登录跳转不可行时，用独立入口 `/oauth2/authorization/gitlab?remember=1`，SuccessHandler 读取 `remember` 请求参数）
- 注销：删除 Redis 中不存在亦可，采用**Redis 黑名单**（key=`jwt:blacklist:{jti}`，TTL=剩余有效期）
- JWT claims：`sub`=email、`uid`、`name`、`role`（global）、`jti`、`exp`

### 6.3 角色与权限矩阵

| 能力              | 系统管理员 | 模块管理员 | 模块操作员 | 无权限用户 |
| --------------- | :---: | :---: | :---: | :---: |
| 查看模块配置（元数据）     |   ✓   |   ✓   |   ✓   |   ✗   |
| 查看他人加密变量明文      |   ✓   |   ✓   |   ✗   |   ✗   |
| 增删改变量 / 文件      |   ✓   |   ✓   |   ✓   |   ✗   |
| 管理模块成员          |   ✓   |   ✓   |   ✗   |   ✗   |
| 创建/删除 应用、模块     |   ✓   |   ✗   |   ✗   |   ✗   |
| 环境增删改           |   ✓   |   ✗   |   ✗   |   ✗   |
| API Token 签发/吊销 |   ✓   |   ✗   |   ✗   |   ✗   |
| 用户管理 / 全局角色     |   ✓   |   ✗   |   ✗   |   ✗   |
| 审计日志 / 访问日志     |   ✓   |   ✗   |   ✗   |   ✗   |
| 备份 / 恢复         |   ✓   |   ✗   |   ✗   |   ✗   |
| 模板库维护           |   ✓   |   ✗   |   ✗   |   ✗   |

- **模块成员按模块独立授权**；无环境级权限（授权即覆盖该模块全部环境）
- 模块可见性：系统管理员可见全部；其他用户仅可见自己是成员的模块（含其共享订阅上下文）

### 6.4 加密变量可见性

- 明文可见：**系统管理员 ∪ 模块管理员 ∪ 该变量的创建人本人**
- 其他操作员：列表/矩阵中**永远脱敏**（`••••••`），可写入新值（轮换密码），但不可读取旧值
- 每次 `reveal` 明文 → 写**高危审计**（操作人、变量、环境、时间）
- console 的 dotenv 导出：对无权限用户，加密值输出占位符 `__MASKED__`；对有权用户输出明文并写高危审计

### 6.5 加密实现

- AES-256-GCM；主密钥来源优先级：`FCLOUD_MASTER_KEY`（Base64 32 字节）→ `/data/config-hub/master.key` 文件 → 自动生成并落盘（权限 600）并打警告
- 存储格式：Base64(IV(12B) + ciphertext + tag)
- 主密钥**绝不进入**备份包与日志；文档明示：换 key 后旧备份的加密值不可恢复

---

## 7. 审计日志

| 动作              | 记录             |   高危  |
| --------------- | -------------- | :---: |
| 登录 / 登出         | 用户、IP          |       |
| 应用/模块/环境/模板 增删改 | 变更前后摘要         | 删除=高危 |
| 变量/文件 增删改       | key/文件名、模块、环境  | 删除=高危 |
| 成员授权变更          | 被授权人、角色        |       |
| Token 签发/吊销     | Token 前缀       | 吊销=高危 |
| 备份创建/下载/恢复      | 文件名、策略         | 恢复=高危 |
| 查看加密变量明文        | 变量、环境          |   ✓   |
| 流水线 API 调用      | **不进审计**，进访问日志 |       |

- 页面：系统管理员可见，按用户/动作/高危/时间筛选，分页
- 实现：Spring AOP `@Audited(action, targetType, critical)` 注解 + 手动 `auditService.log()` 补充；异步写库
- **保留 7 天**：每日 03:00 定时 `DELETE WHERE created_at < now() - interval '7 days'`；不用 Redis TTL

## 8. 访问日志

- 记录：token_id、application_id、endpoint、method、status、duration_ms、ip
- 异步写入，**保留 30 天**，每日 03:30 清理
- 页面：仅系统管理员

---

## 9. 备份与恢复

### 9.1 备份内容（`.tar.gz`）

```
manifest.json     # 全量配置：users(不含密码)/envs/apps/modules/subscriptions/
                  # module_envs/variables(密文)/overrides(密文)/files(元数据)/
                  # members/templates/template_vars/tokens(仅 hash)/backup_at/version
files/            # 全部文件版本二进制，路径 {moduleId}/{fileId}/v{n}/{original}
```

- 加密变量导出**密文**，不含主密钥
- 不含审计日志、访问日志（非配置数据）
- 文件名：`fcloud-backup-YYYYMMDD-HHmmss.tar.gz`

### 9.2 触发与保留

- 手动：系统管理员点"立即备份" → 记录 + 可下载
- 定时：每日 **02:00**，输出到 `{backup.dir}`，**保留最近 3 份**（超出删除文件+记录）
- 恢复：上传备份包 + 策略
  - `ABORT`（默认）：发现任何 code 冲突 → 整体回滚报错，列出冲突项
  - `MERGE`：按自然键（app.code / env.code / module(app,code) / var(module,key)）跳过已存在，仅补缺失
  - `OVERWRITE`：清空全部业务表后导入（前端二次确认 + 输入 `OVERWRITE`；审计高危）
- 恢复使用 manifest 中的原始 ID 插入（BIGINT，无序列冲突）；文件解包到 storage 对应路径
- 恢复后清理 Redis 中 Token 缓存

---

## 10. 前端信息架构与交互规范

### 10.1 视觉规范

- 主色 `#2563eb`；页面底 `#f5f6f8`；卡片纯白、1px 边框 `#e5e7eb`、圆角 **8px**、弱阴影（`0 1px 3px rgba(0,0,0,.04)`）
- Element Plus 主题变量覆盖：`--el-color-primary: #2563eb`、`--el-border-radius-base: 8px`、`--el-border-radius-small: 6px`
- 左侧导航 220px（白底，选中项浅蓝底 12% + 左侧 3px 主色条）；顶栏 56px（面包屑 + 用户头像菜单）
- 表格行高 44px，紧凑但留白；表单错误红 `#dc2626`；必填星号 `*`
- 无暗黑模式；桌面优先（≥1280px 最佳），<1024px 侧栏折叠为图标
- 图标用 `@element-plus/icons-vue`；中文界面

### 10.2 页面清单与路由

| 路由               | 页面       | 关键内容                           |
| ---------------- | -------- | ------------------------------ |
| `/login`         | 登录       | GitLab 登录按钮、"记住我"勾选、首次登录即管理员提示 |
| `/`              | 工作台      | 待办卡片（见 10.3）                   |
| `/applications`  | 应用看板     | 见 10.4                         |
| `/modules/:id`   | 模块详情     | 变量矩阵 + 文件 + 成员 + 模板同步条         |
| `/templates`     | 模板库      | 模板 CRUD + 变量定义编辑 + 拖拽预览        |
| `/environments`  | 环境管理     | 列表 + 抽屉编辑 + 继承链选择 + 排序         |
| `/users`         | 用户管理     | 列表 + 全局角色 + 各模块授权概览            |
| `/tokens`        | Token 管理 | 应用下拉 + 签发/吊销（明文仅弹一次）           |
| `/audit`         | 审计日志     | 筛选 + 高危红色标记                    |
| `/backups`       | 备份恢复     | 记录 + 立即备份 + 上传恢复（策略选择）         |
| `/profile`       | 个人设置     | 昵称编辑、GitLab 信息、退出              |
| `/pipeline-docs` | 流水线接入    | curl 示例（3 个接口）、错误码、dotenv 说明   |

### 10.3 工作台（待办驱动）

首屏不是数字概览，而是四类待办（有则显示，无则显示"一切正常"空态）：

1. 缺必填变量的「模块 × 环境」列表（红色，点击跳矩阵）
2. 模板待同步的模块列表
3. 7 天内到期的 API Token
4. 最近 24h 高危操作（审计入口）

### 10.4 应用看板（核心页）

布局：左侧固定栏（宽 280px）= 共享模块库 + 模板库快捷入口；右侧应用卡片墙（响应式 2–3 列）。

- 应用卡片：名称/code/描述、操作菜单、模块 chip 按 `groupName` 分组显示、订阅的共享模块 chip（虚线边框 + 来源图标）
- 拖拽场景（仅系统管理员可拖，其他人静态展示）：
  1. **共享模块 → 应用卡片** = 订阅；拖入时卡片高亮，松手 toast"已订阅，撤销"
  2. **模块 chip → 另一应用卡片** = 复制，弹窗选「仅骨架 / 连同值」
  3. **模块 chip → 同卡片内分组** = 改分组；**→ 新建分组**区域 = 新建并归入
  4. **应用卡片拖拽** = 排序
- 落点反馈：合法目标蓝色虚线描边，非法置灰；松手前 ghost 显示结果
- 危险拖拽（取消订阅）走 toast 撤销，不弹确认框

### 10.5 模块详情：变量矩阵（核心交互）

```
[模板有更新 ⚠ 同步]                                    [粘贴导入] [+ 变量] [复制 dotenv]
┌────┬──────────────┬──────┬────────┬──────────┬────────┬────────┐
│ ⋮  │ 变量          │ 类型 │ 备注    │ 全局值    │ dev    │ prod   │
├────┼──────────────┼──────┼────────┼──────────┼────────┼────────┤
│ ⋮  │ DB_HOST *    │ 明文 │ 数据库  │ 10.0.1.5 │ ↳继承  │ 10.0.2.9│ ← prod 覆盖
│ ⋮  │ DB_PASSWORD *│ 加密 │ 密码    │ ••••••   │ ↳继承  │ •••••• │ [查看]
└────┴──────────────┴──────┴────────┴──────────┴────────┴────────┘
```

- 列 = 全局值 + 模块绑定的环境（按 sort_order）
- 单元格状态：`值`（本环境覆盖，右上角小圆点标记）/`↳继承`（灰字，hover 提示来源环境）/`空`（未配置）
- 点击单元格 → 行内编辑（Enter 保存，Esc 取消）；清空覆盖 → 回退继承
- 加密值显示 `••••••` + 查看按钮（仅有权者，触发 reveal 接口 + 审计）
- 行首拖拽柄排序（影响 dotenv 输出顺序）；必填变量缺失的环境列头显示红色计数徽标
- 粘贴导入：抽屉 textarea 粘贴 dotenv → 预览解析结果（key/值/类型推断：`PASSWORD|SECRET|TOKEN|KEY` 结尾默认加密）→ 选择应用到「全局」或「指定环境」→ 确认
- 复制 dotenv：按环境生成，加密值按权限处理（§6.4）
- 文件区：拖放上传全区（OS 文件拖入即传，50MB 限制提示），文件行内展开版本历史
- 成员区：右侧"用户面板"拖人到成员列表 → 选角色（操作员/管理员）→ 完成；移除成员 toast 撤销
- 模板条：有更新时顶部黄色条，点开 diff 抽屉（新增/变更/删除三类勾选）

### 10.6 拖拽设计五准则（评审任何新拖拽时使用）

1. **落点必须有语义**（顺序/归属/成员/拷贝），否则用点击
2. 源与目标必须同屏可见
3. 拖拽是**捷径**，必须存在等价的非拖拽入口（菜单/表单）
4. 拖拽即预览（ghost + 合法目标高亮 + 非法置灰）
5. 危险拖拽给**撤销**（toast），不给弹窗确认

> 说明：模板实例化默认入口是**点击**；"拖模板到变量列表的指定位置"作为可选快捷方式保留（落点=插入位置）。

### 10.7 其他交互约定

- 所有编辑用**右侧 Drawer**（宽 480/640），不跳页
- 删除确认：普通项一次确认；应用删除需**输入应用名**；恢复选 OVERWRITE 需输入 `OVERWRITE`
- 空态：插画 + 一句话 + 主按钮
- 加载：骨架屏；操作后 `ElMessage` 反馈；失败展示后端 message

---

## 11. 工程结构与部署

### 11.1 仓库结构

```
fcloud-ci-manager/
├── backend/                     # 后端工程（独立 Gradle 根）
│   ├── build.gradle             # Spring Boot 2.7.17，Java 17
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat
│   ├── src/main/java/ci/fcloud/manager/
│   │   ├── FcloudCiManagerApplication.java
│   │   ├── config/              # Security/OAuth2/Redis/Web/OpenAPI/Properties
│   │   ├── common/              # ApiResponse/异常/Crypto/Audited 注解
│   │   ├── auth/                # JWT/Cookie/OAuth SuccessHandler
│   │   ├── user/ env/ app/ module/ template/ token/
│   │   ├── pipeline/            # 流水线 API + Token 过滤器 + 限流 + 访问日志
│   │   ├── audit/               # 审计切面 + 查询 + 清理
│   │   └── backup/              # 导出/恢复/定时任务
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/V1__init.sql
│       └── static/              # 前端构建产物（仅 -PwithFrontend 时拷入）
├── frontend/                    # 前端工程（Vue3 + Vite + TS）
├── .ci/                         # 构建与部署（镜像、进程管理、本地依赖）
│   ├── base.Dockerfile          # 运行时底座：Node20 + JDK17 + nginx + PostgreSQL
│   ├── Dockerfile               # 应用镜像（基于 base，补 supervisor/redis/产物）
│   ├── supervisord.conf         # 进程定义：PG → Redis → nginx → app
│   ├── nginx.conf.template      # server 块模板（entrypoint 渲染）
│   ├── entrypoint.sh            # initdb / 建库建用户 / 渲染 nginx 配置
│   ├── build.sh                 # base 与应用镜像的构建脚本
│   └── docker-compose.dev.yml   # 本地 PG + Redis
├── docs/
└── README.md
```

### 11.2 单镜像组成（交付物 = 一个镜像）

```
fcloud-ci-manager:latest
├── supervise.sh（PID 1，POSIX sh）
│   ├── nginx               :8080  对外唯一端口，静态资源 + 反代 /api /oauth /oauth2 /actuator
│   ├── postgresql 18       :5432  只听 127.0.0.1，数据 /data/config-hub/pg
│   └── java -jar app       :8081  内部端口，Spring Boot（Flyway 自动迁移）
└── 缓存：进程内 LocalCache（无 redis 进程）
└── /data/config-hub（PVC 挂载点）
    ├── pg/            PostgreSQL 数据
    ├── storage/       模块文件（§2.4 路径规范）
    ├── backups/       备份包（保留 3 份）
    └── master.key     加密主密钥（未提供环境变量时自动生成）
```

- 启动顺序由 `supervise.sh` + `entrypoint.sh` 保证：PG 就绪（`pg_isready`）→ nginx → App；App 内部 Flyway 迁移
- PG 首次启动自动 `initdb`、建库建用户（entrypoint 脚本判断 `/data/config-hub/pg/PG_VERSION` 是否存在）
- nginx 配置由模板渲染（上游端口取 `FCLOUD_SERVER_PORT`，容器内默认 8081）；SPA fallback `try_files $uri $uri/ /index.html`
- 健康检查：`/actuator/health/liveness`、`/actuator/health/readiness`（含 DB）
- 时区：容器 OS 层跑 UTC（不装 tzdata）；JVM 用 `-Duser.timezone=Asia/Shanghai` 展示，存储 UTC

### 11.3 环境变量

| 变量                                       |  必填 | 默认                                    | 说明              |
| ---------------------------------------- | :-: | ------------------------------------- | --------------- |
| `FCLOUD_GITLAB_BASE_URL`                 |  ✓  | —                                     | 自建 GitLab 根地址   |
| `FCLOUD_GITLAB_CLIENT_ID` / `_SECRET`    |  ✓  | —                                     | OAuth 应用凭据      |
| `FCLOUD_OAUTH_REDIRECT_URI`              |     | `{baseUrl}/oauth/gitlab/callback`     | 回调地址            |
| `FCLOUD_JWT_SECRET`                      |  ✓  | —                                     | ≥32 字符          |
| `FCLOUD_MASTER_KEY`                      |     | 自动生成                                  | Base64 32 字节    |
| `FCLOUD_DB_NAME` / `_USER` / `_PASSWORD` |     | `fcloud_ci_hub` / `fcloud` / `fcloud` | 内置 PG           |
| `FCLOUD_SERVER_PORT`                     |     | `8081`（容器内）                           | Spring Boot 端口  |
| `FCLOUD_STORAGE_ROOT`                    |     | `/data/config-hub/storage`            | 文件根目录           |
| `FCLOUD_BACKUP_DIR` / `_RETAIN`          |     | `/data/config-hub/backups` / `3`      | 备份目录/保留份数       |
| `FCLOUD_AUDIT_RETAIN_DAYS`               |     | `7`                                   | 审计保留            |
| `FCLOUD_ACCESS_LOG_RETAIN_DAYS`          |     | `30`                                  | 访问日志保留          |
| `FCLOUD_RATE_LIMIT_PER_MINUTE`           |     | `60`                                  | 流水线限流           |
| `FCLOUD_COOKIE_SECURE`                   |     | `false`                               | HTTPS 部署时置 true |

### 11.4 K8s 部署要点

- **单副本（replicas: 1）**，`strategy: Recreate`；多副本会导致 PG 数据分裂（明确写入文档）
- PVC 挂 `/data/config-hub`；建议 ≥10Gi
- readinessProbe：`/actuator/health/readiness`，initialDelay 40s；liveness：60s
- 资源建议：requests 1C/1Gi，limits 2C/2.5Gi
- 无需 sidecar；无需外部 PG/Redis

### 11.5 本地开发

- 后端：`gradle bootRun`（本地需 PG/Redis，可用 docker-compose.dev.yml 起）
- 前端：`npm run dev`，Vite 代理 `/api`、`/oauth2`、`/oauth` → `http://localhost:8080`
- 前端构建产物拷入 `src/main/resources/static`（或在 Dockerfile 构建阶段完成）

---

## 12. 实施顺序（里程碑）

| 里程碑 | 内容                                                                   | 验收                                           |
| :-: | -------------------------------------------------------------------- | -------------------------------------------- |
|  M1 | Gradle 骨架、application.yml、Flyway schema、健康检查、docker-compose.dev      | `bootRun` 启动，Flyway 建表，`/actuator/health` UP |
|  M2 | Spring Security + GitLab OAuth + JWT Cookie + 用户表/首登管理员 + `/auth/me` | 模拟 OAuth 用户注入后可登录/登出/取 me                    |
|  M3 | 环境 CRUD + 继承链；应用 CRUD；模块 CRUD + 绑定环境 + 共享模块 + 订阅                     | 接口层完整可用                                      |
|  M4 | 变量矩阵（定义/全局/覆盖/继承解析/加密/导入导出/reveal）+ 文件版本 + 成员                        | 解析优先级单测通过（§2.2 五种路径）                         |
|  M5 | 模板库 + 实例化 + diff 同步；模块复制/分组/排序                                       | diff 三分类正确；同步不覆盖值                            |
|  M6 | 流水线 API + Token + 限流 + 访问日志 + 审计 + 备份恢复 + 定时任务                       | §13 场景 7–12 通过                               |
|  M7 | 前端全页面（登录/看板/矩阵/模板/环境/用户/Token/审计/备份/文档）                              | 核心链路可点通                                      |
|  M8 | 单镜像 Dockerfile + entrypoint + 启动验证                                   | 容器起、health UP、页面可访问                          |

---

## 13. 验收清单（端到端）

1. 空库首次 OAuth 登录 → 成为系统管理员；第二个用户登录 → `NONE`，看不到任何模块
2. 环境 dev→test→prod 继承链；成环配置被拒绝
3. 建应用 + 模板实例化模块：变量骨架生成，明文默认值落全局值
4. 矩阵：设置全局值 → 各环境继承；prod 覆盖 → 仅 prod 变化
5. 加密变量：管理员可见明文；操作员脱敏；操作员可写不可读；创建人可读自己的；reveal 产生高危审计
6. 共享模块：订阅后实时跟随；应用级覆盖优先；删源模块前列出订阅方
7. 流水线：Token 拉取必须带 modules；dotenv 分组与顺序正确；共享模块展开；继承回退正确；缺模块 400
8. Token 无效 401、跨应用模块 403、61 次/分钟 429
9. 文件上传 v1–v4 仅留 3 版；指定版本可下；artifacts tar.gz 结构正确；50MB 拒绝
10. 审计：操作入库、7 天清理任务可执行、流水线调用**不**入审计；访问日志 30 天
11. 备份：包结构正确、密文导出、保留 3 份；恢复三策略行为符合 §9.2
12. 单镜像：`docker run -e ... -v pvc:/data/config-hub -p 8080:8080` → health UP、nginx 页面、API 反代、重启后数据仍在

---

## 14. 明确不做（边界）

- 不做暗黑模式、国际化、移动端适配
- 不做环境级权限（不做"prod 仅管理员可改"）
- 不做流水线回写/部署记录
- 不做对象存储（本地磁盘；接口预留可替换）
- 不做多副本/外部 PG 模式（单镜像单副本）
- 不做变量"引用语法"（`${other.key}`）
- 不做按环境的 Token 权限隔离

## 15. 关键风险与坑位

| 风险                        | 处理                                                  |
| ------------------------- | --------------------------------------------------- |
| Gradle 9 与 Boot 2.7 插件不兼容 | 固定 Gradle 7.5.1（Dockerfile 用 `gradle:7.5.1-jdk17` 构建阶段） |
| Flyway 8.5.13 不识别 PG16+   | 显式 `flyway-core:9.22.3`                             |
| 单镜像 PG 数据与容器生命周期绑定        | 必须 PVC；文档写明单副本限制                                    |
| 主密钥丢失 → 加密变量不可恢复          | 建议生产显式提供 `FCLOUD_MASTER_KEY` 并单独保管                  |
| GitLab 邮箱为空               | 回退 `username@gitlab域名`                              |
| 备份包含历史版本导致包大              | 文件仅 3 版；必要时文档提示排除大文件                                |
| Cookie Secure 与 HTTP 部署   | 默认 false；HTTPS 环境置 true                             |

---

## 附录 A：给执行 Agent 的开场指令（可直接粘贴到本地 WorkBuddy）

```text
你要在空仓库中实现 fcloud-ci-manager（配置分发中心）。请先完整阅读同目录的
《fcloud-ci-manager 设计与决策书（最终版）》并严格遵守其中所有已定决策，不要自行更改技术栈与交互设计。

执行要求：
1. 按文档 §12 的 M1–M8 顺序推进，每个里程碑完成后可独立验证再进入下一个。
2. 技术栈锁定：Java 17 + Spring Boot 2.7.17 + Gradle 7.5.1(Groovy) + MyBatis-Plus 3.5.3.2 +
   PostgreSQL 18 + Flyway 9.22.3 + Vue3/Vite/TS/Element Plus（Redis 已移除，见 D26）。
3. 严格实现文档 §2.2 的变量解析优先级与 §6.3 的权限矩阵，并为其编写单元测试。
4. 流水线 API 的三个接口按 §4 的契约实现，任何变量值不得写入日志。
5. 交付单镜像（§11.2），本地用 docker run 验证 §13 的 12 条验收清单，逐条给出结果。
6. 遇到文档未覆盖的细节：选择最简单且符合"显式、可审计"原则的方案，并在 README 记录决策。
```

## 附录 B：本次访谈的关键取舍备忘

- **多环境 Tab → 变量矩阵**：把 N×M 次填写压缩为 N 行 + 少量覆盖；这是 UI 的核心价值主张
- **拖拽只保留在有落点语义的场景**：模板实例化默认点击，拖拽是捷径；订阅/授权/复制/分组/排序用拖拽
- **模板保持关联 + diff 同步**：比"脱钩"多一层复杂度，但换来模板演进能力；同步永不覆盖已填值
- **备份只含密文**：包泄露不泄密，代价是主密钥必须独立保管
- **首登即管理员**：空库自举，省去初始化命令；风险是首个登录者必须是可信的人（部署后立即登录）
