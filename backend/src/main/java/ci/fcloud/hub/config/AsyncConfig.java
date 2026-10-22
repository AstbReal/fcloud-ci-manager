package ci.fcloud.hub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 启用 @Async：审计与访问日志异步写库（§7 / §8），不阻塞业务请求。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 备份打包专用执行器。
     *
     * <p>单线程 + 小队列：备份本来就是全库打包，并发跑只会互相抢 IO，
     * 还会把磁盘打满；业务侧也有一条 RUNNING 记录做并发保护。</p>
     */
    @Bean(name = "backupExecutor")
    public TaskExecutor backupExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(4);
        ex.setThreadNamePrefix("backup-");
        // 停机时等当前备份写完，避免留下半截 tar.gz
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(120);
        ex.initialize();
        return ex;
    }
}
