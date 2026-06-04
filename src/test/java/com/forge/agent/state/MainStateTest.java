package com.forge.agent.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MainState 单元测试
 * 测试主 Agent 状态的初始化、getter/setter、枚举转换等核心逻辑
 */
@DisplayName("MainState 主状态测试")
class MainStateTest {

    // ==================== 默认构造函数初始化 ====================

    @Test
    @DisplayName("默认构造函数应初始化 phase 为 INIT")
    void defaultConstructor_shouldInitPhaseToINIT() {
        MainState state = new MainState();
        assertEquals("INIT", state.getPhase());
    }

    @Test
    @DisplayName("默认构造函数应初始化 approvalStatus 为 PENDING")
    void defaultConstructor_shouldInitApprovalStatusToPending() {
        MainState state = new MainState();
        assertEquals("PENDING", state.getApprovalStatus());
    }

    @Test
    @DisplayName("默认构造函数应初始化空的 subTasks 列表")
    void defaultConstructor_shouldInitEmptySubTasks() {
        MainState state = new MainState();
        assertNotNull(state.getSubTasks());
        assertTrue(state.getSubTasks().isEmpty());
    }

    @Test
    @DisplayName("默认构造函数应初始化空的 subSpecs 列表")
    void defaultConstructor_shouldInitEmptySubSpecs() {
        MainState state = new MainState();
        assertNotNull(state.getSubSpecs());
        assertTrue(state.getSubSpecs().isEmpty());
    }

    @Test
    @DisplayName("默认构造函数应初始化空的 taskDAG 列表")
    void defaultConstructor_shouldInitEmptyTaskDAG() {
        MainState state = new MainState();
        assertNotNull(state.getTaskDAG());
        assertTrue(state.getTaskDAG().isEmpty());
    }

    // ==================== getter/setter ====================

    @Test
    @DisplayName("requirement getter/setter 应正确读写")
    void requirementGetterSetter_shouldWork() {
        MainState state = new MainState();
        assertNull(state.getRequirement());

        state.setRequirement("实现订单退款功能");
        assertEquals("实现订单退款功能", state.getRequirement());
    }

    @Test
    @DisplayName("analysis getter/setter 应正确读写")
    void analysisGetterSetter_shouldWork() {
        MainState state = new MainState();
        assertNull(state.getAnalysis());

        state.setAnalysis("需求分析结果...");
        assertEquals("需求分析结果...", state.getAnalysis());
    }

    @Test
    @DisplayName("currentNode getter/setter 应正确读写")
    void currentNodeGetterSetter_shouldWork() {
        MainState state = new MainState();
        assertNull(state.getCurrentNode());

        state.setCurrentNode("node-1");
        assertEquals("node-1", state.getCurrentNode());
    }

    @Test
    @DisplayName("finalResult getter/setter 应正确读写")
    void finalResultGetterSetter_shouldWork() {
        MainState state = new MainState();
        assertNull(state.getFinalResult());

        state.setFinalResult("执行完成");
        assertEquals("执行完成", state.getFinalResult());
    }

    @Test
    @DisplayName("error getter/setter 应正确读写")
    void errorGetterSetter_shouldWork() {
        MainState state = new MainState();
        assertNull(state.getError());

        state.setError("出错了");
        assertEquals("出错了", state.getError());
    }

    @Test
    @DisplayName("subTasks setter 应替换列表")
    void subTasksSetter_shouldReplaceList() {
        MainState state = new MainState();
        SubTask task = SubTask.create("t1", "任务1", "描述", List.of());
        state.setSubTasks(List.of(task));

        assertEquals(1, state.getSubTasks().size());
        assertEquals("t1", state.getSubTasks().get(0).taskId());
    }

    @Test
    @DisplayName("taskDAG setter 应替换列表")
    void taskDAGSetter_shouldReplaceList() {
        MainState state = new MainState();
        TaskNode node = TaskNode.execute("n1", "节点1", "描述", List.of());
        state.setTaskDAG(List.of(node));

        assertEquals(1, state.getTaskDAG().size());
        assertEquals("n1", state.getTaskDAG().get(0).nodeId());
    }

    // ==================== addSubTask ====================

    @Test
    @DisplayName("addSubTask 应将子任务追加到列表")
    void addSubTask_shouldAppendTask() {
        MainState state = new MainState();
        SubTask task1 = SubTask.create("t1", "任务1", "描述1", List.of());
        SubTask task2 = SubTask.create("t2", "任务2", "描述2", List.of("t1"));

        state.addSubTask(task1);
        state.addSubTask(task2);

        assertEquals(2, state.getSubTasks().size());
        assertEquals("t1", state.getSubTasks().get(0).taskId());
        assertEquals("t2", state.getSubTasks().get(1).taskId());
    }

