package ci.fcloud.hub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 启用 @Async：审计与访问日志异步写库（§7 / §8），不阻塞业务请求。
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
