package ci.fcloud.hub.module;

import java.util.ArrayList;
import java.util.List;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.CryptoService;
import ci.fcloud.hub.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §5.5 dotenv 粘贴导入。
 *
 * <p>从 {@code VariableController#importDotenv} 抽出，供 console 与 MCP 共用——
 * 两套入口必须对「哪些 key 会被写、哪些会被跳过」给出同一答案。</p>
 *
 * <p>语义保持不变：</p>
 * <ul>
 *   <li>不存在的 key → 新建定义 + 按 applyTo 落值；</li>
 *   <li>已存在的 key → 只在目标作用域为空时写入（同步永不覆盖已填的值，§2.5）；</li>
 *   <li>类型推断沿用 {@link DotenvCodec}（PASSWORD/SECRET/TOKEN/KEY 结尾默认加密）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class VariableImportService {

    private final VariableService variableService;
    private final VariableResolver resolver;
    private final CryptoService crypto;
    private final DotenvCodec dotenv;

    /**
     * @param dryRun true 时只计算将发生的变更，不写库（MCP 默认走这个）
     */
    @Transactional
    public Outcome importDotenv(Long moduleId, String dotenvText, String applyTo,
                                Long environmentId, boolean dryRun) {
        if (dotenvText == null || dotenvText.isBlank()) {
            throw BizException.badRequest("dotenvText 不能为空");
        }
        String target = applyTo == null ? "GLOBAL" : applyTo;
        if (!"GLOBAL".equals(target) && !"ENV".equals(target)) {
            throw BizException.badRequest("applyTo 必须是 GLOBAL 或 ENV");
        }
        if ("ENV".equals(target) && environmentId == null) {
            throw BizException.badRequest("applyTo=ENV 必须指定 environmentId");
        }

        List<DotenvCodec.Parsed> parsed = dotenv.parse(dotenvText);
        if (parsed.isEmpty()) {
            throw BizException.badRequest("dotenvText 里没有解析到任何 KEY=VALUE");
        }

        List<Item> items = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (DotenvCodec.Parsed p : parsed) {
            Variable existing = variableService.lambdaQuery()
                    .eq(Variable::getModuleId, moduleId)
                    .eq(Variable::getVarKey, p.key())
                    .one();
            String type = existing != null ? existing.getValueType()
                    : (p.encrypted() ? "ENCRYPTED" : "PLAIN");

            if (existing == null) {
                if (!dryRun) {
                    Variable v = new Variable();
                    v.setModuleId(moduleId);
                    v.setVarKey(p.key());
                    v.setValueType(p.encrypted() ? "ENCRYPTED" : "PLAIN");
                    v.setCreatedBy(SecurityUtils.currentUserId());
                    v.setIsRequired(false);
                    v.setSortOrder(created);
                    String stored = p.encrypted() ? crypto.encrypt(p.value()) : p.value();
                    if ("GLOBAL".equals(target)) {
                        v.setGlobalValue(stored);
                    }
                    variableService.save(v);
                    if ("ENV".equals(target)) {
                        variableService.setValue(moduleId, v.getId(),
                                new VariableService.ValueRequest("ENV", environmentId, null,
                                        p.value()));
                    }
                }
                created++;
                items.add(new Item(p.key(), "CREATE", type, "新建变量定义"));
                continue;
            }

            if ("GLOBAL".equals(target)) {
                if (existing.getGlobalValue() == null) {
                    if (!dryRun) {
                        String stored = "ENCRYPTED".equals(existing.getValueType())
                                ? crypto.encrypt(p.value()) : p.value();
                        variableService.lambdaUpdate().eq(Variable::getId, existing.getId())
                                .set(Variable::getGlobalValue, stored).update();
                    }
                    updated++;
                    items.add(new Item(p.key(), "UPDATE", type, "全局值为空，写入"));
                } else {
                    skipped++;
                    items.add(new Item(p.key(), "SKIP", type, "全局值已有值，不覆盖"));
                }
                continue;
            }

            ResolvedValue rv = resolver.resolveVariable(existing, null, environmentId, false);
            if (!rv.isFound()) {
                if (!dryRun) {
                    variableService.setValue(moduleId, existing.getId(),
                            new VariableService.ValueRequest("ENV", environmentId, null,
                                    p.value()));
                }
                updated++;
                items.add(new Item(p.key(), "UPDATE", type, "该环境无值，写入"));
            } else {
                skipped++;
                items.add(new Item(p.key(), "SKIP", type,
                        "该环境已有值（来源 " + rv.getSource() + "），不覆盖"));
            }
        }

        return new Outcome(created, updated, skipped, dryRun, items);
    }

    /**
     * @param action CREATE / UPDATE / SKIP
     * @param reason 给人看的原因
     */
    public record Item(String key, String action, String valueType, String reason) {
    }

    public record Outcome(int created, int updated, int skipped, boolean dryRun,
                          List<Item> items) {

        public String summary() {
            return (dryRun ? "[预演] " : "") + "新建 " + created + "，更新 " + updated
                    + "，跳过 " + skipped;
        }
    }
}
