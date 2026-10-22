-- 恢复任务异步化：上传完成后立即返回，恢复在后台线程执行
CREATE TABLE IF NOT EXISTS fch_restore_job (
    id            BIGINT PRIMARY KEY,
    file_name     VARCHAR(512)  NOT NULL,
    file_path     VARCHAR(1024) NOT NULL,   -- 上传包的临时路径，任务结束即删
    size          BIGINT,
    strategy      VARCHAR(16)   NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    error_message VARCHAR(1024),
    records       INT,
    files         INT,
    created_by    BIGINT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);

COMMENT ON TABLE fch_restore_job IS '备份恢复任务（异步）：记录状态、结果计数与失败原因';
COMMENT ON COLUMN fch_restore_job.status IS 'RUNNING | SUCCESS | FAILED';

CREATE INDEX IF NOT EXISTS idx_fch_restore_job_status ON fch_restore_job(status);
CREATE INDEX IF NOT EXISTS idx_fch_restore_job_created ON fch_restore_job(created_at DESC);
