package com.forge.agent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LlmModelRouter 单元测试
 * 测试根据角色路由到正确的 LLM 模型
 */
@DisplayName("LlmModelRouter 模型路由器测试")
class LlmModelRouterTest {

    private ChatModel defaultModel;
    private ChatModel plannerModel;
    private ChatModel architectModel;
    private ChatModel reviewerModel;
    private ChatModel workerModel;
    private LlmModelRouter router;

    @BeforeEach
    void setUp() {
        // 创建 mock 模型
        defaultModel = mock(ChatModel.class, "defaultModel");
        plannerModel = mock(ChatModel.class, "plannerModel");
        architectModel = mock(ChatModel.class, "architectModel");
        reviewerModel = mock(ChatModel.class, "reviewerModel");
        workerModel = mock(ChatModel.class, "workerModel");

        Map<String, ChatModel> modelMap = Map.of(
                "planner", plannerModel,
                "architect", architectModel,
                "reviewer", reviewerModel,
                "worker", workerModel
        );

        router = new LlmModelRouter(modelMap, defaultModel);
    }

    // ==================== resolve 返回正确模型 ====================

    @Test
    @DisplayName("resolve('planner') 应返回 planner 模型")
    void resolve_planner_shouldReturnPlannerModel() {
        assertSame(plannerModel, router.resolve("planner"));
    }

    @Test
    @DisplayName("resolve('architect') 应返回 architect 模型")
    void resolve_architect_shouldReturnArchitectModel() {
        assertSame(architectModel, router.resolve("architect"));
    }

    @Test
    @DisplayName("resolve('reviewer') 应返回 reviewer 模型")
    void resolve_reviewer_shouldReturnReviewerModel() {
        assertSame(reviewerModel, router.resolve("reviewer"));
    }

    @Test
    @DisplayName("resolve('worker') 应返回 worker 模型")
    void resolve_worker_shouldReturnWorkerModel() {
        assertSame(workerModel, router.resolve("worker"));
    }

    // ==================== resolve 未知角色返回 default ====================

    @Test
    @DisplayName("resolve 未知角色应返回 default 模型")
    void resolve_unknownRole_shouldReturnDefaultModel() {
        assertSame(defaultModel, router.resolve("unknown-role"));
    }

    @Test
    @DisplayName("resolve 空字符串应返回 default 模型")
    void resolve_emptyString_shouldReturnDefaultModel() {
        assertSame(defaultModel, router.resolve(""));
    }

    // ==================== resolve null 返回 default ====================

    @Test
    @DisplayName("resolve(null) 应返回 default 模型")
    void resolve_null_shouldReturnDefaultModel() {
        assertSame(defaultModel, router.resolve(null));
    }

    // ==================== availableRoles ====================

    @Test
    @DisplayName("availableRoles 应返回所有已注册的角色")
    void availableRoles_shouldReturnAllRegisteredRoles() {
        var roles = router.availableRoles();
        assertEquals(4, roles.size());
        assertTrue(roles.contains("planner"));
        assertTrue(roles.contains("architect"));
        assertTrue(roles.contains("reviewer"));
        assertTrue(roles.contains("worker"));
    }

    @Test
    @DisplayName("availableRoles 不应包含 default")
    void availableRoles_shouldNotContainDefault() {
        assertFalse(router.availableRoles().contains("default"));
    }
}
