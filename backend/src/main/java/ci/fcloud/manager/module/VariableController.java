package ci.fcloud.manager.module;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.CryptoService;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.env.EnvironmentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §5.5 变量矩阵。
 * - GET 矩阵：定义 + 各环境解析结果（加密值脱敏 ••••）
 * - 读：登录即可；写：登录即可；删除：仅系统管理员
 */
@RestController
@RequestMapping("/api/console/modules/{moduleId}/variables")
@RequiredArgsConstructor
public class VariableController {

    private final VariableService variableService;
    private final ModuleService moduleService;
    private final VariableResolver resolver;
    private final DotenvCodec dotenv;
    private final CryptoService crypto;
    private final EnvironmentService environmentService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final VariableImportService importService;

    /**
     * §5.5 GET 矩阵：定义 + 各环境解析结果（加密值脱敏）。
     */
    @GetMapping
    public ApiResponse<Matrix> matrix(@PathVariable Long moduleId) {
        permissionService.requireCanView(moduleId);
        Module m = moduleService.requireById(moduleId);
        List<Environment> envs = environmentService.listAll();
        List<Long> boundEnvIds = moduleService.environmentIdsOf(moduleId);

        // 只展示模块绑定的环境（§10.5 列 = 全局值 + 模块绑定的环境）
        List<Environment> shown = envs.stream()
                .filter(e -> boundEnvIds.contains(e.getId()))
                .toList();

        List<Variable> vars = variableService.listByModule(moduleId);
        boolean canReveal = permissionService.canRevealEncrypted(moduleId, null);

        List<Row> rows = new ArrayList<>();
        for (Variable v : vars) {
            Row r = new Row();
            r.setId(v.getId());
            r.setVarKey(v.getVarKey());
            r.setValueType(v.getValueType());
            r.setRemark(v.getRemark());
            r.setIsRequired(v.getIsRequired());
            r.setSortOrder(v.getSortOrder());
            r.setCreatedBy(v.getCreatedBy());
            // 本人创建的加密变量，创建人本人也可看（§6.4）
            boolean revealThis = canReveal
                    || SecurityUtils.currentUserId() != null
                    && SecurityUtils.currentUserId().equals(v.getCreatedBy());
            r.setCanReveal(revealThis && "ENCRYPTED".equals(v.getValueType()));

            Map<Long, Cell> cells = new LinkedHashMap<>();
            // 全局值列
            Cell g = new Cell();
            if (v.getGlobalValue() != null) {
                if ("ENCRYPTED".equals(v.getValueType())) {
                    // 矩阵永远不回传明文：明文只能经 /reveal 读取（会写审计日志）
                    g.setValue(VariableResolver.MASKED_PLACEHOLDER);
                    g.setMasked(true);
                } else {
                    g.setValue(v.getGlobalValue());
                    g.setMasked(false);
                }
                g.setFound(true);
                g.setSource(ResolvedValue.Source.GLOBAL.name());
            }
            cells.put(0L, g);

            for (Environment e : shown) {
                ResolvedValue rv = resolver.resolveVariable(v,
                        m.getApplicationId(), e.getId(), false);
                Cell c = new Cell();
                c.setValue(rv.isFound() ? rv.getValue() : null);
                c.setMasked(rv.isMasked());
                c.setFound(rv.isFound());
                if (rv.isFound()) {
                    c.setSource(rv.getSource().name());
                    c.setInherited(rv.isInherited());
                    c.setOverride(rv.isOverride());
                }
                cells.put(e.getId(), c);
            }
            r.setCells(cells);
            rows.add(r);
        }

        Matrix matrix = new Matrix();
        matrix.setEnvironments(shown);
        matrix.setRows(rows);
        return ApiResponse.ok(matrix);
    }

    @PostMapping
    public ApiResponse<Variable> create(@PathVariable Long moduleId, @RequestBody Variable body) {
        return ApiResponse.ok(variableService.create(moduleId, body));
    }

