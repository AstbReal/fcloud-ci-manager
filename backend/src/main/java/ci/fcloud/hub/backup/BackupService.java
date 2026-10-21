package ci.fcloud.hub.backup;

import ci.fcloud.hub.app.Application;
import ci.fcloud.hub.app.ApplicationMapper;
import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.config.FcloudProperties;
import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.env.EnvironmentMapper;
import ci.fcloud.hub.file.FileVersion;
import ci.fcloud.hub.file.FileVersionMapper;
import ci.fcloud.hub.file.ModuleFile;
import ci.fcloud.hub.file.ModuleFileMapper;
import ci.fcloud.hub.module.*;
import ci.fcloud.hub.template.Template;
import ci.fcloud.hub.template.TemplateMapper;
import ci.fcloud.hub.template.TemplateVar;
import ci.fcloud.hub.template.TemplateVarMapper;
import ci.fcloud.hub.token.ApiToken;
import ci.fcloud.hub.token.ApiTokenMapper;
import ci.fcloud.hub.token.ApiTokenService;
import ci.fcloud.hub.user.User;
import ci.fcloud.hub.user.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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

    // ==================================================================
    // 备份
    // ==================================================================

    /**
     * 创建备份包，返回记录。
     */
    @Transactional
    public BackupRecord create(String type, Long createdBy) {
        Path dir = Paths.get(props.getBackup().getDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException(50000, "无法创建备份目录");
        }

        String fileName = "fcloud-backup-" + LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(TS) + ".tar.gz";
        Path out = dir.resolve(fileName);

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
        } catch (IOException e) {
            throw new BizException(50000, "备份失败: " + e.getClass().getSimpleName());
        }

        BackupRecord rec = new BackupRecord();
        rec.setFileName(fileName);
        rec.setFilePath(out.toString());
        try {
            rec.setSize(Files.size(out));
        } catch (IOException e) {
            rec.setSize(0L);
        }
        rec.setType(type == null ? "MANUAL" : type);
        rec.setCreatedBy(createdBy);
        rec.setCreatedAt(Instant.now());
        backupMapper.insert(rec);

        pruneOld();
        return rec;
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
        m.version = 1;
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
            o.put("createdBy", a.getCreatedBy());
            o.put("createdAt", a.getCreatedAt());
            o.put("updatedAt", a.getUpdatedAt());
            m.applications.add(o);
        }

        for (ci.fcloud.hub.module.Module md : moduleMapper.selectList(null)) {
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

        return m;
    }

    /**
     * §9.2 保留最近 N 份（超出删除文件 + 记录）。
     */
    public void pruneOld() {
        int retain = props.getBackup().getRetain();
        List<BackupRecord> all = backupMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BackupRecord>()
                        .orderByDesc(BackupRecord::getCreatedAt));
        for (int i = retain; i < all.size(); i++) {
            BackupRecord old = all.get(i);
            try {
                Files.deleteIfExists(Paths.get(old.getFilePath()));
            } catch (IOException ignored) {
                // 文件可能已被手动删除
            }
            backupMapper.deleteById(old.getId());
        }
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
    // 恢复
    // ==================================================================

    /**
     * §9.2 恢复。
     *
     * @param strategy ABORT（默认）/ MERGE / OVERWRITE
     */
    @Transactional
    public RestoreResult restore(MultipartFile file, String strategy) {
        String s = strategy == null ? "ABORT" : strategy.toUpperCase();
        if (!Set.of("ABORT", "MERGE", "OVERWRITE").contains(s)) {
            throw BizException.badRequest("strategy 必须是 ABORT / MERGE / OVERWRITE");
        }

        Manifest m;
        Path tmp;
        try {
            tmp = Files.createTempFile("fcloud-restore-", ".tar.gz");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            m = readManifest(tmp);
        } catch (IOException e) {
            throw new BizException(40000, "备份包读取失败");
        }

        List<String> conflicts = detectConflicts(m);

        if ("ABORT".equals(s) && !conflicts.isEmpty()) {
            throw new BizException(40900,
                    "发现 code 冲突，已整体回滚：" + String.join(", ", conflicts));
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
                    // applicationId 可能为 null（共享模块），必须用 Objects.equals
                    if (java.util.Objects.equals(key[0], o.get("applicationId"))
                            && java.util.Objects.equals(key[1], o.get("code"))) {
                        skipModules.add(num(o.get("id")));
                    }
                }
            }
            for (Object[] key : existingVariableKeys()) {
                for (Map<String, Object> o : m.variables) {
                    if (java.util.Objects.equals(key[0], o.get("moduleId"))
                            && java.util.Objects.equals(key[1], o.get("varKey"))) {
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

        // 文件二进制解包到 storage
        int filesRestored = extractFileBinaries(tmp);

        // §9.2 恢复后清理 Redis 中 Token 缓存
        tokenService.clearCache();

        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
        }

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
            if (existingModuleKeys().stream()
                    .anyMatch(k -> (k[0] + "/" + k[1]).equals(key))) {
                out.add("module:" + key);
            }
        }        return out;
    }

    private List<Object[]> existingModuleKeys() {
        List<Object[]> out = new ArrayList<>();
        for (ci.fcloud.hub.module.Module md : moduleMapper.selectList(null)) {
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
                + "fch_template_var, fch_template, fch_api_token, fch_user "
                + "CASCADE");
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
            ci.fcloud.hub.module.Module md = new ci.fcloud.hub.module.Module();
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

    private static Long num(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static Long longval(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static Integer intval(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static Boolean bool(Object o) {
        return o == null ? null : (Boolean) o;
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
    }

    public record RestoreResult(String strategy, int records, int files) {
    }
}
