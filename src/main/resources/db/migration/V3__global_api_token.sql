-- 流水线 Token 改为全局：不再绑定单个应用
ALTER TABLE fch_api_token DROP CONSTRAINT IF EXISTS fch_api_token_application_id_fkey;
ALTER TABLE fch_api_token DROP COLUMN IF EXISTS application_id;
COMMENT ON TABLE fch_api_token IS '流水线 API Token（全局，不区分应用；仅存 SHA-256）';