    /**
     * §10.5 继承链：返回该变量在目标环境下的逐层来源，供 UI popover 展示。
     * 顺序 = 当前环境 → 父环境… → 全局值；只读，不改变解析优先级。
     */
    @GetMapping("/{varId}/chain")
    public ApiResponse<ChainView> chain(@PathVariable Long moduleId, @PathVariable Long varId,
                                        @RequestParam Long environmentId) {
        permissionService.requireCanView(moduleId);
        Variable v = variableService.requireById(varId);
        if (!v.getModuleId().equals(moduleId)) {
            throw new BizException(40000, "变量不属于该模块");
        }
        Module m = moduleService.requireById(moduleId);
        boolean revealThis = permissionService.canRevealEncrypted(moduleId, v.getCreatedBy());

        Map<Long, Environment> envMap = new LinkedHashMap<>();
        for (Environment e : environmentService.listAll()) {
            envMap.put(e.getId(), e);
        }

        List<ChainStop> levels = new ArrayList<>();
        for (VariableResolver.ChainLevel l : resolver.explain(
                v, m.getApplicationId(), environmentId, revealThis)) {
            ChainStop s = new ChainStop();
            s.setEnvironmentId(l.getEnvironmentId());
            s.setDefined(l.isDefined());
            s.setMasked(l.isMasked());
            s.setValue(l.getValue());
            s.setSource(l.getSource());
            Environment e = l.getEnvironmentId() == null ? null : envMap.get(l.getEnvironmentId());
            s.setEnvironmentCode(e == null ? null : e.getCode());
            s.setEnvironmentName(e == null ? "全局值" : e.getName());
            levels.add(s);
        }

        ChainView view = new ChainView();
        view.setVarId(v.getId());
        view.setVarKey(v.getVarKey());
        view.setEnvironmentId(environmentId);
        view.setLevels(levels);
        return ApiResponse.ok(view);
    }

    @PutMapping("/{varId}")
    public ApiResponse<Variable> update(@PathVariable Long moduleId, @PathVariable Long varId,
                                        @RequestBody Variable body) {
        return ApiResponse.ok(variableService.updateDefinition(moduleId, varId, body));
    }

    @DeleteMapping("/{varId}")
    public ApiResponse<Void> delete(@PathVariable Long moduleId, @PathVariable Long varId) {
        permissionService.requireCanDelete();
        variableService.delete(moduleId, varId);
        auditService.log("VARIABLE_DELETE", "VARIABLE", String.valueOf(varId), true, null);
        return ApiResponse.ok();
    }

    @PutMapping("/sort")
    public ApiResponse<Void> sort(@PathVariable Long moduleId, @RequestBody SortBody body) {
        variableService.sort(moduleId, body.getIds());
        return ApiResponse.ok();
    }

    /**
     * §5.5 PUT /variables/{varId}/value
     */
    @PutMapping("/{varId}/value")
    public ApiResponse<Void> setValue(@PathVariable Long moduleId, @PathVariable Long varId,
                                      @RequestBody VariableService.ValueRequest body) {
        variableService.setValue(moduleId, varId, body);
        return ApiResponse.ok();
    }

    /**
     * §5.5 DELETE /variables/{varId}/override 清覆盖（回退继承）。
     */
    @DeleteMapping("/{varId}/override")
    public ApiResponse<Void> clearOverride(@PathVariable Long moduleId, @PathVariable Long varId,
                                           @RequestParam(required = false) Long environmentId,
                                           @RequestParam(required = false) Long applicationId) {
        if (environmentId == null) {
            throw new BizException(40000, "必须指定 environmentId");
        }
        variableService.clearOverride(varId, environmentId, applicationId);
        return ApiResponse.ok();
    }

    /**
     * §5.5 POST /variables/import 粘贴导入。
     *
     * <p>实现在 {@link VariableImportService}，与 MCP 的 import_dotenv 共用同一套语义。</p>
     */
    @PostMapping("/import")
    public ApiResponse<ImportResult> importDotenv(@PathVariable Long moduleId,
                                                  @RequestBody ImportBody body) {
        VariableImportService.Outcome outcome = importService.importDotenv(
                moduleId, body.getDotenvText(), body.getApplyTo(), body.getEnvironmentId(), false);
        return ApiResponse.ok(new ImportResult(
                outcome.created(), outcome.updated(), outcome.items().size()));
    }

