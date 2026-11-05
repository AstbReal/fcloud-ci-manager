-- 模板文件 + 模块内容来源标记。
--
-- origin 只区分「模板下发的副本」和「模块自有内容」：
--   MODULE   = 模块自己维护（默认，兼容存量数据）
--   TEMPLATE = 由模板复制而来，参与 diff / 同步
-- 不使用 template_var_id / template_file_id 做外键：模板更新采用
-- 删除重建（replaceVars），外键会在每次保存模板时被置空；用 var_key /
-- file_key 匹配更稳定，重命名按「删除 + 新增」处理。
ALTER TABLE fch_variable ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'MODULE';
ALTER TABLE fch_file     ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'MODULE';

COMMENT ON COLUMN fch_variable.origin IS 'MODULE = 模块自有 / TEMPLATE = 模板下发';
COMMENT ON COLUMN fch_file.origin     IS 'MODULE = 模块自有 / TEMPLATE = 模板下发';

-- 模板文件内容指纹：同步时先比对 sha256，内容一致就不产生新版本。
ALTER TABLE fch_file_version ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);

-- 模板文件定义（与环境无关；下发时复制到模块已绑定的每个环境）
CREATE TABLE IF NOT EXISTS fch_template_file (
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

CREATE INDEX IF NOT EXISTS idx_variable_module_origin ON fch_variable(module_id, origin);
CREATE INDEX IF NOT EXISTS idx_file_module_origin     ON fch_file(module_id, origin);
CREATE INDEX IF NOT EXISTS idx_template_file_tpl      ON fch_template_file(template_id);
