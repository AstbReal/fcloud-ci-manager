-- 备份任务异步化：记录状态、失败原因、完成时间
-- 历史记录都是同步生成成功的，直接落 SUCCESS，不改变既有语义。

ALTER TABLE fch_backup_record
    ADD COLUMN IF NOT EXISTS status        VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS finished_at   TIMESTAMPTZ;

COMMENT ON COLUMN fch_backup_record.status IS 'RUNNING | SUCCESS | FAILED';
COMMENT ON COLUMN fch_backup_record.error_message IS '失败原因，仅 FAILED 时有值';

CREATE INDEX IF NOT EXISTS idx_fch_backup_record_status ON fch_backup_record(status);
