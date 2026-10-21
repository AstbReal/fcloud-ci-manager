-- ============================================================================
-- fcloud-ci-hub V1 初始化迁移（§3 数据模型）
-- 所有时间列 TIMESTAMPTZ（存 UTC，展示 Asia/Shanghai）；主键 BIGINT 雪花（应用层生成）
-- ============================================================================

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
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
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

-- 12. 流水线 API Token（SHA-256 存储）
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
  last_used_at    TIMESTAMPTZ
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
