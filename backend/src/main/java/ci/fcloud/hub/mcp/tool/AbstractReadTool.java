package ci.fcloud.hub.mcp.tool;

import java.util.List;

import ci.fcloud.hub.app.Application;
import ci.fcloud.hub.app.ApplicationMapper;
import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.env.EnvironmentService;
import ci.fcloud.hub.file.FileService;
import ci.fcloud.hub.mcp.McpToolException;
import ci.fcloud.hub.module.Module;
import ci.fcloud.hub.module.ModuleService;
import ci.fcloud.hub.module.VariableResolver;
import ci.fcloud.hub.module.VariableService;
import ci.fcloud.hub.pipeline.PipelineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 只读工具基类：统一参数读取与实体解析。
 *
 * <p>模块解析刻意复用 {@link PipelineService} 的归属校验
 * （私有模块属于该应用 / 共享模块已被该应用订阅），
 * 避免 MCP 与流水线 API 对"能不能访问"给出不同答案。</p>
 */
public abstract class AbstractReadTool implements McpTool {

    @Autowired
    protected PipelineService pipelineService;
    @Autowired
    protected ModuleService moduleService;
    @Autowired
    protected EnvironmentService environmentService;
    @Autowired
    protected VariableService variableService;
    @Autowired
    protected VariableResolver variableResolver;
    @Autowired
    protected FileService fileService;
    @Autowired
    protected ApplicationMapper applicationMapper;
    @Autowired
    protected ci.fcloud.hub.config.FcloudProperties props;

    protected String requireStr(java.util.Map<String, Object> args, String key) {
        return Schemas.requireString(args, key);
    }

    protected String optStr(java.util.Map<String, Object> args, String key) {
        return Schemas.optString(args, key);
    }

    protected List<String> optList(java.util.Map<String, Object> args, String key) {
        return Schemas.optStringList(args, key);
    }

    protected Application requireApp(String code) {
        return pipelineService.requireApplication(code);
    }

    protected Environment requireEnv(String code) {
        return environmentService.requireByCode(code);
    }

    /**
     * 解析模块。指定 application 时走归属校验；未指定时要求 code 全局唯一。
     */
    protected Module requireModule(String code, String appCode) {
        if (appCode != null && !appCode.isBlank()) {
            Application app = pipelineService.requireApplication(appCode);
            return pipelineService.resolveModuleForApp(app, code);
        }
        List<Module> hits = moduleService.lambdaQuery()
                .eq(Module::getCode, code)
                .list();
        if (hits.isEmpty()) {
            throw new McpToolException("模块不存在: " + code);
        }
        if (hits.size() > 1) {
            List<String> apps = hits.stream()
                    .map(m -> m.getApplicationId() == null ? "shared" : String.valueOf(m.getApplicationId()))
                    .toList();
            throw new McpToolException("模块 code " + code + " 存在于多个应用（" + String.join(", ", apps)
                    + "），请用 application 参数指定");
        }
        return hits.get(0);
    }

    /**
     * 解析时使用的 applicationId：
     * 私有模块只认模块级/全局值（返回 null）；共享模块才有应用级覆盖。
     */
    protected Long overrideAppId(Module m, String appCode) {
        if (m.getApplicationId() != null) {
            return null;
        }
        if (appCode == null || appCode.isBlank()) {
            return null;
        }
        return pipelineService.requireApplication(appCode).getId();
    }

    protected List<Application> allApplications() {
        return applicationMapper.selectList(new LambdaQueryWrapper<Application>()
                .orderByAsc(Application::getSortOrder)
                .orderByAsc(Application::getId));
    }
}
