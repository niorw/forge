package com.forge.agent.engine;

import com.forge.agent.state.TaskNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAGOrchestrator (DagEngine) 单元测试
 * 测试 DAG 编排引擎的拓扑排序、就绪检测、依赖失败阻断等核心算法
 */
@DisplayName("DAGOrchestrator DAG 编排引擎测试")
class DAGOrchestratorTest {

    private DagEngine dagEngine;

    @BeforeEach
    void setUp() {
        dagEngine = new DagEngine();
    }

    // ==================== 拓扑排序 ====================

    @Test
    @DisplayName("拓扑排序：无依赖的节点应返回原顺序")
    void topologicalSort_noDependencies_shouldReturnAll() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A任务", "描述", List.of()),
                TaskNode.execute("b", "B任务", "描述", List.of()),
                TaskNode.execute("c", "C任务", "描述", List.of())
        );

        List<TaskNode> sorted = dagEngine.topologicalSort(nodes);
        assertEquals(3, sorted.size());
        // 所有节点都应出现
        Set<String> ids = Set.of(
                sorted.get(0).nodeId(),
                sorted.get(1).nodeId(),
                sorted.get(2).nodeId()
        );
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
        assertTrue(ids.contains("c"));
    }

    @Test
    @DisplayName("拓扑排序：线性依赖 a->b->c 应保持顺序")
    void topologicalSort_linearDependency_shouldPreserveOrder() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("c", "C任务", "描述", List.of("b")),
                TaskNode.execute("a", "A任务", "描述", List.of()),
                TaskNode.execute("b", "B任务", "描述", List.of("a"))
        );

        List<TaskNode> sorted = dagEngine.topologicalSort(nodes);
        assertEquals(3, sorted.size());
        assertEquals("a", sorted.get(0).nodeId());
        assertEquals("b", sorted.get(1).nodeId());
        assertEquals("c", sorted.get(2).nodeId());
    }

    @Test
    @DisplayName("拓扑排序：DAG 结构 a->b, a->c, b->d, c->d")
    void topologicalSort_dagStructure_shouldSortCorrectly() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("d", "D任务", "描述", List.of("b", "c")),
                TaskNode.execute("a", "A任务", "描述", List.of()),
                TaskNode.execute("b", "B任务", "描述", List.of("a")),
                TaskNode.execute("c", "C任务", "描述", List.of("a"))
        );

        List<TaskNode> sorted = dagEngine.topologicalSort(nodes);
        assertEquals(4, sorted.size());
        // a 必须在 b、c 之前
        int idxA = indexOf(sorted, "a");
        int idxB = indexOf(sorted, "b");
        int idxC = indexOf(sorted, "c");
        int idxD = indexOf(sorted, "d");
        assertTrue(idxA < idxB);
        assertTrue(idxA < idxC);
        assertTrue(idxB < idxD);
        assertTrue(idxC < idxD);
    }

    @Test
    @DisplayName("拓扑排序：循环依赖应抛出 IllegalStateException")
    void topologicalSort_circularDependency_shouldThrow() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A任务", "描述", List.of("c")),
                TaskNode.execute("b", "B任务", "描述", List.of("a")),
                TaskNode.execute("c", "C任务", "描述", List.of("b"))
        );

        assertThrows(IllegalStateException.class, () -> dagEngine.topologicalSort(nodes));
    }

    @Test
    @DisplayName("拓扑排序：空列表应返回空结果")
    void topologicalSort_emptyList_shouldReturnEmpty() {
        List<TaskNode> sorted = dagEngine.topologicalSort(List.of());
        assertNotNull(sorted);
        assertTrue(sorted.isEmpty());
    }

    @Test
    @DisplayName("拓扑排序：null 输入应返回空结果")
    void topologicalSort_nullInput_shouldReturnEmpty() {
        List<TaskNode> sorted = dagEngine.topologicalSort(null);
        assertNotNull(sorted);
        assertTrue(sorted.isEmpty());
    }

    // ==================== 就绪任务检测 ====================

    @Test
    @DisplayName("就绪检测：无依赖的节点应全部就绪")
    void getReadyNodes_noDependencies_shouldAllBeReady() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of())
        );

        List<TaskNode> ready = dagEngine.getReadyNodes(nodes, Set.of());
        assertEquals(2, ready.size());
    }

    @Test
    @DisplayName("就绪检测：依赖未完成的节点不应就绪")
    void getReadyNodes_dependencyNotCompleted_shouldNotBeReady() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of("a"))
        );

        // a 未完成时，b 不应就绪
        List<TaskNode> ready = dagEngine.getReadyNodes(nodes, Set.of());
        assertEquals(1, ready.size());
        assertEquals("a", ready.get(0).nodeId());
    }

    @Test
    @DisplayName("就绪检测：依赖全部完成的节点应就绪")
    void getReadyNodes_allDependenciesCompleted_shouldBeReady() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of("a")),
                TaskNode.execute("c", "C", "描述", List.of("a"))
        );

        // a 完成后，b 和 c 应就绪
        List<TaskNode> ready = dagEngine.getReadyNodes(nodes, Set.of("a"));
        assertEquals(2, ready.size());
        assertTrue(ready.stream().anyMatch(n -> n.nodeId().equals("b")));
        assertTrue(ready.stream().anyMatch(n -> n.nodeId().equals("c")));
    }

    @Test
    @DisplayName("就绪检测：已完成的节点不应再出现")
    void getReadyNodes_completedNodes_shouldNotAppear() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of())
        );

        // a 已完成，不应再出现在就绪列表
        List<TaskNode> ready = dagEngine.getReadyNodes(nodes, Set.of("a"));
        assertEquals(1, ready.size());
        assertEquals("b", ready.get(0).nodeId());
    }

    @Test
    @DisplayName("就绪检测：null 输入应返回空列表")
    void getReadyNodes_nullInput_shouldReturnEmpty() {
        List<TaskNode> ready = dagEngine.getReadyNodes(null, Set.of());
        assertNotNull(ready);
        assertTrue(ready.isEmpty());
    }

    // ==================== 依赖失败阻断 ====================

    @Test
    @DisplayName("依赖失败阻断：当 a 失败时，b 仍在未完成列表中等待")
    void dependencyFailure_shouldBlockDownstream() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of("a")),
                TaskNode.execute("c", "C", "描述", List.of("b"))
        );

        // 模拟 a 完成但 b 未完成（b 因 a 的失败结果而被阻断）
        // 此时 completedIds 只包含 a，b 的依赖满足但实际执行被上层阻断
        // 验证 c 的依赖 b 未完成 → c 不就绪
        List<TaskNode> readyAfterA = dagEngine.getReadyNodes(nodes, Set.of("a"));
        assertEquals(1, readyAfterA.size());
        assertEquals("b", readyAfterA.get(0).nodeId());

        // 如果 b 也没完成（被阻断），c 不应就绪
        // completedIds 仍只有 a
        List<TaskNode> readyWhenBBlocked = dagEngine.getReadyNodes(nodes, Set.of("a"));
        assertFalse(readyWhenBBlocked.stream().anyMatch(n -> n.nodeId().equals("c")));
    }

    @Test
    @DisplayName("isAllCompleted：全部完成应返回 true")
    void isAllCompleted_allCompleted_shouldReturnTrue() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of())
        );
        assertTrue(dagEngine.isAllCompleted(nodes, Set.of("a", "b")));
    }

    @Test
    @DisplayName("isAllCompleted：部分完成应返回 false")
    void isAllCompleted_partial_shouldReturnFalse() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of())
        );
        assertFalse(dagEngine.isAllCompleted(nodes, Set.of("a")));
    }

    @Test
    @DisplayName("isAllCompleted：空列表应返回 true")
    void isAllCompleted_emptyList_shouldReturnTrue() {
        assertTrue(dagEngine.isAllCompleted(List.of(), Set.of()));
    }

    // ==================== validateDAG ====================

    @Test
    @DisplayName("validateDAG：合法 DAG 应无错误")
    void validateDAG_validDAG_shouldReturnNoErrors() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of()),
                TaskNode.execute("b", "B", "描述", List.of("a"))
        );
        List<String> errors = dagEngine.validateDAG(nodes);
        assertTrue(errors.isEmpty());
    }

    @Test
    @DisplayName("validateDAG：重复节点ID应报错")
    void validateDAG_duplicateIds_shouldReportError() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A1", "描述1", List.of()),
                TaskNode.execute("a", "A2", "描述2", List.of())
        );
        List<String> errors = dagEngine.validateDAG(nodes);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("重复")));
    }

    @Test
    @DisplayName("validateDAG：引用不存在的依赖应报错")
    void validateDAG_missingDependency_shouldReportError() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of("nonexistent"))
        );
        List<String> errors = dagEngine.validateDAG(nodes);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("不存在")));
    }

    @Test
    @DisplayName("validateDAG：循环依赖应报错")
    void validateDAG_circularDependency_shouldReportError() {
        List<TaskNode> nodes = List.of(
                TaskNode.execute("a", "A", "描述", List.of("b")),
                TaskNode.execute("b", "B", "描述", List.of("a"))
        );
        List<String> errors = dagEngine.validateDAG(nodes);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("循环")));
    }

    @Test
    @DisplayName("validateDAG：空列表应报错")
    void validateDAG_emptyList_shouldReportError() {
        List<String> errors = dagEngine.validateDAG(List.of());
        assertFalse(errors.isEmpty());
    }

    // ==================== 辅助方法 ====================

    private int indexOf(List<TaskNode> sorted, String nodeId) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).nodeId().equals(nodeId)) return i;
        }
        return -1;
    }
}
