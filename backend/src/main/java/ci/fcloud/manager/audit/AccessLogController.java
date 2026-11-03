package ci.fcloud.manager.audit;

import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * §5.9 访问日志查询（仅系统管理员）。
 */
@RestController
@RequestMapping("/api/console/admin/access-logs")
@RequiredArgsConstructor
public class AccessLogController {

    private final AccessLogService accessLogService;

    @GetMapping
    public ApiResponse<PageResult<AccessLog>> list(
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        LambdaQueryWrapper<AccessLog> w = new LambdaQueryWrapper<>();
        if (appId != null) {
            w.eq(AccessLog::getApplicationId, appId);
        }
        if (from != null) {
            w.ge(AccessLog::getCreatedAt, toInstant(from));
        }
        if (to != null) {
            w.le(AccessLog::getCreatedAt, toInstant(to));
        }
        w.orderByDesc(AccessLog::getCreatedAt);

        Page<AccessLog> p = accessLogService.page(new Page<>(page, size), w);
        return ApiResponse.ok(PageResult.of(p.getRecords(), p.getTotal(), page, size));
    }

    private Instant toInstant(LocalDateTime t) {
        return t.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }
}