    @Test
    @DisplayName("addSubTask 在 subTasks 为 null 时应自动创建列表")
    void addSubTask_shouldAutoCreateListWhenNull() {
        MainState state = new MainState();
        // 先设为 null 模拟异常场景（实际上默认已初始化）
        state.setSubTasks(null);

        SubTask task = SubTask.create("t1", "任务1", "描述", List.of());
        state.addSubTask(task);

        assertNotNull(state.getSubTasks());
        assertEquals(1, state.getSubTasks().size());
    }

    // ==================== ApprovalStatus 枚举转换 ====================

    @Test
    @DisplayName("getApprovalStatusEnum 应正确返回 PENDING")
    void getApprovalStatusEnum_shouldReturnPending() {
        MainState state = new MainState();
        assertEquals(MainState.ApprovalStatus.PENDING, state.getApprovalStatusEnum());
    }

    @Test
    @DisplayName("getApprovalStatusEnum 设置后应正确转换")
    void getApprovalStatusEnum_shouldConvertAfterSet() {
        MainState state = new MainState();
        state.setApprovalStatus(MainState.ApprovalStatus.APPROVED.name());
        assertEquals(MainState.ApprovalStatus.APPROVED, state.getApprovalStatusEnum());
    }

    @Test
    @DisplayName("getApprovalStatusEnum 对无效值应返回 PENDING")
    void getApprovalStatusEnum_shouldReturnPendingForInvalidValue() {
        MainState state = new MainState();
        state.setApprovalStatus("INVALID_VALUE");
        assertEquals(MainState.ApprovalStatus.PENDING, state.getApprovalStatusEnum());
    }

    @Test
    @DisplayName("getApprovalStatusEnum 对 null 应返回 PENDING")
    void getApprovalStatusEnum_shouldReturnPendingForNull() {
        MainState state = new MainState();
        // 将 approvalStatus 设为 null
        state.setApprovalStatus(null);
        assertEquals(MainState.ApprovalStatus.PENDING, state.getApprovalStatusEnum());
    }

    // ==================== Phase 枚举转换 ====================

    @Test
    @DisplayName("getPhaseEnum 默认应返回 INIT")
    void getPhaseEnum_shouldReturnInitByDefault() {
        MainState state = new MainState();
        assertEquals(MainState.Phase.INIT, state.getPhaseEnum());
    }

    @Test
    @DisplayName("getPhaseEnum 设置后应正确转换")
    void getPhaseEnum_shouldConvertAfterSet() {
        MainState state = new MainState();
        state.setPhase(MainState.Phase.ANALYZE.name());
        assertEquals(MainState.Phase.ANALYZE, state.getPhaseEnum());
    }

    @Test
    @DisplayName("getPhaseEnum 遍历所有枚举值应正确转换")
    void getPhaseEnum_shouldConvertAllEnumValues() {
        MainState state = new MainState();
        for (MainState.Phase phase : MainState.Phase.values()) {
            state.setPhase(phase.name());
            assertEquals(phase, state.getPhaseEnum());
        }
    }

    @Test
    @DisplayName("getPhaseEnum 对无效值应返回 INIT")
    void getPhaseEnum_shouldReturnInitForInvalidValue() {
        MainState state = new MainState();
        state.setPhase("NOT_A_REAL_PHASE");
        assertEquals(MainState.Phase.INIT, state.getPhaseEnum());
    }

    @Test
    @DisplayName("getPhaseEnum 对 null 应返回 INIT")
    void getPhaseEnum_shouldReturnInitForNull() {
        MainState state = new MainState();
        state.setPhase(null);
        assertEquals(MainState.Phase.INIT, state.getPhaseEnum());
    }

    // ==================== 带参构造函数 ====================

    @Test
    @DisplayName("带参构造函数应保留传入的初始数据")
    void constructorWithInitData_shouldPreserveData() {
        MainState state = new MainState();
        state.setRequirement("原始需求");
        state.setPhase(MainState.Phase.PLAN.name());

        // 通过 AgentState 构造函数拷贝
        MainState copy = new MainState(state.data());
        assertEquals("原始需求", copy.getRequirement());
        assertEquals("PLAN", copy.getPhase());
    }
}
