-- MCP 个人访问令牌（Personal Access Token）
-- 与流水线全局 Token（fch_api_token）职责分离：
--   fch_api_token：机器主体、全局只读、无用户身份
--   fch_pat      ：人类主体、绑定用户、带 scope、可写
CREATE TABLE fch_pat (
  id            BIGINT PRIMARY KEY,
  user_id       BIGINT NOT NULL REFERENCES fch_user(id) ON DELETE CASCADE,
  name          VARCHAR(64) NOT NULL,
  token_hash    CHAR(64) NOT NULL UNIQUE,          -- hex(sha256(明文))
  token_prefix  VARCHAR(24) NOT NULL,              -- chub_pat_ + 前 8 位
  scopes        VARCHAR(128) NOT NULL DEFAULT 'mcp:read',
  expires_at    TIMESTAMPTZ,
  revoked       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at  TIMESTAMPTZ
);

CREATE INDEX idx_fch_pat_user ON fch_pat(user_id);

COMMENT ON TABLE fch_pat IS 'AI/MCP 个人访问令牌（绑定用户，带 scope；仅存 SHA-256）';
COMMENT ON COLUMN fch_pat.scopes IS '逗号分隔：mcp:read / mcp:write / mcp:secret';
