package com.aiterminal.ai;

import com.aiterminal.ai.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 负责与 Redis 交互，维护基于 Session ID 的 AI 对话历史
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ChatHistoryRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis Key 的前缀
    private static final String KEY_PREFIX = "chat:history:";

    // 每个会话保留的最大消息数量（默认 -1 无限制）
    @org.springframework.beans.factory.annotation.Value("${app.redis.chat.max-history-size:-1}")
    private int maxHistorySize;

    // 会话历史的过期时间，单位：小时（默认 -1 永不过期）
    @org.springframework.beans.factory.annotation.Value("${app.redis.chat.expire-hours:-1}")
    private long expireHours;

    /**
     * 将单条消息追加到某个会话的历史记录中
     */
    public void pushMessage(String sessionId, ChatMessage message) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        String key = KEY_PREFIX + sessionId;
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            // 右端（尾部）推入消息
            redisTemplate.opsForList().rightPush(key, jsonMessage);

            // 限制列表长度，仅保留最新记录
            if (maxHistorySize > 0) {
                long size = redisTemplate.opsForList().size(key);
                if (size > maxHistorySize) {
                    redisTemplate.opsForList().trim(key, size - maxHistorySize, -1);
                }
            }

            // 刷新过期时间
            if (expireHours > 0) {
                redisTemplate.expire(key, expireHours, TimeUnit.HOURS);
            }

        } catch (JsonProcessingException e) {
            log.error("将聊天消息序列化到 Redis 时发生错误, sessionId: {}", sessionId, e);
        }
    }

    /**
     * 追加多条历史记录（通常用于合并前端传来的临时历史）
     */
    public void pushMessages(String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (ChatMessage msg : messages) {
            pushMessage(sessionId, msg);
        }
    }

    /**
     * 获取指定会话的最近对话历史 (倒数 N 条，受限于 MAX_HISTORY_SIZE 和参数 limit)
     * limit 参数：为了提高大模型响应速度，可进一步控制返回给大模型的历史总数（如 6 条）
     */
    public List<ChatMessage> getRecentHistory(String sessionId, int limit) {
        if (sessionId == null || sessionId.isEmpty()) {
            return new ArrayList<>();
        }

        String key = KEY_PREFIX + sessionId;

        // LRANGE 起始从 0 (头), -1 (尾)
        List<String> jsonMessages = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonMessages == null || jsonMessages.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatMessage> results = jsonMessages.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, ChatMessage.class);
                    } catch (JsonProcessingException e) {
                        log.warn("反序列化聊天消息失败", e);
                        return null;
                    }
                })
                .filter(msg -> msg != null)
                .collect(Collectors.toList());

        // 如果要限制取出的数量，比如仅取最后 limit 条
        if (limit > 0 && results.size() > limit) {
            return results.subList(results.size() - limit, results.size());
        }

        return results;
    }

    /**
     * 清除指定会话的历史记录
     */
    public void clearHistory(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            redisTemplate.delete(KEY_PREFIX + sessionId);
            log.debug("清除了会话 {} 的 AI 聊天历史", sessionId);
        }
    }
}
