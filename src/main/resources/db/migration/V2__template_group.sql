-- 模板分组（数据库 / 消息 / 缓存 / 基础 …），用于模板库左侧列表归类
ALTER TABLE fch_template ADD COLUMN IF NOT EXISTS group_name VARCHAR(64);

COMMENT ON COLUMN fch_template.group_name IS '模板分组，仅用于模板库展示归类';
