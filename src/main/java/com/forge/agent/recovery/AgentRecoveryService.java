package com.forge.agent.recovery;

import com.forge.agent.state.MainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Agent 重启恢复服务
 * 
 * 负责：
 * 1. 应用重启时，从 Redis 恢复未完成的 Agent 状态
 * 2. 检查是否有中断的任务需要重新执行
 * 3. 恢复 DAG 执行进度，从断点继续执行
 * 
 * 状态持久化：
 * - MainState 保存在 Redis Hash: agent:state:{sessionId}
 * - 子任务结果保存在 Redis Hash: agent:task:result:{taskId}
 */
@Service
public class AgentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRecoveryService.class);

    private static final String STATE_KEY_PREFIX = "agent:state:";
    private static final String SESSION_SET_KEY = "agent:sessions:active";

    private final StringRedisTemplate redisTemplate;

    public AgentRecoveryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存 Agent 状态到 Redis
     * 
     * @param sessionId 会话ID
     * @param state 主Agent状态
     */
    public void saveState(String sessionId, MainState state) {
        String key = STATE_KEY_PREFIX + sessionId;

        // 将状态保存到 Redis Hash — 通过 data() 获取底层 Map
        Map<String, String> stateMap = new java.util.HashMap<>();
        state.data().forEach((k, v) -> {
            if (v != null) {
                stateMap.put(k, v.toString());
            }
        });

        redisTemplate.opsForHash().putAll(key, stateMap);

        // 记录活跃会话
        redisTemplate.opsForSet().add(SESSION_SET_KEY, sessionId);

        log.info("Agent 状态已保存: sessionId={}", sessionId);
    }

    /**
     * 从 Redis 恢复 Agent 状态
     * 
     * @param sessionId 会话ID
     * @return 恢复的 MainState，不存在返回 null
     */
    public MainState loadState(String sessionId) {
        String key = STATE_KEY_PREFIX + sessionId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        if (entries.isEmpty()) {
            log.info("未找到 Agent 状态: sessionId={}", sessionId);
            return null;
        }

        // 将 Redis Hash 转为 Map<String, Object>，构造 MainState
        Map<String, Object> stateMap = new java.util.HashMap<>();
        entries.forEach((k, v) -> stateMap.put(k.toString(), v.toString()));

        MainState state = new MainState(stateMap);
        log.info("Agent 状态已恢复: sessionId={}, phase={}", sessionId, state.getPhase());
        return state;
    }

    /**
     * 删除 Agent 状态
     * 
     * @param sessionId 会话ID
     */
    public void deleteState(String sessionId) {
        String key = STATE_KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
        redisTemplate.opsForSet().remove(SESSION_SET_KEY, sessionId);
        log.info("Agent 状态已删除: sessionId={}", sessionId);
    }

    /**
     * 恢复所有未完成的会话
     * 
     * @return 未完成会话的 sessionId -> MainState 映射
     */
    public Map<String, MainState> recoverAllUnfinished() {
        Map<String, MainState> recovered = new java.util.HashMap<>();

        Set<String> sessionIds = redisTemplate.opsForSet().members(SESSION_SET_KEY);
        if (sessionIds == null || sessionIds.isEmpty()) {
            log.info("没有需要恢复的会话");
            return recovered;
        }

        for (String sessionId : sessionIds) {
            MainState state = loadState(sessionId);

            if (state != null && !isCompleted(state)) {
                recovered.put(sessionId, state);
                log.info("发现未完成的会话: sessionId={}, phase={}", sessionId, state.getPhase());
            }
        }

        log.info("共恢复 {} 个未完成的会话", recovered.size());
        return recovered;
    }

    /**
     * 检查 Agent 是否已完成
     */
    private boolean isCompleted(MainState state) {
        String phase = state.getPhase();
        return "DONE".equals(phase) || "ERROR".equals(phase);
    }

    /**
     * 清理已完成的会话状态
     */
    public void cleanupCompleted() {
        Set<String> sessionIds = redisTemplate.opsForSet().members(SESSION_SET_KEY);
        if (sessionIds == null) {
            return;
        }

        int cleanedCount = 0;
        for (String sessionId : sessionIds) {
            MainState state = loadState(sessionId);

            if (state != null && isCompleted(state)) {
                deleteState(sessionId);
                cleanedCount++;
            }
        }

        log.info("清理完成，共清理 {} 个已完成的会话", cleanedCount);
    }
}
