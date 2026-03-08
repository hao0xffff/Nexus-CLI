package com.aiterminal.util;

import java.io.File;

/**
 * 全局应用配置常量
 */
public class AppConfig {
    
    // 获取用户主目录下的应用数据文件夹 ~/.nexus-cli
    public static final String APP_DATA_DIR = System.getProperty("user.home") + File.separator + ".nexus-cli";
    
    /**
     * 判断是否开启了 AI 多轮对话（默认为 true，将来可用作特性的条件开关）
     */
    public static boolean isAiMultiTurnEnabled() {
        return true;
    }
}
