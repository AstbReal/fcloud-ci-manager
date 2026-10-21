-- 系统设置（键值表）：GitLab 接入等全局配置
CREATE TABLE IF NOT EXISTS fch_system_setting (
    setting_key   VARCHAR(64) PRIMARY KEY,
    setting_value TEXT,
    encrypted     BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by    BIGINT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE fch_system_setting IS '系统级配置；加密项（如 GitLab Token）以主密钥加密存储';

-- 应用与 GitLab 仓库的关联
ALTER TABLE fch_application ADD COLUMN IF NOT EXISTS gitlab_project_id BIGINT;
ALTER TABLE fch_application ADD COLUMN IF NOT EXISTS repo_url VARCHAR(512);
