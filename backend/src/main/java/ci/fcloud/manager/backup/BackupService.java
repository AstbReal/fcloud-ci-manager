package ci.fcloud.manager.backup;

import ci.fcloud.manager.app.Application;
import ci.fcloud.manager.app.ApplicationMapper;
import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.config.FcloudProperties;
import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.env.EnvironmentMapper;
import ci.fcloud.manager.file.FileVersion;
import ci.fcloud.manager.file.FileVersionMapper;
import ci.fcloud.manager.file.ModuleFile;
import ci.fcloud.manager.file.ModuleFileMapper;
import ci.fcloud.manager.module.*;
import ci.fcloud.manager.pat.Pat;
import ci.fcloud.manager.pat.PatMapper;
import ci.fcloud.manager.pat.PatService;
import ci.fcloud.manager.setting.SystemSetting;
import ci.fcloud.manager.setting.SystemSettingMapper;
import ci.fcloud.manager.template.Template;
import ci.fcloud.manager.setting.ArtifactRepoClient;
import ci.fcloud.manager.setting.ArtifactRepoSettingService;
import ci.fcloud.manager.template.TemplateMapper;
import ci.fcloud.manager.template.TemplateVar;
import ci.fcloud.manager.template.TemplateVarMapper;
import ci.fcloud.manager.token.ApiToken;
import ci.fcloud.manager.token.ApiTokenMapper;
import ci.fcloud.manager.token.ApiTokenService;
import ci.fcloud.manager.user.User;
import ci.fcloud.manager.user.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §9 备份与恢复。
 *
 * <pre>
 * 包结构：
 *   manifest.json   全量配置（users/envs/apps/modules/subscriptions/module_envs/
 *                   variables(密文)/overrides(密文)/files(元数据)/members/
 *                   templates/template_vars/tokens(仅 hash)/backup_at/version）
 *   files/          全部文件版本二进制，路径 {moduleId}/{fileId}/v{n}/{original}
 * </pre>
 *
 * - 加密变量导出**密文**，不含主密钥
 * - 不含审计日志、访问日志
 * - 文件名：fcloud-backup-YYYYMMDD-HHmmss.tar.gz
 * - 手动 + 定时（每日 02:00），保留最近 N 份
 * - 恢复策略：ABORT / MERGE / OVERWRITE
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final String MANIFEST = "manifest.json";
    private static final String FILES_DIR = "files/";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupRecordMapper backupMapper;
    private final ObjectMapper objectMapper;
    private final FcloudProperties props;

    private final UserMapper userMapper;
    private final EnvironmentMapper environmentMapper;
    private final ApplicationMapper applicationMapper;
    private final ModuleMapper moduleMapper;
    private final ModuleEnvMapper moduleEnvMapper;
    private final ModuleSubscriptionMapper subscriptionMapper;
    private final ModuleMemberMapper memberMapper;
    private final VariableMapper variableMapper;
    private final VariableOverrideMapper overrideMapper;
    private final ModuleFileMapper fileMapper;
    private final FileVersionMapper fileVersionMapper;
    private final TemplateMapper templateMapper;
    private final TemplateVarMapper templateVarMapper;
    private final ApiTokenMapper tokenMapper;
    private final ApiTokenService tokenService;
    private final PatMapper patMapper;
    private final PatService patService;
    private final SystemSettingMapper settingMapper;
    private final RestoreJobMapper restoreJobMapper;

    /** 恢复要在后台线程里开事务：显式用 TransactionTemplate，避免 self-invocation 绕过代理 */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    /** 制品库（Artifactory 兼容）异地存放：开关关闭时下面两个 bean 不会被调用 */
    private final ArtifactRepoSettingService artifactSettings;
    private final ArtifactRepoClient artifactRepo;

    /** 备份打包是 IO 密集型长任务，单独一个单线程执行器，避免占满公共线程池 */
    private final @Qualifier("backupExecutor") TaskExecutor backupExecutor;

    /**
     * 进程重启后，上一轮还在 RUNNING 的记录不可能再完成，标记为失败。
     * 否则前端会一直轮询，且并发保护会永远挡住新的备份。
     */
    @PostConstruct
    public void failStaleRunning() {
        int n = jdbcTemplate.update(
                "UPDATE fch_backup_record SET status = 'FAILED', "
                        + "error_message = '进程重启，备份任务中断', finished_at = now() "
                        + "WHERE status = 'RUNNING'");
        if (n > 0) {
            log.warn("marked {} stale RUNNING backup record(s) as FAILED", n);
        }
        // 恢复任务同理：进程重启后不可能再继续，顺带清掉它留下的临时包
        List<RestoreJob> staleJobs = restoreJobMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RestoreJob>()
                        .eq(RestoreJob::getStatus, "RUNNING"));
        for (RestoreJob job : staleJobs) {
            job.setStatus("FAILED");
            job.setErrorMessage("进程重启，恢复任务中断");
            job.setFinishedAt(Instant.now());
            restoreJobMapper.updateById(job);
            try {
                Files.deleteIfExists(Paths.get(job.getFilePath()));
            } catch (IOException ignored) {
                // 临时文件删不掉不影响状态
            }
        }
        if (!staleJobs.isEmpty()) {
            log.warn("marked {} stale RUNNING restore job(s) as FAILED", staleJobs.size());
        }
    }

    // ==================================================================
    // 备份
    // ==================================================================

    /**
     * 创建备份任务：只落一条 RUNNING 记录并立刻返回，真正的打包放到后台线程。
     *
     * <p>不加事务注解：记录必须马上提交，前端才能看到 RUNNING，
     * 后台线程也才能按 id 查到这个记录。</p>
     */
    public BackupRecord createAsync(String type, Long createdBy) {
        assertIdle();

        Path dir = Paths.get(props.getBackup().getDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException(50000, "无法创建备份目录");
        }

        String fileName = "fcloud-backup-" + LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(TS) + ".tar.gz";
        Path out = dir.resolve(fileName);

        BackupRecord rec = new BackupRecord();
        rec.setFileName(fileName);
        rec.setFilePath(out.toString());
        rec.setSize(0L);
        rec.setType(type == null ? "MANUAL" : type);
        rec.setCreatedBy(createdBy);
        rec.setCreatedAt(Instant.now());
        rec.setStatus("RUNNING");
        backupMapper.insert(rec);

        Long id = rec.getId();
        backupExecutor.execute(() -> runBackup(id));
        return rec;
    }

    /**
     * 后台打包：成功置 SUCCESS 并清理旧份；失败保留记录、写原因、删半成品。
     */
    private void runBackup(Long recordId) {
        BackupRecord rec = backupMapper.selectById(recordId);
        if (rec == null) {
            log.warn("backup record {} disappeared before packaging", recordId);
            return;
        }
        Path out = Paths.get(rec.getFilePath());
        long started = System.currentTimeMillis();
        try {
            writeArchive(out);
            rec.setSize(Files.size(out));
            rec.setStatus("SUCCESS");
            rec.setFinishedAt(Instant.now());
            backupMapper.updateById(rec);
            pruneOld();
            // 本地成功之后再上传；上传失败不改变本地记录的 SUCCESS 状态
            uploadToRemote(rec);
            log.info("backup {} finished in {} ms ({} bytes)",
                    rec.getFileName(), System.currentTimeMillis() - started, rec.getSize());
        } catch (Exception e) {
            log.error("backup {} failed", rec.getFileName(), e);
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
                // 半成品删不掉也不影响记录状态
            }
            rec.setSize(0L);
            rec.setStatus("FAILED");
            rec.setErrorMessage(truncate(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), 900));
            rec.setFinishedAt(Instant.now());
            backupMapper.updateById(rec);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 打 tar.gz：manifest.json + files/ 下全部文件版本二进制 */
    private void writeArchive(Path out) throws IOException {
        Manifest m = buildManifest();
        try (OutputStream os = Files.newOutputStream(out);
             GzipCompressorOutputStream gz = new GzipCompressorOutputStream(os);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            // manifest 必须显式写出 null 字段（全局配置了 non_null），
            // 因此这里复制一份不带该配置的 ObjectMapper。
            ObjectMapper manifestMapper = objectMapper.copy()
                    .setSerializationInclusion(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
            byte[] json = manifestMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(m);
            addBytes(tar, MANIFEST, json);

            // files/：全部文件版本二进制
            for (FileVersion fv : fileVersionMapper.selectList(null)) {
                ModuleFile mf = fileMapper.selectById(fv.getFileId());
                if (mf == null) {
                    continue;
                }
                Path src = Paths.get(props.getStorage().getRoot(), fv.getStoragePath());
                if (!Files.exists(src)) {
                    continue;
                }
                String entry = FILES_DIR + mf.getModuleId() + "/" + mf.getId()
                        + "/v" + fv.getVersion() + "/" + fv.getOriginalName();
                TarArchiveEntry te = new TarArchiveEntry(src.toFile(), entry);
                tar.putArchiveEntry(te);
                Files.copy(src, tar);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    private void addBytes(TarArchiveOutputStream tar, String name, byte[] data)
            throws IOException {
        TarArchiveEntry te = new TarArchiveEntry(name);
        te.setSize(data.length);
        tar.putArchiveEntry(te);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private Manifest buildManifest() {
        Manifest m = new Manifest();
        // v2：新增 pats / settings，并补上应用的 GitLab 绑定字段
        m.version = 2;
        m.backupAt = Instant.now();

        // 用户：不含任何密码字段（设计上本就没有）
        for (User u : userMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", u.getId());
            o.put("email", u.getEmail());
            o.put("name", u.getName());
            o.put("nickname", u.getNickname());
            o.put("avatarUrl", u.getAvatarUrl());
            o.put("gitlabId", u.getGitlabId());
            o.put("globalRole", u.getGlobalRole());
            o.put("lastLoginAt", u.getLastLoginAt());
            o.put("createdAt", u.getCreatedAt());
            o.put("updatedAt", u.getUpdatedAt());
            m.users.add(o);
        }

        for (Environment e : environmentMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", e.getId());
            o.put("code", e.getCode());
            o.put("name", e.getName());
            o.put("color", e.getColor());
            o.put("sortOrder", e.getSortOrder());
            o.put("isDefault", e.getIsDefault());
            o.put("parentEnvId", e.getParentEnvId());
            o.put("createdAt", e.getCreatedAt());
            o.put("updatedAt", e.getUpdatedAt());
            m.environments.add(o);
        }

        for (Application a : applicationMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.getId());
            o.put("code", a.getCode());
            o.put("name", a.getName());
            o.put("description", a.getDescription());
            o.put("sortOrder", a.getSortOrder());
            // V4 新增：GitLab 仓库绑定，漏了会在恢复后丢关联
            o.put("gitlabProjectId", a.getGitlabProjectId());
            o.put("repoUrl", a.getRepoUrl());
            o.put("createdBy", a.getCreatedBy());
            o.put("createdAt", a.getCreatedAt());
            o.put("updatedAt", a.getUpdatedAt());
            m.applications.add(o);
        }

        for (ci.fcloud.manager.module.Module md : moduleMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", md.getId());
            o.put("applicationId", md.getApplicationId());
            o.put("code", md.getCode());
            o.put("name", md.getName());
            o.put("description", md.getDescription());
            o.put("isShared", md.getIsShared());
            o.put("groupName", md.getGroupName());
            o.put("templateId", md.getTemplateId());
            o.put("templateSyncedAt", md.getTemplateSyncedAt());
            o.put("sortOrder", md.getSortOrder());
            o.put("createdBy", md.getCreatedBy());
            o.put("createdAt", md.getCreatedAt());
            o.put("updatedAt", md.getUpdatedAt());
            m.modules.add(o);
        }

        for (ModuleEnv me : moduleEnvMapper.selectList(null)) {
            m.moduleEnvs.add(Map.of("id", me.getId(), "moduleId", me.getModuleId(),
                    "environmentId", me.getEnvironmentId()));
        }

        for (ModuleSubscription s : subscriptionMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", s.getId());
            o.put("moduleId", s.getModuleId());
            o.put("applicationId", s.getApplicationId());
            o.put("createdBy", s.getCreatedBy());
            o.put("createdAt", s.getCreatedAt());
            m.subscriptions.add(o);
        }

        for (ModuleMember mm : memberMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", mm.getId());
            o.put("moduleId", mm.getModuleId());
            o.put("userId", mm.getUserId());
            o.put("role", mm.getRole());
            o.put("createdBy", mm.getCreatedBy());
            o.put("createdAt", mm.getCreatedAt());
            m.members.add(o);
        }

        // 变量：加密值导出密文（global_value 原样，本身就是密文）
        for (Variable v : variableMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", v.getId());
            o.put("moduleId", v.getModuleId());
            o.put("varKey", v.getVarKey());
            o.put("valueType", v.getValueType());
            o.put("globalValue", v.getGlobalValue());
            o.put("remark", v.getRemark());
            o.put("isRequired", v.getIsRequired());
            o.put("sortOrder", v.getSortOrder());
            o.put("createdBy", v.getCreatedBy());
            o.put("createdAt", v.getCreatedAt());
            o.put("updatedAt", v.getUpdatedAt());
            m.variables.add(o);
        }

        // 覆盖：值已是密文（加密变量）或明文（明文变量），原样导出
        for (VariableOverride ov : overrideMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", ov.getId());
            o.put("variableId", ov.getVariableId());
            o.put("environmentId", ov.getEnvironmentId());
            o.put("applicationId", ov.getApplicationId());
            o.put("value", ov.getValue());
            o.put("updatedBy", ov.getUpdatedBy());
            o.put("updatedAt", ov.getUpdatedAt());
            m.overrides.add(o);
        }

        // 文件元数据（不含二进制；二进制在 files/ 下）
        for (ModuleFile f : fileMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", f.getId());
            o.put("moduleId", f.getModuleId());
            o.put("environmentId", f.getEnvironmentId());
            o.put("fileKey", f.getFileKey());
            o.put("remark", f.getRemark());
            o.put("currentVersion", f.getCurrentVersion());
            o.put("createdBy", f.getCreatedBy());
            o.put("createdAt", f.getCreatedAt());
            o.put("updatedAt", f.getUpdatedAt());
            m.files.add(o);
        }

        for (FileVersion fv : fileVersionMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", fv.getId());
            o.put("fileId", fv.getFileId());
            o.put("version", fv.getVersion());
            o.put("storagePath", fv.getStoragePath());
            o.put("originalName", fv.getOriginalName());
            o.put("mimeType", fv.getMimeType());
            o.put("size", fv.getSize());
            o.put("uploadedBy", fv.getUploadedBy());
            o.put("createdAt", fv.getCreatedAt());
            m.fileVersions.add(o);
        }

        for (Template t : templateMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", t.getId());
            o.put("code", t.getCode());
            o.put("name", t.getName());
            o.put("description", t.getDescription());
            o.put("createdBy", t.getCreatedBy());
            o.put("createdAt", t.getCreatedAt());
            o.put("updatedAt", t.getUpdatedAt());
            m.templates.add(o);
        }

        for (TemplateVar tv : templateVarMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", tv.getId());
            o.put("templateId", tv.getTemplateId());
            o.put("varKey", tv.getVarKey());
            o.put("valueType", tv.getValueType());
            o.put("remark", tv.getRemark());
            o.put("defaultValue", tv.getDefaultValue());
            o.put("isRequired", tv.getIsRequired());
            o.put("sortOrder", tv.getSortOrder());
            m.templateVars.add(o);
        }

        // Token：仅 hash 与前缀，绝不含明文
        for (ApiToken t : tokenMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", t.getId());
            o.put("name", t.getName());
            o.put("tokenHash", t.getTokenHash());
            o.put("tokenPrefix", t.getTokenPrefix());
            o.put("expiresAt", t.getExpiresAt());
            o.put("revoked", t.getRevoked());
            o.put("createdBy", t.getCreatedBy());
            o.put("createdAt", t.getCreatedAt());
            o.put("lastUsedAt", t.getLastUsedAt());
            m.tokens.add(o);
        }

        // 个人访问令牌（MCP）：同样只存 hash
        for (Pat p : patMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", p.getId());
            o.put("userId", p.getUserId());
            o.put("name", p.getName());
            o.put("tokenHash", p.getTokenHash());
            o.put("tokenPrefix", p.getTokenPrefix());
            o.put("scopes", p.getScopes());
            o.put("expiresAt", p.getExpiresAt());
            o.put("revoked", p.getRevoked());
            o.put("createdAt", p.getCreatedAt());
            o.put("lastUsedAt", p.getLastUsedAt());
            m.pats.add(o);
        }

        // 系统设置：GitLab 接入、MCP 开关等；加密项导出的也是密文
        for (SystemSetting st : settingMapper.selectList(null)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("settingKey", st.getSettingKey());
            o.put("settingValue", st.getSettingValue());
            o.put("encrypted", st.getEncrypted());
            o.put("updatedBy", st.getUpdatedBy());
            o.put("updatedAt", st.getUpdatedAt());
            m.settings.add(o);
        }

        return m;
    }

    /**
     * §9.2 保留最近 N 份（超出删除文件 + 记录）。
     * 只统计已结束的任务：RUNNING 的那条不能删，否则后台线程更新不到它。
     */
    public void pruneOld() {
        int retain = props.getBackup().getRetain();
        // 只读一次配置：关掉制品库后不该再为每条旧记录跑一次 requireUsable
        boolean remoteOn = artifactSettings.load().usable();
        List<BackupRecord> all = backupMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BackupRecord>()
                        .ne(BackupRecord::getStatus, "RUNNING")
                        .orderByDesc(BackupRecord::getCreatedAt));
        for (int i = retain; i < all.size(); i++) {
            BackupRecord old = all.get(i);
            try {
                Files.deleteIfExists(Paths.get(old.getFilePath()));
            } catch (IOException ignored) {
                // 文件可能已被手动删除
            }
            // 本地删了，异地那份也一起清掉，两边份数保持一致
            if (remoteOn && "UPLOADED".equals(old.getRemoteStatus())) {
                try {
                    artifactRepo.delete(old.getFileName());
                } catch (Exception e) {
                    log.warn("delete remote backup {} failed: {}", old.getRemotePath(), e.getMessage());
                }
            }
            backupMapper.deleteById(old.getId());
        }
    }

    // ==================================================================
    // 制品库（JFrog Artifactory 兼容）异地存放
    // ==================================================================

    /**
     * 把备份包上传到制品库。失败只影响 remote_* 字段，不改动本地备份的成功状态
     * —— 本地那份是有效的，没必要因为异地失败就把它标成失败。
     */
    public BackupRecord uploadToRemote(BackupRecord rec) {
        ArtifactRepoSettingService.Config cfg = artifactSettings.load();
        if (!cfg.enabled()) {
            return rec;
        }
        if (!cfg.usable()) {
            rec.setRemoteStatus("FAILED");
            rec.setRemoteError("制品库配置不完整：需要 owner / repo / Token");
            backupMapper.updateById(rec);
            return rec;
        }
        try {
            Path file = Paths.get(rec.getFilePath());
            if (!Files.exists(file)) {
                throw new BizException(40400, "本地备份文件已不存在，无法上传");
            }
            artifactRepo.upload(rec.getFileName(), file);
            rec.setRemoteStatus("UPLOADED");
            rec.setRemotePath(ArtifactRepoClient.joinPath(cfg.dir(), rec.getFileName()));
            rec.setRemoteError(null);
            rec.setRemoteUploadedAt(Instant.now());
            // 上传成功就顺手裁一次：手动重试走的也是这个方法，不能只在定时备份里裁
            pruneRemote(props.getBackup().getRetain());
            log.info("backup {} uploaded to artifact repo as {}", rec.getFileName(), rec.getRemotePath());
        } catch (Exception e) {
            rec.setRemoteStatus("FAILED");
            rec.setRemoteError(truncate(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), 900));
            log.warn("upload backup {} to artifact repo failed: {}", rec.getFileName(), e.getMessage());
        }
        backupMapper.updateById(rec);
        return rec;
    }

    /** 制品库只保留最近 N 份（跟随本地 retain），超出部分删掉。 */
    private void pruneRemote(int retain) {
        ArtifactRepoSettingService.Config cfg = artifactSettings.load();
        if (!cfg.usable()) {
            return;
        }
        try {
            List<ArtifactRepoClient.RemoteFile> files = artifactRepo.list().stream()
                    .filter(f -> f.name().endsWith(".tar.gz"))
                    .sorted(java.util.Comparator.comparing(ArtifactRepoClient.RemoteFile::name).reversed())
                    .toList();
            for (int i = retain; i < files.size(); i++) {
                artifactRepo.delete(files.get(i).name());
            }
        } catch (Exception e) {
            // 清理是尽力而为，不影响备份结果
            log.warn("prune remote backups failed: {}", e.getMessage());
        }
    }

    /** 制品库上可用的备份（按文件名倒序 = 新的在前）。 */
    public List<ArtifactRepoClient.RemoteFile> listRemote() {
        return artifactRepo.list().stream()
                .filter(f -> f.name().endsWith(".tar.gz"))
                .sorted(java.util.Comparator.comparing(ArtifactRepoClient.RemoteFile::name).reversed())
                .toList();
    }

    public List<BackupRecord> listAll() {
        return backupMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BackupRecord>()
                        .orderByDesc(BackupRecord::getCreatedAt));
    }

    public BackupRecord requireById(Long id) {
        BackupRecord r = backupMapper.selectById(id);
        if (r == null) {
            throw BizException.notFound("备份记录不存在: " + id);
        }
        return r;
    }

    // ==================================================================
    // 恢复（异步）
    // ==================================================================

    /**
     * 提交恢复任务：上传包落到临时文件 → 插一条 RUNNING 记录 → 后台执行。
     *
     * <p>上传本身仍占用请求线程（要收完整个包），解包导入在后台线程完成。</p>
     */
    public RestoreJob submitRestore(MultipartFile file, String strategy, Long createdBy) {
        String s = normalizeStrategy(strategy);
        assertIdle();

        Path tmp = newRestoreTemp();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException(40000, "备份包读取失败");
        }
        String original = file.getOriginalFilename();
        return enqueueRestore(tmp,
                original == null || original.isEmpty() ? tmp.getFileName().toString() : original,
                file.getSize(), s, createdBy);
    }

    /**
     * 从制品库恢复：先把包下到本地临时文件，再走同一套后台任务。
     * 下载失败不建任务（否则会留下一条永远失败的记录）。
     */
    public RestoreJob submitRestoreFromRemote(String remotePath, String strategy, Long createdBy) {
        if (remotePath == null || remotePath.isBlank()) {
            throw BizException.badRequest("请选择制品库上的备份");
        }
        String s = normalizeStrategy(strategy);
        assertIdle();

        Path tmp = newRestoreTemp();
        String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        long size;
        try {
            artifactRepo.download(fileName, tmp);
            size = Files.size(tmp);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new BizException(50000, "制品库备份包写入失败：" + e.getMessage());
        } catch (RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
        return enqueueRestore(tmp, fileName, size, s, createdBy);
    }

    private RestoreJob enqueueRestore(Path tmp, String displayName, long size,
                                      String strategy, Long createdBy) {
        RestoreJob job = new RestoreJob();
        job.setFileName(displayName);
        job.setFilePath(tmp.toString());
        job.setSize(size);
        job.setStrategy(strategy);
        job.setStatus("RUNNING");
        job.setCreatedBy(createdBy);
        job.setCreatedAt(Instant.now());
        restoreJobMapper.insert(job);

        Long id = job.getId();
        backupExecutor.execute(() -> runRestore(id));
        return job;
    }

    private Path newRestoreTemp() {
        Path dir = Paths.get(props.getBackup().getDir()).resolve(".restore");
        try {
            Files.createDirectories(dir);
            return Files.createTempFile(dir, "restore-", ".tar.gz");
        } catch (IOException e) {
            throw new BizException(50000, "无法创建恢复临时目录");
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 删不掉不影响结果
        }
    }

    private void runRestore(Long jobId) {
        RestoreJob job = restoreJobMapper.selectById(jobId);
        if (job == null) {
            log.warn("restore job {} disappeared before execution", jobId);
            return;
        }
        Path tmp = Paths.get(job.getFilePath());
        long started = System.currentTimeMillis();
        try {
            RestoreResult r = txTemplate.execute(st -> restoreArchive(tmp, job.getStrategy()));
            job.setRecords(r == null ? 0 : r.records());
            job.setFiles(r == null ? 0 : r.files());
            job.setStatus("SUCCESS");
            log.info("restore {} finished in {} ms ({} records, {} files)",
                    job.getFileName(), System.currentTimeMillis() - started,
                    job.getRecords(), job.getFiles());
        } catch (Exception e) {
            log.error("restore {} failed", job.getFileName(), e);
            job.setStatus("FAILED");
            job.setErrorMessage(truncate(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), 900));
        } finally {
            job.setFinishedAt(Instant.now());
            try {
                restoreJobMapper.updateById(job);
            } catch (Exception e) {
                log.error("failed to update restore job {}", jobId, e);
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时包删不掉不影响结果
            }
        }
    }

    private static String normalizeStrategy(String strategy) {
        String s = strategy == null ? "ABORT" : strategy.toUpperCase();
        if (!Set.of("ABORT", "MERGE", "OVERWRITE").contains(s)) {
            throw BizException.badRequest("strategy 必须是 ABORT / MERGE / OVERWRITE");
        }
        return s;
    }

    /** 备份与恢复互斥：两者都动全库，串行跑才有意义 */
    private void assertIdle() {
        Long backups = backupMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BackupRecord>()
                        .eq(BackupRecord::getStatus, "RUNNING"));
        Long restores = restoreJobMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RestoreJob>()
                        .eq(RestoreJob::getStatus, "RUNNING"));
        boolean busy = (backups != null && backups > 0) || (restores != null && restores > 0);
        if (busy) {
            throw new BizException(40900, "已有备份或恢复任务在执行，请等它结束再试");
        }
    }

    public List<RestoreJob> listRestores() {
        return restoreJobMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RestoreJob>()
                        .orderByDesc(RestoreJob::getCreatedAt)
                        .last("limit 20"));
    }

    /**
     * 真正的恢复逻辑，由后台线程在事务里调用。
     *
     * @param tmp      本地备份包（已落盘）
     * @param strategy ABORT（默认）/ MERGE / OVERWRITE
     */
    public RestoreResult restoreArchive(Path tmp, String strategy) {
        String s = normalizeStrategy(strategy);

        Manifest m;
        try {
            m = readManifest(tmp);
        } catch (IOException e) {
            throw new BizException(40000, "备份包读取失败");
        }

        List<String> conflicts = detectConflicts(m);

        if ("ABORT".equals(s) && !conflicts.isEmpty()) {
            String detail = conflicts.size() > 15
                    ? String.join(", ", conflicts.subList(0, 15)) + " 等共 " + conflicts.size() + " 项"
                    : String.join(", ", conflicts);
            throw new BizException(40900, "发现冲突，已整体回滚：" + detail);
        }

        if ("OVERWRITE".equals(s)) {
            clearBusinessTables();
        }

        Set<Long> skipApps = new HashSet<>();
        Set<Long> skipEnvs = new HashSet<>();
        Set<Long> skipModules = new HashSet<>();
        Set<Long> skipVars = new HashSet<>();
        Set<Long> skipUsers = new HashSet<>();
        Set<Long> skipTemplates = new HashSet<>();
        Set<Long> skipTokens = new HashSet<>();
        Set<Long> skipPats = new HashSet<>();

        Set<Long> skipFiles = new HashSet<>();
        Set<Long> skipFileVersions = new HashSet<>();
        Set<Long> skipModuleEnvs = new HashSet<>();
        Set<Long> skipSubscriptions = new HashSet<>();
        Set<Long> skipMembers = new HashSet<>();
        Set<Long> skipOverrides = new HashSet<>();
        Set<Long> skipTemplateVars = new HashSet<>();

        if ("MERGE".equals(s)) {
            // §9.2 MERGE：按自然键（email / env.code / app.code / module(app,code) /
            // var(module,key) …）跳过已存在，仅补缺失
            for (User u : userMapper.selectList(null)) {
                for (Map<String, Object> o : m.users) {
                    if (u.getEmail().equals(o.get("email"))) {
                        skipUsers.add(num(o.get("id")));
                    }
                }
            }
            for (Application a : applicationMapper.selectList(null)) {
                for (Map<String, Object> o : m.applications) {
                    if (a.getCode().equals(o.get("code"))) {
                        skipApps.add(num(o.get("id")));
                    }
                }
            }
            for (Environment e : environmentMapper.selectList(null)) {
                for (Map<String, Object> o : m.environments) {
                    if (e.getCode().equals(o.get("code"))) {
                        skipEnvs.add(num(o.get("id")));
                    }
                }
            }
            for (Object[] key : existingModuleKeys()) {
                for (Map<String, Object> o : m.modules) {
                    // applicationId 可能为 null（共享模块），且 manifest 里的 Long 是字符串，
                    // 必须归一化后再比，否则私用模块永远匹配不上、插入时撞主键
                    if (java.util.Objects.equals(key[0], num(o.get("applicationId")))
                            && java.util.Objects.equals(key[1], str(o.get("code")))) {
                        skipModules.add(num(o.get("id")));
                    }
                }
            }
            for (Object[] key : existingVariableKeys()) {
                for (Map<String, Object> o : m.variables) {
                    if (java.util.Objects.equals(key[0], num(o.get("moduleId")))
                            && java.util.Objects.equals(key[1], str(o.get("varKey")))) {
                        skipVars.add(num(o.get("id")));
                    }
                }
            }
            for (Template t : templateMapper.selectList(null)) {
                for (Map<String, Object> o : m.templates) {
                    if (t.getCode().equals(o.get("code"))) {
                        skipTemplates.add(num(o.get("id")));
                    }
                }
            }
            for (ApiToken t : tokenMapper.selectList(null)) {
                for (Map<String, Object> o : m.tokens) {
                    if (t.getTokenHash().equals(o.get("tokenHash"))) {
                        skipTokens.add(num(o.get("id")));
                    }
                }
            }
            // PAT 按 token_hash 跳过；同时避免与已有 id 撞主键
            Set<String> existPatHashes = new HashSet<>();
            Set<Long> existPatIds = new HashSet<>();
            for (Pat p : patMapper.selectList(null)) {
                existPatHashes.add(p.getTokenHash());
                existPatIds.add(p.getId());
            }
            for (Map<String, Object> o : m.pats) {
                if (existPatHashes.contains(str(o.get("tokenHash")))
                        || existPatIds.contains(num(o.get("id")))) {
                    skipPats.add(num(o.get("id")));
                }
            }
            // 文件按 (module,env,fileKey) 跳过；文件版本按 (fileId,version) 跳过
            Set<String> existFiles = new HashSet<>();
            for (ModuleFile f : fileMapper.selectList(null)) {
                existFiles.add(f.getModuleId() + "/" + f.getEnvironmentId()
                        + "/" + f.getFileKey());
            }
            for (Map<String, Object> o : m.files) {
                String k = o.get("moduleId") + "/" + o.get("environmentId")
                        + "/" + o.get("fileKey");
                if (existFiles.contains(k)) {
                    skipFiles.add(num(o.get("id")));
                }
            }
            Set<String> existVersions = new HashSet<>();
            for (FileVersion fv : fileVersionMapper.selectList(null)) {
                existVersions.add(fv.getFileId() + "/" + fv.getVersion());
            }
            for (Map<String, Object> o : m.fileVersions) {
                if (existVersions.contains(o.get("fileId") + "/" + o.get("version"))) {
                    skipFileVersions.add(num(o.get("id")));
                }
            }
            // 关联表同样按自然键跳过，避免唯一约束/主键冲突
            Set<String> existEnvBinds = new HashSet<>();
            for (ModuleEnv me : moduleEnvMapper.selectList(null)) {
                existEnvBinds.add(me.getModuleId() + "/" + me.getEnvironmentId());
            }
            for (Map<String, Object> o : m.moduleEnvs) {
                if (existEnvBinds.contains(o.get("moduleId") + "/" + o.get("environmentId"))) {
                    skipModuleEnvs.add(num(o.get("id")));
                }
            }
            Set<String> existSubs = new HashSet<>();
            for (ModuleSubscription sb : subscriptionMapper.selectList(null)) {
                existSubs.add(sb.getModuleId() + "/" + sb.getApplicationId());
            }
            for (Map<String, Object> o : m.subscriptions) {
                if (existSubs.contains(o.get("moduleId") + "/" + o.get("applicationId"))) {
                    skipSubscriptions.add(num(o.get("id")));
                }
            }
            Set<String> existMembers = new HashSet<>();
            for (ModuleMember mm : memberMapper.selectList(null)) {
                existMembers.add(mm.getModuleId() + "/" + mm.getUserId());
            }
            for (Map<String, Object> o : m.members) {
                if (existMembers.contains(o.get("moduleId") + "/" + o.get("userId"))) {
                    skipMembers.add(num(o.get("id")));
                }
            }
            Set<String> existOverrides = new HashSet<>();
            for (VariableOverride ov : overrideMapper.selectList(null)) {
                existOverrides.add(ov.getVariableId() + "/" + ov.getEnvironmentId()
                        + "/" + ov.getApplicationId());
            }
            for (Map<String, Object> o : m.overrides) {
                if (existOverrides.contains(o.get("variableId") + "/"
                        + o.get("environmentId") + "/" + o.get("applicationId"))) {
                    skipOverrides.add(num(o.get("id")));
                }
            }
            Set<String> existTplVars = new HashSet<>();
            for (TemplateVar tv : templateVarMapper.selectList(null)) {
                existTplVars.add(tv.getTemplateId() + "/" + tv.getVarKey());
            }
            for (Map<String, Object> o : m.templateVars) {
                if (existTplVars.contains(o.get("templateId") + "/" + o.get("varKey"))) {
                    skipTemplateVars.add(num(o.get("id")));
                }
            }
        }

        int counts = 0;
        counts += insertUsers(m.users, skipUsers);
        counts += insertEnvs(m.environments, skipEnvs);
        counts += insertApps(m.applications, skipApps);
        counts += insertModules(m.modules, skipModules);
        counts += insertModuleEnvs(m.moduleEnvs, skipModuleEnvs);
        counts += insertSubscriptions(m.subscriptions, skipSubscriptions);
        counts += insertMembers(m.members, skipMembers);
        counts += insertVariables(m.variables, skipVars);
        counts += insertOverrides(m.overrides, skipOverrides);
        counts += insertFiles(m.files, skipFiles);
        counts += insertFileVersions(m.fileVersions, skipFileVersions);
        counts += insertTemplates(m.templates, skipTemplates);
        counts += insertTemplateVars(m.templateVars, skipTemplateVars);
        counts += insertTokens(m.tokens, skipTokens);
        counts += insertPats(m.pats, m.users, skipPats);
        counts += upsertSettings(m.settings, "OVERWRITE".equals(s));

        // 文件二进制解包到 storage
        int filesRestored = extractFileBinaries(tmp);

        // §9.2 恢复后清理 Token 缓存（流水线 Token 与 PAT 各一套前缀）
        tokenService.clearCache();
        patService.clearCache();

        // 临时包由 runRestore 统一清理（成功/失败都删），这里不碰文件
        return new RestoreResult(s, counts, filesRestored);
    }

    private Manifest readManifest(Path tarGz) throws IOException {
        try (InputStream in = Files.newInputStream(tarGz);
             GzipCompressorInputStream gz = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                if (MANIFEST.equals(e.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = tar.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    return objectMapper.readValue(bos.toByteArray(), Manifest.class);
                }
            }
        }
        throw new BizException(40000, "备份包缺少 manifest.json");
    }

    /**
     * ABORT 模式的冲突检测：覆盖目标库全部唯一约束，
     * 否则冲突会在插入阶段以 SQL 异常形式爆掉，而不是干净的 40900。
     */
    private List<String> detectConflicts(Manifest m) {
        List<String> out = new ArrayList<>();

        Set<String> appCodes = new HashSet<>();
        for (Application a : applicationMapper.selectList(null)) {
            appCodes.add(a.getCode());
        }
        Set<String> envCodes = new HashSet<>();
        for (Environment e : environmentMapper.selectList(null)) {
            envCodes.add(e.getCode());
        }
        Set<String> userEmails = new HashSet<>();
        for (User u : userMapper.selectList(null)) {
            if (u.getEmail() != null) {
                userEmails.add(u.getEmail().toLowerCase());
            }
        }
        Set<String> templateCodes = new HashSet<>();
        for (Template t : templateMapper.selectList(null)) {
            templateCodes.add(t.getCode());
        }
        Set<String> tokenHashes = new HashSet<>();
        for (ApiToken t : tokenMapper.selectList(null)) {
            tokenHashes.add(t.getTokenHash());
        }
        Set<String> patHashes = new HashSet<>();
        for (Pat p : patMapper.selectList(null)) {
            patHashes.add(p.getTokenHash());
        }
        Set<String> moduleKeys = new HashSet<>();
        for (Object[] k : existingModuleKeys()) {
            moduleKeys.add(k[0] + "/" + k[1]);
        }

        for (Map<String, Object> o : m.applications) {
            if (appCodes.contains(o.get("code"))) {
                out.add("app:" + o.get("code"));
            }
        }
        for (Map<String, Object> o : m.environments) {
            if (envCodes.contains(o.get("code"))) {
                out.add("env:" + o.get("code"));
            }
        }
        for (Map<String, Object> o : m.modules) {
            String key = o.get("applicationId") + "/" + o.get("code");
            if (moduleKeys.contains(key)) {
                out.add("module:" + key);
            }
        }
        for (Map<String, Object> o : m.users) {
            String email = str(o.get("email"));
            if (email != null && userEmails.contains(email.toLowerCase())) {
                out.add("user:" + email);
            }
        }
        for (Map<String, Object> o : m.templates) {
            if (templateCodes.contains(o.get("code"))) {
                out.add("template:" + o.get("code"));
            }
        }
        for (Map<String, Object> o : m.tokens) {
            if (tokenHashes.contains(o.get("tokenHash"))) {
                out.add("token:" + o.get("tokenPrefix"));
            }
        }
        for (Map<String, Object> o : m.pats) {
            if (patHashes.contains(o.get("tokenHash"))) {
                out.add("pat:" + o.get("tokenPrefix"));
            }
        }
        return out;
    }

    private List<Object[]> existingModuleKeys() {
        List<Object[]> out = new ArrayList<>();
        for (ci.fcloud.manager.module.Module md : moduleMapper.selectList(null)) {
            out.add(new Object[]{md.getApplicationId(), md.getCode()});
        }
        return out;
    }

    private List<Object[]> existingVariableKeys() {
        List<Object[]> out = new ArrayList<>();
        for (Variable v : variableMapper.selectList(null)) {
            out.add(new Object[]{v.getModuleId(), v.getVarKey()});
        }
        return out;
    }

    /**
     * §9.2 OVERWRITE：清空全部业务表后导入。
     * 用单条 TRUNCATE ... CASCADE 保证外键顺序无关，且比逐表 DELETE 快。
     * 不含审计日志、访问日志、备份记录（非配置数据）。
     */
    private void clearBusinessTables() {
        jdbcTemplate.execute("TRUNCATE TABLE "
                + "fch_variable_override, fch_variable, fch_file_version, fch_file, "
                + "fch_module_member, fch_module_subscription, fch_module_env, "
                + "fch_module, fch_application, fch_environment, "
                + "fch_template_var, fch_template, fch_api_token, fch_pat, fch_user "
                + "CASCADE");
        // fch_system_setting 故意不参与 TRUNCATE：旧备份包（v1）里没有 settings，
        // 清空会把 GitLab 接入配置一起抹掉，导致恢复完登不进来。
    }

    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ---------------- 逐表插入（保持原始 ID） ----------------

    private int insertUsers(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            User u = new User();
            u.setId(num(o.get("id")));
            u.setEmail(str(o.get("email")));
            u.setName(str(o.get("name")));
            u.setNickname(str(o.get("nickname")));
            u.setAvatarUrl(str(o.get("avatarUrl")));
            u.setGitlabId(str(o.get("gitlabId")));
            u.setGlobalRole(str(o.get("globalRole")));
            u.setLastLoginAt(instant(o.get("lastLoginAt")));
            u.setCreatedAt(instant(o.get("createdAt")));
            u.setUpdatedAt(instant(o.get("updatedAt")));
            userMapper.insert(u);
            n++;
        }
        return n;
    }

    private int insertEnvs(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            Environment e = new Environment();
            e.setId(num(o.get("id")));
            e.setCode(str(o.get("code")));
            e.setName(str(o.get("name")));
            e.setColor(str(o.get("color")));
            e.setSortOrder(intval(o.get("sortOrder")));
            e.setIsDefault(bool(o.get("isDefault")));
            e.setParentEnvId(num(o.get("parentEnvId")));
            e.setCreatedAt(instant(o.get("createdAt")));
            e.setUpdatedAt(instant(o.get("updatedAt")));
            environmentMapper.insert(e);
            n++;
        }
        return n;
    }

    private int insertApps(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            Application a = new Application();
            a.setId(num(o.get("id")));
            a.setCode(str(o.get("code")));
            a.setName(str(o.get("name")));
            a.setDescription(str(o.get("description")));
            a.setSortOrder(intval(o.get("sortOrder")));
            a.setGitlabProjectId(num(o.get("gitlabProjectId")));
            a.setRepoUrl(str(o.get("repoUrl")));
            a.setCreatedBy(num(o.get("createdBy")));
            a.setCreatedAt(instant(o.get("createdAt")));
            a.setUpdatedAt(instant(o.get("updatedAt")));
            applicationMapper.insert(a);
            n++;
        }
        return n;
    }

    private int insertModules(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            ci.fcloud.manager.module.Module md = new ci.fcloud.manager.module.Module();
            md.setId(num(o.get("id")));
            md.setApplicationId(num(o.get("applicationId")));
            md.setCode(str(o.get("code")));
            md.setName(str(o.get("name")));
            md.setDescription(str(o.get("description")));
            md.setIsShared(bool(o.get("isShared")));
            md.setGroupName(str(o.get("groupName")));
            md.setTemplateId(num(o.get("templateId")));
            md.setTemplateSyncedAt(instant(o.get("templateSyncedAt")));
            md.setSortOrder(intval(o.get("sortOrder")));
            md.setCreatedBy(num(o.get("createdBy")));
            md.setCreatedAt(instant(o.get("createdAt")));
            md.setUpdatedAt(instant(o.get("updatedAt")));
            moduleMapper.insert(md);
            n++;
        }
        return n;
    }

    private int insertModuleEnvs(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            ModuleEnv me = new ModuleEnv();
            me.setId(num(o.get("id")));
            me.setModuleId(num(o.get("moduleId")));
            me.setEnvironmentId(num(o.get("environmentId")));
            moduleEnvMapper.insert(me);
            n++;
        }
        return n;
    }

    private int insertSubscriptions(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            ModuleSubscription s = new ModuleSubscription();
            s.setId(num(o.get("id")));
            s.setModuleId(num(o.get("moduleId")));
            s.setApplicationId(num(o.get("applicationId")));
            s.setCreatedBy(num(o.get("createdBy")));
            s.setCreatedAt(instant(o.get("createdAt")));
            subscriptionMapper.insert(s);
            n++;
        }
        return n;
    }

    private int insertMembers(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            ModuleMember mm = new ModuleMember();
            mm.setId(num(o.get("id")));
            mm.setModuleId(num(o.get("moduleId")));
            mm.setUserId(num(o.get("userId")));
            mm.setRole(str(o.get("role")));
            mm.setCreatedBy(num(o.get("createdBy")));
            mm.setCreatedAt(instant(o.get("createdAt")));
            memberMapper.insert(mm);
            n++;
        }
        return n;
    }

    private int insertVariables(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            Variable v = new Variable();
            v.setId(num(o.get("id")));
            v.setModuleId(num(o.get("moduleId")));
            v.setVarKey(str(o.get("varKey")));
            v.setValueType(str(o.get("valueType")));
            v.setGlobalValue(str(o.get("globalValue")));
            v.setRemark(str(o.get("remark")));
            v.setIsRequired(bool(o.get("isRequired")));
            v.setSortOrder(intval(o.get("sortOrder")));
            v.setCreatedBy(num(o.get("createdBy")));
            v.setCreatedAt(instant(o.get("createdAt")));
            v.setUpdatedAt(instant(o.get("updatedAt")));
            variableMapper.insert(v);
            n++;
        }
        return n;
    }

    private int insertOverrides(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            VariableOverride ov = new VariableOverride();
            ov.setId(num(o.get("id")));
            ov.setVariableId(num(o.get("variableId")));
            ov.setEnvironmentId(num(o.get("environmentId")));
            ov.setApplicationId(num(o.get("applicationId")));
            ov.setValue(str(o.get("value")));
            ov.setUpdatedBy(num(o.get("updatedBy")));
            ov.setUpdatedAt(instant(o.get("updatedAt")));
            overrideMapper.insert(ov);
            n++;
        }
        return n;
    }

    private int insertFiles(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            ModuleFile f = new ModuleFile();
            f.setId(num(o.get("id")));
            f.setModuleId(num(o.get("moduleId")));
            f.setEnvironmentId(num(o.get("environmentId")));
            f.setFileKey(str(o.get("fileKey")));
            f.setRemark(str(o.get("remark")));
            f.setCurrentVersion(intval(o.get("currentVersion")));
            f.setCreatedBy(num(o.get("createdBy")));
            f.setCreatedAt(instant(o.get("createdAt")));
            f.setUpdatedAt(instant(o.get("updatedAt")));
            fileMapper.insert(f);
            n++;
        }
        return n;
    }

    private int insertFileVersions(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            FileVersion fv = new FileVersion();
            fv.setId(num(o.get("id")));
            fv.setFileId(num(o.get("fileId")));
            fv.setVersion(intval(o.get("version")));
            fv.setStoragePath(str(o.get("storagePath")));
            fv.setOriginalName(str(o.get("originalName")));
            fv.setMimeType(str(o.get("mimeType")));
            fv.setSize(longval(o.get("size")));
            fv.setUploadedBy(num(o.get("uploadedBy")));
            fv.setCreatedAt(instant(o.get("createdAt")));
            fileVersionMapper.insert(fv);
            n++;
        }
        return n;
    }

    private int insertTemplates(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            Template t = new Template();
            t.setId(num(o.get("id")));
            t.setCode(str(o.get("code")));
            t.setName(str(o.get("name")));
            t.setDescription(str(o.get("description")));
            t.setCreatedBy(num(o.get("createdBy")));
            t.setCreatedAt(instant(o.get("createdAt")));
            t.setUpdatedAt(instant(o.get("updatedAt")));
            templateMapper.insert(t);
            n++;
        }
        return n;
    }

    private int insertTemplateVars(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            TemplateVar tv = new TemplateVar();
            tv.setId(num(o.get("id")));
            tv.setTemplateId(num(o.get("templateId")));
            tv.setVarKey(str(o.get("varKey")));
            tv.setValueType(str(o.get("valueType")));
            tv.setRemark(str(o.get("remark")));
            tv.setDefaultValue(str(o.get("defaultValue")));
            tv.setIsRequired(bool(o.get("isRequired")));
            tv.setSortOrder(intval(o.get("sortOrder")));
            templateVarMapper.insert(tv);
            n++;
        }
        return n;
    }

    private int insertTokens(List<Map<String, Object>> rows, Set<Long> skip) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            if (skip.contains(num(o.get("id")))) {
                continue;
            }
            ApiToken t = new ApiToken();
            t.setId(num(o.get("id")));
            t.setName(str(o.get("name")));
            t.setTokenHash(str(o.get("tokenHash")));
            t.setTokenPrefix(str(o.get("tokenPrefix")));
            t.setExpiresAt(instant(o.get("expiresAt")));
            t.setRevoked(bool(o.get("revoked")));
            t.setCreatedBy(num(o.get("createdBy")));
            t.setCreatedAt(instant(o.get("createdAt")));
            t.setLastUsedAt(instant(o.get("lastUsedAt")));
            tokenMapper.insert(t);
            n++;
        }
        return n;
    }

    /**
     * PAT 绑定用户：恢复时用户可能按 email 命中已有账号（id 不同），
     * 先按 id 找，找不到再按 email 找回真实 id，避免外键失败。
     */
    private int insertPats(List<Map<String, Object>> rows,
                           List<Map<String, Object>> manifestUsers,
                           Set<Long> skip) {
        Map<Long, String> emailById = new HashMap<>();
        for (Map<String, Object> u : manifestUsers) {
            emailById.put(num(u.get("id")), str(u.get("email")));
        }
        int n = 0;
        for (Map<String, Object> o : rows) {
            Long oldId = num(o.get("id"));
            if (skip.contains(oldId)) {
                continue;
            }
            Long userId = resolveUserId(num(o.get("userId")), emailById);
            if (userId == null) {
                log.warn("skip PAT '{}': owner user {} not found after restore",
                        o.get("name"), o.get("userId"));
                continue;
            }
            Pat p = new Pat();
            p.setId(oldId);
            p.setUserId(userId);
            p.setName(str(o.get("name")));
            p.setTokenHash(str(o.get("tokenHash")));
            p.setTokenPrefix(str(o.get("tokenPrefix")));
            p.setScopes(str(o.get("scopes")));
            p.setExpiresAt(instant(o.get("expiresAt")));
            p.setRevoked(bool(o.get("revoked")));
            p.setCreatedAt(instant(o.get("createdAt")));
            p.setLastUsedAt(instant(o.get("lastUsedAt")));
            patMapper.insert(p);
            n++;
        }
        return n;
    }

    private Long resolveUserId(Long oldUserId, Map<Long, String> emailById) {
        if (oldUserId == null) {
            return null;
        }
        if (userMapper.selectById(oldUserId) != null) {
            return oldUserId;
        }
        String email = emailById.get(oldUserId);
        if (email == null) {
            return null;
        }
        User u = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, email));
        return u == null ? null : u.getId();
    }

    /**
     * 系统设置按主键 upsert。
     * OVERWRITE 覆盖；ABORT / MERGE 只补目标库缺失的键，不动现场配置。
     */
    private int upsertSettings(List<Map<String, Object>> rows, boolean overwrite) {
        int n = 0;
        for (Map<String, Object> o : rows) {
            String key = str(o.get("settingKey"));
            if (key == null || key.isEmpty()) {
                continue;
            }
            SystemSetting exist = settingMapper.selectById(key);
            if (exist != null && !overwrite) {
                continue;
            }
            SystemSetting st = new SystemSetting();
            st.setSettingKey(key);
            st.setSettingValue(str(o.get("settingValue")));
            st.setEncrypted(bool(o.get("encrypted")));
            st.setUpdatedBy(num(o.get("updatedBy")));
            st.setUpdatedAt(exist == null ? instant(o.get("updatedAt")) : Instant.now());
            if (exist == null) {
                settingMapper.insert(st);
            } else {
                settingMapper.updateById(st);
            }
            n++;
        }
        return n;
    }

    /**
     * 把 files/ 下的二进制解包到 storage 对应路径。
     * 包内路径：files/{moduleId}/{fileId}/v{n}/{original}
     */
    private int extractFileBinaries(Path tarGz) {
        int n = 0;
        try (InputStream in = Files.newInputStream(tarGz);
             GzipCompressorInputStream gz = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                if (!e.getName().startsWith(FILES_DIR) || e.isDirectory()) {
                    continue;
                }
                String rel = e.getName().substring(FILES_DIR.length());
                Path target = Paths.get(props.getStorage().getRoot(), rel);
                Files.createDirectories(target.getParent());
                Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        } catch (IOException ex) {
            log.warn("extract file binaries partially failed: {}", ex.getMessage());
        }
        return n;
    }

    // ---------------- 类型转换 ----------------

    /**
     * manifest 里的 Long 会被 JacksonConfig 的 ToStringSerializer 写成字符串
     * （雪花 ID 序列化规则对 ObjectMapper 全局生效），因此这里必须同时接受
     * Number 和 String，否则恢复时直接 ClassCastException。
     */
    private static Long num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    private static Long longval(Object o) {
        return num(o);
    }

    private static Integer intval(Object o) {
        Long v = num(o);
        return v == null ? null : v.intValue();
    }

    private static Boolean bool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(o.toString());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- DTO ----------------

    /**
     * §9.1 manifest.json 结构。
     *
     * 所有表以 Map 序列化，并强制写出 null 字段（不用全局的
     * default-property-inclusion=non_null），保证恢复时键始终存在。
     */
    public static class Manifest {
        public int version;
        public Instant backupAt;
        public List<Map<String, Object>> users = new ArrayList<>();
        public List<Map<String, Object>> environments = new ArrayList<>();
        public List<Map<String, Object>> applications = new ArrayList<>();
        public List<Map<String, Object>> modules = new ArrayList<>();
        public List<Map<String, Object>> moduleEnvs = new ArrayList<>();
        public List<Map<String, Object>> subscriptions = new ArrayList<>();
        public List<Map<String, Object>> members = new ArrayList<>();
        public List<Map<String, Object>> variables = new ArrayList<>();
        public List<Map<String, Object>> overrides = new ArrayList<>();
        public List<Map<String, Object>> files = new ArrayList<>();
        public List<Map<String, Object>> fileVersions = new ArrayList<>();
        public List<Map<String, Object>> templates = new ArrayList<>();
        public List<Map<String, Object>> templateVars = new ArrayList<>();
        public List<Map<String, Object>> tokens = new ArrayList<>();
        /** v2 新增：MCP 个人令牌（仅 hash） */
        public List<Map<String, Object>> pats = new ArrayList<>();
        /** v2 新增：系统设置（GitLab 接入、MCP 开关；加密项为密文） */
        public List<Map<String, Object>> settings = new ArrayList<>();
    }

    public record RestoreResult(String strategy, int records, int files) {
    }
}
