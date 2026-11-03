package ci.fcloud.manager.module;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModuleSubscriptionMapper extends BaseMapper<ModuleSubscription> {

    /**
     * 批量订阅：一条 INSERT 多值，撞唯一约束 (module_id, application_id) 的行直接跳过。
     *
     * <p>用 ON CONFLICT DO NOTHING 而不是先查后插，是为了让「同一批里重复 / 并发重复」
     * 都由数据库裁决，不会因为竞态插出重复行，也不需要额外的隔离级别。
     *
     * <p>created_at 走列默认值 now()，因此这里不列该字段。
     */
    @Insert("<script>"
            + "INSERT INTO fch_module_subscription (id, module_id, application_id, created_by) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.id}, #{r.moduleId}, #{r.applicationId}, #{r.createdBy})"
            + "</foreach> "
            + "ON CONFLICT (module_id, application_id) DO NOTHING"
            + "</script>")
    int batchInsert(@Param("rows") List<ModuleSubscription> rows);
}
