package ci.fcloud.manager.template;

import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.config.FcloudProperties;
import ci.fcloud.manager.file.FileService;
import ci.fcloud.manager.file.ModuleFile;
import ci.fcloud.manager.file.ModuleFileMapper;
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
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模板文件：上传、删除、下发到模块环境。
 *
 * <p>模板文件只存一份（{@code _templates/{templateId}/{fileKey}}），下发时复制成
 * 模块的 {@code fch_file} + {@code fch_file_version}，并用 {@code origin=TEMPLATE}
 * 标记。模块自有同名文件（{@code origin=MODULE}）永远优先，不会被覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateFileService {

    private static final String TEMPLATE_DIR = "_templates";

    private final TemplateFileMapper templateFileMapper;
    private final TemplateMapper templateMapper;
    private final ModuleFileMapper moduleFileMapper;
    private final FileService fileService;
    private final FcloudProperties props;

    public List<TemplateFile> list(Long templateId) {
        return templateFileMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TemplateFile>()
                        .eq(TemplateFile::getTemplateId, templateId)
                        .orderByAsc(TemplateFile::getFileKey));
    }

    public TemplateFile requireById(Long id) {
        TemplateFile f = templateFileMapper.selectById(id);
        if (f == null) {
            throw BizException.notFound("模板文件不存在: " + id);
        }
        return f;
    }

    public Path pathOf(TemplateFile f) {
        return Paths.get(props.getStorage().getRoot(), f.getStoragePath());
    }

    /**
     * 上传/替换模板文件。同名 key 直接覆盖内容，不保留模板侧版本。
     */
    @Transactional
    public TemplateFile upload(Long templateId, String fileKey, MultipartFile multipart,
                               String remark) {
        requireAdmin();
        Template template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BizException.notFound("模板不存在: " + templateId);
        }
        fileKey = FileService.requireFlatFileKey(fileKey);
        if (multipart == null || multipart.isEmpty()) {
            throw BizException.badRequest("文件内容为空");
        }
        long maxBytes = props.getStorage().getMaxFileSizeMb() * 1024L * 1024L;
        if (multipart.getSize() > maxBytes) {
            throw BizException.badRequest(
                    "单文件超过 " + props.getStorage().getMaxFileSizeMb() + "MB 限制");
        }

        Path target = Paths.get(props.getStorage().getRoot(), TEMPLATE_DIR,
                String.valueOf(templateId), fileKey);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = multipart.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException(50000, "模板文件写入失败");
        }

        TemplateFile f = templateFileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TemplateFile>()
                        .eq(TemplateFile::getTemplateId, templateId)
                        .eq(TemplateFile::getFileKey, fileKey));
        boolean create = f == null;
        if (create) {
            f = new TemplateFile();
            f.setTemplateId(templateId);
            f.setFileKey(fileKey);
            f.setCreatedBy(SecurityUtils.currentUserId());
            f.setCreatedAt(Instant.now());
        }
        f.setRemark(remark);
        f.setStoragePath(TEMPLATE_DIR + "/" + templateId + "/" + fileKey);
        f.setOriginalName(multipart.getOriginalFilename() == null
                ? fileKey : multipart.getOriginalFilename());
        f.setMimeType(multipart.getContentType());
        f.setSize(multipart.getSize());
        f.setSha256(FileService.sha256(target));
        if (create) {
            templateFileMapper.insert(f);
        } else {
            templateFileMapper.updateById(f);
        }
        touch(templateId);
        return f;
    }

    @Transactional
    public void delete(Long templateId, Long fileId) {
        requireAdmin();
        TemplateFile f = requireById(fileId);
        if (!templateId.equals(f.getTemplateId())) {
            throw BizException.badRequest("文件不属于该模板");
        }
        try {
            Files.deleteIfExists(pathOf(f));
        } catch (IOException e) {
            log.warn("delete template file failed: {}", f.getStoragePath());
        }
        templateFileMapper.deleteById(fileId);
        touch(templateId);
    }

    /**
     * 模板删除时清理全部文件（数据库行随 fch_template 级联，但磁盘要显式删）。
     */
    @Transactional
    public void deleteAllForTemplate(Long templateId) {
        for (TemplateFile f : list(templateId)) {
            try {
                Files.deleteIfExists(pathOf(f));
            } catch (IOException e) {
                log.warn("delete template file failed: {}", f.getStoragePath());
            }
        }
        templateFileMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TemplateFile>()
                        .eq(TemplateFile::getTemplateId, templateId));
    }

    /**
     * 把模板文件下发到模块的指定环境。
     *
     * <p>只写 {@code origin=TEMPLATE} 的模块文件；同 key 的模块自有文件跳过并计入
     * {@code conflicts}。内容 sha256 相同不会产生新版本。</p>
     */
    @Transactional
    public DistributeResult distribute(Long moduleId, Long templateId,
                                       Collection<Long> environmentIds) {
        List<TemplateFile> files = list(templateId);
        Set<Long> envs = new LinkedHashSet<>();
        if (environmentIds != null) {
            for (Long id : environmentIds) {
                if (id != null) {
                    envs.add(id);
                }
            }
        }
        if (files.isEmpty() || envs.isEmpty()) {
            return new DistributeResult(0, 0, 0);
        }

        int added = 0;
        int updated = 0;
        int conflicts = 0;
        for (Long envId : envs) {
            for (TemplateFile tf : files) {
                Path source = pathOf(tf);
                if (!Files.exists(source)) {
                    log.warn("template file missing on disk, skip: {}", tf.getStoragePath());
                    continue;
                }
                ModuleFile existing = moduleFileMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                                .eq(ModuleFile::getModuleId, moduleId)
                                .eq(ModuleFile::getEnvironmentId, envId)
                                .eq(ModuleFile::getFileKey, tf.getFileKey()));
                if (existing != null && !"TEMPLATE".equals(existing.getOrigin())) {
                    conflicts++;
                    continue;
                }
                String sha = tf.getSha256() != null ? tf.getSha256()
                        : FileService.sha256(source);
                ModuleFile saved = fileService.materializeFromTemplate(moduleId, envId,
                        tf.getFileKey(), source, tf.getOriginalName(), tf.getMimeType(),
                        sha, tf.getRemark());
                if (saved == null) {
                    continue;
                }
                if (existing == null) {
                    added++;
                } else {
                    updated++;
                }
            }
        }
        return new DistributeResult(added, updated, conflicts);
    }

    /**
     * 统计模块里由模板管理、但模板已删除的文件（按 fileKey 去重，带环境数）。
     */
    public List<RemovedFile> removedFiles(Long moduleId, Long templateId) {
        Set<String> templateKeys = new java.util.HashSet<>();
        for (TemplateFile f : list(templateId)) {
            templateKeys.add(f.getFileKey());
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ModuleFile mf : moduleFileMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                        .eq(ModuleFile::getModuleId, moduleId)
                        .eq(ModuleFile::getOrigin, "TEMPLATE"))) {
            if (!templateKeys.contains(mf.getFileKey())) {
                counts.merge(mf.getFileKey(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new RemovedFile(e.getKey(), e.getValue()))
                .toList();
    }

    private void touch(Long templateId) {
        templateMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Template>()
                        .eq(Template::getId, templateId)
                        .set(Template::getUpdatedAt, Instant.now()));
    }

    private static void requireAdmin() {
        if (!SecurityUtils.isSystemAdmin()) {
            throw new BizException(40300, "只有系统管理员可以维护模板文件");
        }
    }

    public record DistributeResult(int added, int updated, int conflicts) {
    }

    public record RemovedFile(String fileKey, int environmentCount) {
    }
}
