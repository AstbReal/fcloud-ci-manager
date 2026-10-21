package ci.fcloud.hub.pipeline;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.file.FileService;
import ci.fcloud.hub.file.FileVersion;
import ci.fcloud.hub.file.ModuleFile;
import ci.fcloud.hub.module.*;
import ci.fcloud.hub.token.ApiToken;
import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.env.EnvironmentService;
import ci.fcloud.hub.app.Application;
import ci.fcloud.hub.app.ApplicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * §4 流水线 API 的业务实现（只读）。
 *
 * 关键约束：
 * - 全环境通用（不做按环境隔离），Token 作用域 = 所属应用（含其订阅的共享模块）
 * - 模块必须属于该应用（私有模块）或该应用已订阅（共享模块），否则 403
 * - 任何变量值都不得写入日志
 */
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final ApplicationMapper applicationMapper;
    private final ModuleService moduleService;
    private final EnvironmentService environmentService;
    private final VariableService variableService;
    private final VariableResolver resolver;
    private final ModuleSubscriptionMapper subscriptionMapper;
    private final FileService fileService;
    private final DotenvCodec dotenvCodec;

    /**
     * §4.2 解析并生成 dotenv。
     *
     * @param modulesParam 逗号分隔的模块 code，必须显式指定
     */
    public String buildConfig(ApiToken token, String applicationCode,
                              String environmentCode, String modulesParam) {
        // Token 自 V3 起为全局作用域，不参与解析；保留参数只为兼容既有调用方。
        // 流水线必须拿到明文（§4.2），因此 revealSecrets = true。
        return buildConfig(applicationCode, environmentCode, modulesParam, true);
    }

    /**
     * §4.2 解析并生成 dotenv（无鉴权版本）。
     *
     * <p>MCP 的 get_config 直接复用，调用方自行保证应用/环境/模块的可见性。
     * 流水线 API 仍走上面的重载，行为完全一致。</p>
     *
     * @param revealSecrets false 时加密变量输出脱敏占位符（MCP 默认），
     *                      true 时输出明文（流水线行为）
     */
    public String buildConfig(String applicationCode, String environmentCode,
                              String modulesParam) {
        return buildConfig(applicationCode, environmentCode, modulesParam, true);
    }

    public String buildConfig(String applicationCode, String environmentCode,
                              String modulesParam, boolean revealSecrets) {
        if (modulesParam == null || modulesParam.isBlank()) {
            throw BizException.badRequest("modules 参数必填（逗号分隔的模块 code）");
        }
        Application app = requireApplication(applicationCode);
        Environment env = environmentService.requireByCode(environmentCode);

        List<String> codes = new ArrayList<>();
        for (String s : modulesParam.split(",")) {
            String c = s.trim();
            if (!c.isEmpty()) {
                codes.add(c);
            }
        }
        if (codes.isEmpty()) {
            throw BizException.badRequest("modules 参数必填（逗号分隔的模块 code）");
        }

        // 按请求参数顺序逐模块输出（§4.2）
        List<DotenvCodec.ModuleEntries> groups = new ArrayList<>();
        for (String code : codes) {
            ci.fcloud.hub.module.Module m = resolveModuleForApp(app, code);
            ensureBoundToEnv(m, env);

            Long ownerAppId = m.getApplicationId() == null ? app.getId() : null;
            List<Variable> vars = variableService.listByModule(m.getId());
            List<DotenvCodec.Entry> entries = new ArrayList<>();
            for (Variable v : vars) {
                // 共享模块：当前应用可对其设置应用级覆盖，故传 app.getId()
                Long appForOverride = m.getApplicationId() == null ? app.getId() : null;
                ResolvedValue rv = resolver.resolveVariable(v, appForOverride, env.getId(),
                        revealSecrets);
                if (rv.isFound()) {
                    entries.add(new DotenvCodec.Entry(v.getVarKey(), rv.getValue(),
                            "ENCRYPTED".equals(v.getValueType())));
                }
            }
            groups.add(new DotenvCodec.ModuleEntries(m.getCode(), entries));
        }
        // 加密变量输出解密明文（§4.2），因此 valuesAlreadyResolved = true
        return dotenvCodec.render(groups, true, null);
    }

    /**
     * §4.3 批量下载文件所需的 (模块, 文件) 列表。
     *
     * @param moduleCodes 模块 code 列表（必填）
     * @param fileKeys    文件 key 列表；为空表示该模块在该环境下的全部文件
     */
    public List<Artifact> collectArtifacts(ApiToken token, String applicationCode,
                                           String environmentCode,
                                           List<String> moduleCodes, List<String> fileKeys) {
        if (moduleCodes == null || moduleCodes.isEmpty()) {
            throw BizException.badRequest("modules 参数必填");
        }
        Application app = requireApplication(applicationCode);
        Environment env = environmentService.requireByCode(environmentCode);

        List<Artifact> out = new ArrayList<>();
        for (String code : moduleCodes) {
            ci.fcloud.hub.module.Module m = resolveModuleForApp(app, code);
            ensureBoundToEnv(m, env);
            List<ModuleFile> files = fileService.listByModule(m.getId(), env.getId());
            for (ModuleFile f : files) {
                if (fileKeys != null && !fileKeys.isEmpty()
                        && !fileKeys.contains(f.getFileKey())) {
                    continue;
                }
                FileVersion v = fileService.resolveVersion(f.getId(), null);
                out.add(new Artifact(m.getCode(), f.getFileKey(), v,
                        fileService.resolvePath(v)));
            }
        }
        return out;
    }

    /**
     * §4.4 单文件下载：校验模块归属 + 文件属于该模块。
     */
    public FileVersion resolveSingleFile(ApiToken token, String moduleId, String fileId,
                                         Integer version) {
        ci.fcloud.hub.module.Module m =
                moduleService.requireById(parseId(moduleId, "moduleId"));
        ModuleFile f = fileService.requireById(parseId(fileId, "fileId"));
        if (!f.getModuleId().equals(m.getId())) {
            throw BizException.badRequest("文件不属于该模块");
        }
        return fileService.resolveVersion(f.getId(), version);
    }

    public java.nio.file.Path pathOf(FileVersion v) {
        return fileService.resolvePath(v);
    }

    // ---------------- 校验 ----------------

    /**
     * 全局 Token 下，应用完全由请求参数决定。
     *
     * <p>MCP 的模块解析也走这里，保证两套入口对「应用不存在」的判定一致。</p>
     */
    public Application requireApplication(String applicationCode) {
        if (applicationCode == null || applicationCode.isBlank()) {
            throw BizException.badRequest("application 参数必填");
        }
        Application app = applicationMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Application>()
                        .eq(Application::getCode, applicationCode)
                        .last("limit 1"));
        if (app == null) {
            throw BizException.notFound("应用不存在: " + applicationCode);
        }
        return app;
    }

    /**
     * 解析模块 code。查找顺序（显式、可审计）：
     * 1. 该应用自己的私有模块（application_id = app.id 且 is_shared = false）
     * 2. 该应用已订阅的共享模块（application_id IS NULL 且 is_shared = true）
     * 两者都不命中 → 403 / 400。
     *
     * 这里不用 "limit 1 取任意同名"，避免跨应用误匹配到别人的私有模块。
     */
    /**
     * 解析模块 code（public 供 MCP 只读工具复用，保证两套入口的归属校验一致）。
     */
    public ci.fcloud.hub.module.Module resolveModuleForApp(Application app, String moduleCode) {
        // 1) 本应用的私有模块
        ci.fcloud.hub.module.Module own = moduleService.lambdaQuery()
                .eq(ci.fcloud.hub.module.Module::getApplicationId, app.getId())
                .eq(ci.fcloud.hub.module.Module::getCode, moduleCode)
                .last("limit 1")
                .one();
        if (own != null) {
            return own;
        }
        // 2) 已订阅的共享模块
        ci.fcloud.hub.module.Module shared = moduleService.lambdaQuery()
                .isNull(ci.fcloud.hub.module.Module::getApplicationId)
                .eq(ci.fcloud.hub.module.Module::getCode, moduleCode)
                .last("limit 1")
                .one();
        if (shared != null) {
            assertModuleAccessible(app, shared);
            return shared;
        }
        // 3) 模块确实存在但属于其他应用 → 403（§4.1：403 = 模块不属于该应用）
        long ownedByOther = moduleService.lambdaQuery()
                .isNotNull(ci.fcloud.hub.module.Module::getApplicationId)
                .eq(ci.fcloud.hub.module.Module::getCode, moduleCode)
                .count();
        if (ownedByOther > 0) {
            throw BizException.forbidden("模块不属于该应用: " + moduleCode);
        }
        // 4) 完全不存在 → 400
        throw BizException.badRequest("模块不存在: " + moduleCode);
    }

    private void assertModuleAccessible(Application app, ci.fcloud.hub.module.Module m) {
        if (m.getApplicationId() != null) {
            // 私有模块：必须属于该应用
            if (!m.getApplicationId().equals(app.getId())) {
                throw BizException.forbidden("模块不属于该应用: " + m.getCode());
            }
            return;
        }
        // 共享模块：该应用必须已订阅
        long subs = subscriptionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                        .eq(ModuleSubscription::getModuleId, m.getId())
                        .eq(ModuleSubscription::getApplicationId, app.getId()));
        if (subs == 0) {
            throw BizException.forbidden("应用未订阅该共享模块: " + m.getCode());
        }
    }

    public void ensureBoundToEnv(ci.fcloud.hub.module.Module m, Environment env) {
        List<Long> bound = moduleService.environmentIdsOf(m.getId());
        if (!bound.contains(env.getId())) {
            throw BizException.badRequest(
                    "模块 " + m.getCode() + " 未绑定环境 " + env.getCode());
        }
    }

    private Long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw BizException.badRequest(field + " 必须是数字");
        }
    }

    /**
     * 一个待打包的文件。
     */
    public record Artifact(String moduleCode, String fileKey, FileVersion version,
                           java.nio.file.Path path) {
    }
}
