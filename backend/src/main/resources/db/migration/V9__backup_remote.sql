-- 备份上传到制品库（Gitee）的状态。
-- remote_status: NULL = 未上传（开关关闭或从未尝试）/ UPLOADED / FAILED
-- 注意：上传失败不影响本地备份记录本身的 SUCCESS 状态，两者是独立维度。
ALTER TABLE fch_backup_record ADD COLUMN IF NOT EXISTS remote_status VARCHAR(16);
ALTER TABLE fch_backup_record ADD COLUMN IF NOT EXISTS remote_path VARCHAR(512);
ALTER TABLE fch_backup_record ADD COLUMN IF NOT EXISTS remote_error VARCHAR(1024);
ALTER TABLE fch_backup_record ADD COLUMN IF NOT EXISTS remote_uploaded_at TIMESTAMPTZ;

COMMENT ON COLUMN fch_backup_record.remote_status IS 'NULL | UPLOADED | FAILED —— 制品库上传状态';
COMMENT ON COLUMN fch_backup_record.remote_path IS '制品库内的路径，用于下载与清理';
