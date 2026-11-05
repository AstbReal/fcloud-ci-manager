package ci.fcloud.manager.file;

import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.config.FcloudProperties;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ModuleService;
import ci.fcloud.manager.module.PermissionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * §2.4 / §5.6 文件版本管理。
 *
 * <pre>
 * 存储路径：{storage.root}/{appCode 或 _shared}/{moduleCode}/{envCode}/v{version}/{fileKey}
 * 维度：module + environment + file_key 唯一；同名 key 上传即生成新版本
 * 版本保留最近 3 个，第 4 个上传后自动删最旧（磁盘文件 + 记录）
 * 单文件 ≤ 50MB；单模块所有文件总量 ≤ 500MB（可配置）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService extends ServiceImpl<ModuleFileMapper, ModuleFile> {

    private final FileVersionMapper versionMapper;
    private final ModuleService moduleService;
    private final ci.fcloud.manager.app.ApplicationMapper applicationMapper;
    private final PermissionService permissionService;
    private final FcloudProperties props;
    private final ci.fcloud.manager.env.EnvironmentMapper environmentMapper;

    public List<ModuleFile> listByModule(Long moduleId, Long environmentId) {
        var w = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                .eq(ModuleFile::getModuleId, moduleId);
        if (environmentId != null) {
            w.eq(ModuleFile::getEnvironmentId, environmentId);
        }
        return baseMapper.selectList(w.orderByAsc(ModuleFile::getFileKey));
    }

    public List<FileVersion> versionsOf(Long fileId) {
        return versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileId, fileId)
                        .orderByDesc(FileVersion::getVersion));
    }

    public ModuleFile requireById(Long id) {
        ModuleFile f = getById(id);
        if (f == null) {
            throw BizException.notFound("文件不存在: " + id);
        }
        return f;
    }

    /**
     * §5.6 上传即新版本。
     */
    @Transactional
    public ModuleFile upload(Long moduleId, Long environmentId, String fileKey,
                             MultipartFile multipart, String remark) {
        permissionService.requireCanEdit(moduleId);
        fileKey = requireFlatFileKey(fileKey);
        if (multipart == null || multipart.isEmpty()) {
            throw BizException.badRequest("文件内容为空");
        }
        long maxBytes = props.getStorage().getMaxFileSizeMb() * 1024L * 1024L;
        if (multipart.getSize() > maxBytes) {
            throw BizException.badRequest(
                    "单文件超过 " + props.getStorage().getMaxFileSizeMb() + "MB 限制");
        }

        // 单模块总量校验（§2.4）
        validateModuleTotal(moduleId, multipart.getSize());

        Module m = moduleService.requireById(moduleId);

        ModuleFile file = lambdaQuery()
                .eq(ModuleFile::getModuleId, moduleId)
                .eq(ModuleFile::getEnvironmentId, environmentId)
                .eq(ModuleFile::getFileKey, fileKey)
                .one();
        int nextVersion;
        if (file == null) {
            file = new ModuleFile();
            file.setModuleId(moduleId);
            file.setEnvironmentId(environmentId);
            file.setFileKey(fileKey);
            file.setRemark(remark);
            file.setCurrentVersion(0);
            file.setOrigin("MODULE");
            file.setCreatedBy(SecurityUtils.currentUserId());
            save(file);
            nextVersion = 1;
        } else {
            nextVersion = (file.getCurrentVersion() == null ? 0 : file.getCurrentVersion()) + 1;
        }

        // §2.4 路径：{storage.root}/{appCode 或 _shared}/{moduleCode}/{envCode}/v{version}/{fileKey}
        Path target = targetFor(m, environmentId, nextVersion, fileKey);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = multipart.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException(50000, "文件写入失败");
        }

        FileVersion fv = new FileVersion();
        fv.setFileId(file.getId());
        fv.setVersion(nextVersion);
        fv.setStoragePath(relative(target));
        fv.setOriginalName(multipart.getOriginalFilename() == null
                ? fileKey : multipart.getOriginalFilename());
        fv.setMimeType(multipart.getContentType());
        fv.setSize(multipart.getSize());
        fv.setSha256(sha256(target));
        fv.setUploadedBy(SecurityUtils.currentUserId());
        versionMapper.insert(fv);

        file.setCurrentVersion(nextVersion);
        // 用户手动上传即接管该 key：即使它原先来自模板，也不再被模板同步覆盖。
        file.setOrigin("MODULE");
        if (remark != null) {
            file.setRemark(remark);
        }
        updateById(file);

        // §2.4 保留最近 3 个
        pruneOldVersions(file, nextVersion);
        return file;
    }

    /**
     * fileKey 只能是文件名，不允许带目录。
     *
     * <p>「模块 × 环境」已经是文件唯一维度，再叠一层目录只会让同一份配置出现
     * 两个 key（`deployment.yaml` 与 `deployment/deployment.yaml`），
     * 后续谁覆盖谁说不清。真正需要区分就改文件名。</p>
     */
    public static String requireFlatFileKey(String fileKey) {
        String key = fileKey == null ? "" : fileKey.trim();
        if (key.isEmpty()) {
            throw BizException.badRequest("fileKey 不能为空");
        }
        if (key.contains("/") || key.contains("\\")) {
            throw BizException.badRequest(
                    "fileKey 只能是文件名，不能带目录：" + fileKey);
        }
        if (".".equals(key) || "..".equals(key)) {
            throw BizException.badRequest("fileKey 不合法：" + fileKey);
        }
        return key;
    }

    /**
     * fileKey → 目标路径。
     *
     * <p>key 已由 {@link #requireFlatFileKey} 限制为扁平文件名，这里仍然做一次
     * 归一化与越界校验，防止绕过校验的调用把文件写到 storage root 之外。</p>
     */
    public static Path resolveTarget(Path versionDir, String fileKey) {
        Path base = versionDir.normalize();
        Path target = base.resolve(fileKey).normalize();
        if (target.equals(base) || !target.startsWith(base)) {
            throw BizException.badRequest("fileKey 不合法：不能为空、不能包含 .. 或指向目录之外");
        }
        return target;
    }

    /**
     * §2.4 第 4 个上传后自动删最旧（磁盘文件 + 记录）。
     */
    private void pruneOldVersions(ModuleFile file, int currentVersion) {
        int keep = props.getStorage().getKeepVersions();
        List<FileVersion> all = versionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileId, file.getId())
                        .orderByAsc(FileVersion::getVersion));
        while (all.size() > keep) {
            FileVersion oldest = all.remove(0);
            try {
                Files.deleteIfExists(Paths.get(props.getStorage().getRoot(),
                        oldest.getStoragePath()));
            } catch (IOException e) {
                log.warn("delete old version file failed: {}", oldest.getStoragePath());
            }
            versionMapper.deleteById(oldest.getId());
        }
    }

    /**
     * §2.4 单模块所有文件总量 ≤ 500MB。
     */
    private void validateModuleTotal(Long moduleId, long incomingSize) {
        long maxBytes = props.getStorage().getMaxModuleTotalMb() * 1024L * 1024L;
        List<ModuleFile> files = lambdaQuery().eq(ModuleFile::getModuleId, moduleId).list();
        long total = 0;
        for (ModuleFile f : files) {
            total += currentVersionSize(f);
        }
        if (total + incomingSize > maxBytes) {
            throw BizException.badRequest(
                    "模块文件总量超过 " + props.getStorage().getMaxModuleTotalMb() + "MB 限制");
        }
    }

    private long currentVersionSize(ModuleFile f) {
        FileVersion v = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                        .eq(FileVersion::getFileId, f.getId())
                        .eq(FileVersion::getVersion, f.getCurrentVersion()));
        return v == null || v.getSize() == null ? 0 : v.getSize();
    }

    private String envCode(Long environmentId) {
        ci.fcloud.manager.env.Environment e = environmentMapper.selectById(environmentId);
        return e == null ? String.valueOf(environmentId) : e.getCode();
    }

    public ModuleFile updateRemark(Long fileId, String remark) {
        ModuleFile f = requireById(fileId);
        permissionService.requireCanEdit(f.getModuleId());
        f.setRemark(remark);
        updateById(f);
        return f;
    }

    @Transactional
    public void delete(Long fileId) {
        permissionService.requireCanDelete();
        ModuleFile f = requireById(fileId);
        permissionService.requireCanEdit(f.getModuleId());
        deleteInternal(f);
    }

    /**
     * 不做权限校验的删除，供模板同步内部使用（调用方已校验系统管理员）。
     */
    @Transactional
    public void deleteInternal(ModuleFile file) {
        for (FileVersion v : versionsOf(file.getId())) {
            try {
                Files.deleteIfExists(Paths.get(props.getStorage().getRoot(), v.getStoragePath()));
            } catch (IOException e) {
                log.warn("delete file failed: {}", v.getStoragePath());
            }
            versionMapper.deleteById(v.getId());
        }
        removeById(file.getId());
    }

    /**
     * 把模板文件实体化成模块文件版本。
     *
     * <p>调用方必须先确认同 key 的模块文件不是 {@code origin = MODULE}，
     * 否则会覆盖模块自有内容。内容 sha256 一致时不产生新版本，只更新备注。</p>
     *
     * @return 实际写入的模块文件；内容未变化且备注未变时返回 null
     */
    @Transactional
    public ModuleFile materializeFromTemplate(Long moduleId, Long environmentId,
                                              String fileKey, Path source,
                                              String originalName, String mimeType,
                                              String sha256, String remark) {
        Module m = moduleService.requireById(moduleId);
        ModuleFile file = lambdaQuery()
                .eq(ModuleFile::getModuleId, moduleId)
                .eq(ModuleFile::getEnvironmentId, environmentId)
                .eq(ModuleFile::getFileKey, fileKey)
                .one();
        if (file != null && !"TEMPLATE".equals(file.getOrigin())) {
            // 模块自有内容优先：由调用方按冲突计数，不抛异常中断整批绑定。
            return null;
        }
        if (file == null) {
            file = new ModuleFile();
            file.setModuleId(moduleId);
            file.setEnvironmentId(environmentId);
            file.setFileKey(fileKey);
            file.setRemark(remark);
            file.setCurrentVersion(0);
            file.setOrigin("TEMPLATE");
            file.setCreatedBy(SecurityUtils.currentUserId());
            save(file);
        } else {
            FileVersion current = versionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                            .eq(FileVersion::getFileId, file.getId())
                            .eq(FileVersion::getVersion, file.getCurrentVersion()));
            if (current != null && sha256 != null && sha256.equals(current.getSha256())) {
                if (remark != null && !remark.equals(file.getRemark())) {
                    file.setRemark(remark);
                    updateById(file);
                    return file;
                }
                return null;
            }
        }

        int nextVersion = (file.getCurrentVersion() == null ? 0 : file.getCurrentVersion()) + 1;
        Path target = targetFor(m, environmentId, nextVersion, fileKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException(50000, "模板文件写入失败");
        }

        FileVersion fv = new FileVersion();
        fv.setFileId(file.getId());
        fv.setVersion(nextVersion);
        fv.setStoragePath(relative(target));
        fv.setOriginalName(originalName == null ? fileKey : originalName);
        fv.setMimeType(mimeType);
        fv.setSize(Files.exists(target) ? sizeOf(target) : 0L);
        fv.setSha256(sha256);
        fv.setUploadedBy(SecurityUtils.currentUserId());
        versionMapper.insert(fv);

        file.setCurrentVersion(nextVersion);
        if (remark != null) {
            file.setRemark(remark);
        }
        updateById(file);
        pruneOldVersions(file, nextVersion);
        return file;
    }

    /** 相对 storage.root 的路径，统一用 / 分隔，跨平台可读 */
    private String relative(Path target) {
        return String.valueOf(Paths.get(props.getStorage().getRoot()).relativize(target))
                .replace('\\', '/');
    }

    /**
     * 模块文件的版本落盘路径。
     */
    private Path targetFor(Module m, Long environmentId, int version, String fileKey) {
        String appCode;
        if (m.getApplicationId() == null) {
            appCode = "_shared";
        } else {
            ci.fcloud.manager.app.Application a =
                    applicationMapper.selectById(m.getApplicationId());
            appCode = a == null ? String.valueOf(m.getApplicationId()) : a.getCode();
        }
        Path dir = Paths.get(props.getStorage().getRoot(), appCode, m.getCode(),
                envCode(environmentId), "v" + version);
        return resolveTarget(dir, fileKey);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** SHA-256（hex，小写）。读取失败返回 null，同步时退化为版本号比较。 */
    public static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path);
             DigestInputStream dis = new DigestInputStream(in,
                     MessageDigest.getInstance("SHA-256"))) {
            byte[] buf = new byte[8192];
            while (dis.read(buf) > 0) {
                // digest 由 DigestInputStream 累积
            }
            return HexFormat.of().formatHex(dis.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * §5.6 下载：version 省略 = 最新版本。
     */
    public FileVersion resolveVersion(Long fileId, Integer version) {
        requireById(fileId);
        FileVersion v;
        if (version == null) {
            v = versionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                            .eq(FileVersion::getFileId, fileId)
                            .orderByDesc(FileVersion::getVersion)
                            .last("limit 1"));
        } else {
            v = versionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                            .eq(FileVersion::getFileId, fileId)
                            .eq(FileVersion::getVersion, version));
        }
        if (v == null) {
            throw BizException.notFound("文件版本不存在");
        }
        return v;
    }

    public Path resolvePath(FileVersion v) {
        return Paths.get(props.getStorage().getRoot(), v.getStoragePath());
    }
}
