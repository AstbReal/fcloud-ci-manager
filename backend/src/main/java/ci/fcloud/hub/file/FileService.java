package ci.fcloud.hub.file;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.SecurityUtils;
import ci.fcloud.hub.config.FcloudProperties;
import ci.fcloud.hub.module.Module;
import ci.fcloud.hub.module.ModuleMapper;
import ci.fcloud.hub.module.ModuleService;
import ci.fcloud.hub.module.PermissionService;
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
    private final ci.fcloud.hub.app.ApplicationMapper applicationMapper;
    private final PermissionService permissionService;
    private final FcloudProperties props;
    private final ci.fcloud.hub.env.EnvironmentMapper environmentMapper;

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
        // §2.4 存储路径首段：私有模块用应用 code，共享模块用 _shared
        String appCode;
        if (m.getApplicationId() == null) {
            appCode = "_shared";
        } else {
            ci.fcloud.hub.app.Application a = applicationMapper.selectById(m.getApplicationId());
            appCode = a == null ? String.valueOf(m.getApplicationId()) : a.getCode();
        }
        String envCode = envCode(environmentId);

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
            file.setCreatedBy(SecurityUtils.currentUserId());
            save(file);
            nextVersion = 1;
        } else {
            nextVersion = (file.getCurrentVersion() == null ? 0 : file.getCurrentVersion()) + 1;
        }

        // §2.4 路径：{storage.root}/{appCode 或 _shared}/{moduleCode}/{envCode}/v{version}/{fileKey}
        Path dir = Paths.get(props.getStorage().getRoot(), appCode, m.getCode(), envCode,
                "v" + nextVersion);
        Path target = resolveTarget(dir, fileKey);
        try {
            // fileKey 允许带子路径（历史项目按阶段分目录），父目录要一起建
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
        fv.setStoragePath(String.valueOf(
                Paths.get(props.getStorage().getRoot()).relativize(target)));
        fv.setOriginalName(multipart.getOriginalFilename() == null
                ? fileKey : multipart.getOriginalFilename());
        fv.setMimeType(multipart.getContentType());
        fv.setSize(multipart.getSize());
        fv.setUploadedBy(SecurityUtils.currentUserId());
        versionMapper.insert(fv);

        file.setCurrentVersion(nextVersion);
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
    static String requireFlatFileKey(String fileKey) {
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
    static Path resolveTarget(Path versionDir, String fileKey) {
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
        ci.fcloud.hub.env.Environment e = environmentMapper.selectById(environmentId);
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
        List<FileVersion> versions = versionsOf(fileId);
        for (FileVersion v : versions) {
            try {
                Files.deleteIfExists(Paths.get(props.getStorage().getRoot(), v.getStoragePath()));
            } catch (IOException e) {
                log.warn("delete file failed: {}", v.getStoragePath());
            }
            versionMapper.deleteById(v.getId());
        }
        removeById(fileId);
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
