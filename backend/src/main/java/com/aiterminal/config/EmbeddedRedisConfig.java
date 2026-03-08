package com.aiterminal.config;

import com.aiterminal.util.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import redis.embedded.RedisServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 嵌入式 Redis 配置类
 * 负责在应用启动时拉起内置 Redis，关闭时自动销毁
 */
@Configuration
public class EmbeddedRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisConfig.class);

    private RedisServer redisServer;

    // 可以在 application.yml 配置端口，默认 26379 避免与系统可能的 redis 冲突
    @Value("${app.redis.port:26379}")
    private int port;

    @Value("${app.redis.max-heap:128M}")
    private String maxHeap;

    @Value("${app.redis.embedded.enabled:true}")
    private boolean embeddedEnabled;

    @Value("${app.redis.embedded.fail-fast:false}")
    private boolean failFast;

    @Autowired
    private ApplicationContext context;

    @PostConstruct
    public void init() {
        if (!AppConfig.isAiMultiTurnEnabled()) {
            return;
        }
        if (!embeddedEnabled) {
            log.info("[Embedded Redis] 已禁用内嵌 Redis，使用外部 Redis 或降级存储。");
            return;
        }
        if (isUnsupportedEmbeddedPlatform()) {
            log.warn("[Embedded Redis] 当前平台可能不支持 embedded-redis，跳过启动并使用降级存储。");
            return;
        }
        try {
            startRedis();
        } catch (Throwable t) {
            if (failFast) {
                throw new IllegalStateException("[Embedded Redis] 初始化失败", t);
            }
            log.warn("[Embedded Redis] 初始化失败，继续以降级模式运行: {}", t.getMessage());
        }
    }

    public synchronized void startRedis() {
        if (redisServer != null && redisServer.isActive()) {
            log.info("[Embedded Redis] 已在运行中。");
            return;
        }

        try {
            ch.qos.logback.classic.Logger lettuceLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger("io.lettuce.core.protocol");
            lettuceLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        } catch (Exception e) {
        }

        try {
            // 前置检查
            if (isPortOpen("127.0.0.1", port, 200)) {
                log.warn("[Embedded Redis] 端口 {} 已被占用，跳过内嵌 Redis 启动。(将尝试直连该端口)", port);
                return;
            }

            // 使用 AppConfig 定义的全局数据目录，确保项目打包成 jar 后数据能正确存储在外部
            File redisDataDir = new File(AppConfig.APP_DATA_DIR, "redis-data");

            if (!redisDataDir.exists()) {
                redisDataDir.mkdirs();
            }

            // 将 Windows 路径里的反斜杠转为正斜杠，以防 redis 配置文件解析出错
            String safePath = redisDataDir.getAbsolutePath().replace("\\", "/");

            log.info("-----------------------------------------------------");
            log.info("[Embedded Redis] 启动中...");
            log.info("[数据存储路径] {}", safePath);

            // 构建并配置 Redis Server
            redisServer = RedisServer.builder()
                    .port(port)
                    .setting("maxmemory " + maxHeap)
                    .setting("dir " + safePath)
                    .setting("dbfilename dump.rdb")
                    .setting("save 10 1") // 每10秒有1个修改就持久化
                    .setting("appendonly yes") // 开启 AOF 防止数据丢失
                    .setting("appendfilename appendonly.aof")
                    .build();

            redisServer.start();
            log.info("[Embedded Redis] 启动成功");

            // 由于我们是内嵌 Redis 晚于 Spring 某些 Bean 初始化，
            // 尝试重置一下 Spring Data Redis 的 Lettuce 客户端连接池确保它连向了正确的端口
            try {
                org.springframework.data.redis.connection.RedisConnectionFactory factory = context
                        .getBean(org.springframework.data.redis.connection.RedisConnectionFactory.class);

                if (factory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) {
                    org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuce = (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) factory;
                    log.info("[Redis Client] 重新初始化连接池...");
                    lettuce.afterPropertiesSet();
                    lettuce.resetConnection();
                }
            } catch (Exception e) {
                log.warn("连接池重置失败 (这通常不影响运行): {}", e.getMessage());
            }

            log.info("-----------------------------------------------------");
        } catch (Throwable e) {
            if (failFast) {
                throw e;
            }
            log.warn("[Embedded Redis] 启动失败，继续以降级模式运行: {}", e.getMessage());
            redisServer = null;
        }
    }

    private boolean isUnsupportedEmbeddedPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        boolean isMac = osName.contains("mac");
        boolean isArm = osArch.contains("aarch64") || osArch.contains("arm64");
        return isMac && isArm;
    }

    @PreDestroy
    public synchronized void stopRedis() {
        if (redisServer != null && redisServer.isActive()) {
            log.info("[Embedded Redis] 正在停止...");
            try {
                // 停止时关掉 lettuce 的不必要心跳报错日志
                ch.qos.logback.classic.Logger lettuceLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                        .getLogger("io.lettuce.core.protocol");
                lettuceLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
            } catch (Exception e) {
                // ignore
            }

            try {
                redisServer.stop();
                log.info("[Embedded Redis] 已停止。");
            } catch (Exception e) {
                log.error("停止 Redis 时发生异常", e);
            }
            redisServer = null;
        }
    }

    /**
     * 测试本地端口是否通畅
     */
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
