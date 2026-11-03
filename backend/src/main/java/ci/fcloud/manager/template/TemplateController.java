package ci.fcloud.manager.template;

import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.module.Module;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * §5.8 模板（仅系统管理员，路径前缀 /api/console/admin）。
 */
@RestController
@RequestMapping("/api/console/admin")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping("/templates")
    public ApiResponse<List<TemplateVO>> list() {
        return ApiResponse.ok(templateService.listAll().stream().map(t -> {
            TemplateVO vo = TemplateVO.of(t);
            vo.setVarCount(templateService.varsOf(t.getId()).size());
            vo.setPendingModules(templateService.pendingModuleCount(t.getId()));
            return vo;
        }).toList());
    }

    @PostMapping("/templates")
    public ApiResponse<TemplateDetail> create(@RequestBody TemplateBody body) {
        Template t = templateService.create(body.toTemplate(), body.getVars());
        return ApiResponse.ok(TemplateDetail.of(t, templateService.varsOf(t.getId())));
    }

    @PutMapping("/templates/group")
    public ApiResponse<Void> renameGroup(@RequestBody GroupBody body) {
        templateService.renameGroup(body.getFrom(), body.getTo());
        return ApiResponse.ok();
    }

    @GetMapping("/templates/{id}")
    public ApiResponse<TemplateDetail> detail(@PathVariable Long id) {
        Template t = templateService.requireById(id);
        return ApiResponse.ok(TemplateDetail.of(t, templateService.varsOf(id)));
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<TemplateDetail> update(@PathVariable Long id,
                                              @RequestBody TemplateBody body) {
        Template t = templateService.update(id, body.toTemplate(), body.getVars());
        return ApiResponse.ok(TemplateDetail.of(t, templateService.varsOf(id)));
    }

    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * §5.8 实例化：{applicationId, code, name}
     */
    @PostMapping("/templates/{id}/instantiate")
    public ApiResponse<Module> instantiate(@PathVariable Long id,
                                           @RequestBody InstantiateBody body) {
        return ApiResponse.ok(templateService.instantiate(id, body.getApplicationId(),
                body.getCode(), body.getName(), SecurityUtils.currentUserId(),
                body.getEnvironmentIds(), body.getGroupName()));
    }

    /**
     * §5.8 GET /modules/{id}/template-diff
     */
    @GetMapping("/modules/{id}/template-diff")
    public ApiResponse<TemplateService.TemplateDiff> diff(@PathVariable Long id) {
        return ApiResponse.ok(templateService.diff(id));
    }

    /**
     * §5.8 POST /modules/{id}/template-sync
     */
    @PostMapping("/modules/{id}/template-sync")
    public ApiResponse<TemplateService.SyncResult> sync(@PathVariable Long id,
                                                        @RequestBody SyncBody body) {
        return ApiResponse.ok(templateService.sync(id,
                body.getAcceptAdded(), body.getAcceptChanged(),
                body.getRemovedKeys(), body.getRemovedAction()));
    }

    /**
     * §5.8 POST /modules/{id}/save-as-template
     */
    @PostMapping("/modules/{id}/save-as-template")
    public ApiResponse<Template> saveAsTemplate(@PathVariable Long id,
                                                @RequestBody SaveAsBody body) {
        return ApiResponse.ok(templateService.saveAsTemplate(id, body.getCode(),
                body.getName(), body.getGroupName()));
    }

    // ---------------- DTO ----------------

    @Data
    public static class TemplateBody {
        private String code;
        private String name;
        private String description;
        private String groupName;
        private List<TemplateVar> vars;

        Template toTemplate() {
            Template t = new Template();
            t.setCode(code);
            t.setName(name);
            t.setDescription(description);
            t.setGroupName(groupName);
            return t;
        }
    }

    @Data
    public static class GroupBody {
        private String from;
        private String to;
    }

    @Data
    public static class InstantiateBody {
        private Long applicationId;
        private String code;
        private String name;
        private String groupName;
        private List<Long> environmentIds;
    }

    @Data
    public static class SyncBody {
        private List<String> acceptAdded;
        private List<String> acceptChanged;
        private List<String> removedKeys;
        private String removedAction;
    }

    @Data
    public static class SaveAsBody {
        private String code;
        private String name;
        private String groupName;
    }

    @Data
    public static class TemplateVO {
        private Long id;
        private String code;
        private String name;
        private String description;
        private String groupName;
        private int varCount;
        private long pendingModules;
        private java.time.Instant createdAt;
        private java.time.Instant updatedAt;

        static TemplateVO of(Template t) {
            TemplateVO v = new TemplateVO();
            v.setId(t.getId());
            v.setCode(t.getCode());
            v.setName(t.getName());
            v.setDescription(t.getDescription());
            v.setGroupName(t.getGroupName());
            v.setCreatedAt(t.getCreatedAt());
            v.setUpdatedAt(t.getUpdatedAt());
            return v;
        }
    }

    @Data
    public static class TemplateDetail {
        private Long id;
        private String code;
        private String name;
        private String description;
        private String groupName;
        private List<TemplateVar> vars;

        static TemplateDetail of(Template t, List<TemplateVar> vars) {
            TemplateDetail d = new TemplateDetail();
            d.setId(t.getId());
            d.setCode(t.getCode());
            d.setName(t.getName());
            d.setDescription(t.getDescription());
            d.setGroupName(t.getGroupName());
            d.setVars(vars);
            return d;
        }
    }
}
