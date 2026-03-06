package com.aiterminal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;

/**
 * Thread pool and HTTP client configuration for optimal performance.
 * Provides shared resources to avoid creating/destroying threads per request.
 */
@Slf4j
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    /**
     * Shared HttpClient for all AI/LLM API calls.
     * Uses HTTP/2, connection pooling, and a dedicated executor.
     */
    @Bean
    public HttpClient sharedHttpClient(ExecutorService httpExecutor) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(30))
                .executor(httpExecutor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        
        log.info("Created shared HttpClient with HTTP/2 support and connection pooling");
        return client;
    }

    /**
     * Executor for HTTP client async operations.
     * Sized for concurrent AI API calls.
     */
    @Bean(name = "httpExecutor")
    public ExecutorService httpExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4,                      // Core pool size
                16,                     // Max pool size
                60L, TimeUnit.SECONDS,  // Keep alive time
                new LinkedBlockingQueue<>(100),  // Work queue
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "http-client-" + count++);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()  // Backpressure handling
        );
        
        log.info("Created HTTP executor: core=4, max=16");
        return executor;
    }

    /**
     * Executor for terminal I/O operations (stdout/stderr reading).
     * Cached thread pool for dynamic scaling.
     */
    @Bean(name = "terminalIoExecutor")
    public ExecutorService terminalIoExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,                      // Core pool size
                32,                     // Max pool size - supports many concurrent terminals
                30L, TimeUnit.SECONDS,  // Keep alive time
                new SynchronousQueue<>(),  // Direct handoff
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "terminal-io-" + count++);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        log.info("Created terminal I/O executor: core=2, max=32");
        return executor;
    }

    /**
     * Executor for ReAct agent tasks.
     * Limited concurrency to prevent overwhelming LLM APIs.
     */
    @Bean(name = "reactExecutor")
    public ExecutorService reactExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,                      // Core pool size
                4,                      // Max pool size - limit concurrent ReAct tasks
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),  // Queue for pending tasks
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "react-agent-" + count++);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        log.info("Created ReAct executor: core=2, max=4");
        return executor;
    }

    /**
     * Executor for command execution (CommandExecutor).
     * Separate pool to isolate from terminal I/O.
     */
    @Bean(name = "commandExecutorPool")
    public ExecutorService commandExecutorPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4,                      // Core pool size
                16,                     // Max pool size
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "cmd-exec-" + count++);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        log.info("Created command executor: core=4, max=16");
        return executor;
    }

    /**
     * Spring async task executor for @Async methods.
     */
    @Bean(name = "asyncTaskExecutor")
    public ThreadPoolTaskExecutor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("Created async task executor: core=4, max=16, queue=100");
        return executor;
    }

    /**
     * Scheduled executor for periodic tasks (health checks, cleanup, etc.).
     */
    @Bean(name = "scheduledExecutor")
    public ScheduledExecutorService scheduledExecutor() {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduled-task");
            t.setDaemon(true);
            return t;
        });
        
        log.info("Created scheduled executor: pool=2");
        return executor;
    }
}
