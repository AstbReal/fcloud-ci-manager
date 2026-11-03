-- 一个 GitLab 项目最多对应一个应用。
-- 之前的 import 接口因为 create() 丢字段，gitlab_project_id 从来没写进去过，
-- 所以这里不会与存量数据冲突；NULL 表示手工创建的应用，不参与约束。
CREATE UNIQUE INDEX IF NOT EXISTS uk_application_gitlab_project
    ON fch_application (gitlab_project_id)
    WHERE gitlab_project_id IS NOT NULL;