    /**
     * §5.5 GET /variables/export?environmentId= dotenv 文本（加密值按权限脱敏）。
     */
    @GetMapping("/export")
    public ApiResponse<ExportResult> export(@PathVariable Long moduleId,
                                            @RequestParam(required = false) Long environmentId) {
        permissionService.requireCanView(moduleId);
        Module m = moduleService.requireById(moduleId);
        List<Variable> vars = variableService.listByModule(moduleId);

        // §6.4 加密值按权限**逐变量**处理：无权 → __MASKED__；有权 → 明文
        // 注意：不能用一个模块级 reveal 标志，否则"能看自己创建的变量"会顺带
        // 泄漏他人变量的明文。
        List<DotenvCodec.Entry> entries = new ArrayList<>();
        boolean anyRevealed = false;
        Long envId = environmentId;
        if (envId == null) {
            // 未指定环境时用模块绑定的第一个环境
            List<Long> bound = moduleService.environmentIdsOf(moduleId);
            envId = bound.isEmpty() ? null : bound.get(0);
        }
        for (Variable v : vars) {
            boolean encrypted = "ENCRYPTED".equals(v.getValueType());
            boolean revealThis = !encrypted
                    || permissionService.canRevealEncrypted(moduleId, v.getCreatedBy());
            if (encrypted && revealThis) {
                anyRevealed = true;
            }
            String raw;
            if (envId == null) {
                // 未指定环境：只看全局值
                if (v.getGlobalValue() == null) {
                    continue;
                }
                // 全局值存的是密文，需按权限解密或脱敏
                raw = encrypted
                        ? (revealThis ? crypto.decrypt(v.getGlobalValue())
                        : VariableResolver.EXPORT_MASKED)
                        : v.getGlobalValue();
            } else {
                ResolvedValue rv = resolver.resolveVariable(
                        v, m.getApplicationId(), envId, revealThis);
                if (!rv.isFound()) {
                    continue;
                }
                raw = rv.getValue();
                // §6.4：console 的 dotenv 导出无权限时输出 __MASKED__（而非矩阵里的 ••••）
                if (encrypted && !revealThis) {
                    raw = VariableResolver.EXPORT_MASKED;
                }
            }
            entries.add(new DotenvCodec.Entry(v.getVarKey(), raw, encrypted));
        }
        // 值已在上方按权限逐变量处理完（明文或 __MASKED__），render 直接输出。
        String text = dotenv.render(
                List.of(new DotenvCodec.ModuleEntries(m.getCode(), entries)), true, crypto);
        return ApiResponse.ok(new ExportResult(text, anyRevealed));
    }

    /**
     * §5.5 POST /variables/{varId}/reveal 返回明文，写高危审计。
     */
    @PostMapping("/{varId}/reveal")
    public ApiResponse<RevealResult> reveal(@PathVariable Long moduleId, @PathVariable Long varId,
                                            @RequestBody(required = false) RevealBody body) {
        Long envId = body == null ? null : body.getEnvironmentId();
        if (envId == null) {
            List<Long> bound = moduleService.environmentIdsOf(moduleId);
            envId = bound.isEmpty() ? null : bound.get(0);
        }
        String plain = variableService.reveal(moduleId, varId, envId);
        // §6.4 / §7：每次 reveal 明文 → 高危审计
        auditService.log("VARIABLE_REVEAL", "VARIABLE", String.valueOf(varId), true,
                auditService.detailJson("moduleId", moduleId, "environmentId", envId));
        return ApiResponse.ok(new RevealResult(plain));
    }

    @Data
    public static class SortBody {
        private List<Long> ids;
    }

    @Data
    public static class ChainView {
        private Long varId;
        private String varKey;
        private Long environmentId;
        private List<ChainStop> levels;
    }

    @Data
    public static class ChainStop {
        private Long environmentId;
        private String environmentCode;
        private String environmentName;
        private boolean defined;
        private boolean masked;
        private String value;
        private String source;
    }

    @Data
    public static class ImportBody {
        private String dotenvText;
        private String applyTo;
        private Long environmentId;
    }

    public record ImportResult(int created, int updated, int parsed) {
    }

    public record ExportResult(String dotenv, boolean encryptedRevealed) {
    }

    public record RevealResult(String plainValue) {
    }

    @Data
    public static class RevealBody {
        private Long environmentId;
    }

    @Data
    public static class Matrix {
        private List<Environment> environments;
        private List<Row> rows;
    }

    @Data
    public static class Row {
        private Long id;
        private String varKey;
        private String valueType;
        private String remark;
        private Boolean isRequired;
        private Integer sortOrder;
        private Long createdBy;
        private boolean canReveal;
        /** key = environmentId，0 表示全局值列 */
        private Map<Long, Cell> cells;
    }

    @Data
    public static class Cell {
        private String value;
        private boolean masked;
        private boolean found;
        private boolean override;
        private boolean inherited;
        private String source;
    }
}
