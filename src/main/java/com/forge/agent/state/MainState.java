package com.forge.agent.state;

import org.bsc.langgraph4j.state.AgentState;

import com.forge.agent.review.Verdict;

import java.util.*;
import java.util.stream.Stream;

/**
 * 主 Agent 状态
 *
 * AgentState 内部有个 private HashMap，data() 返回它的不可变视图。
 * 我们传 new HashMap<>() 给父类，然后保留对同一个 HashMap 的引用，
 * 通过这个引用写数据，data() 读到的就是最新的。
 */
public class MainState extends AgentState {

    public static final String REQUIREMENT = "requirement";
    public static final String ANALYSIS = "analysis";
    public static final String TASK_DAG = "taskDAG";
    public static final String SUB_SPECS = "subSpecs";
    public static final String SUB_TASKS = "subTasks";
    public static final String CURRENT_NODE = "currentNode";
    public static final String APPROVAL_STATUS = "approvalStatus";
    public static final String FINAL_RESULT = "finalResult";
    public static final String ERROR = "error";
    public static final String PHASE = "phase";
    public static final String CODE_VERDICT = "codeVerdict";
    public static final String SECURITY_VERDICT = "securityVerdict";
    public static final String REJECTION_REASON = "rejectionReason";
    public static final String PREVIOUS_DAG_JSON = "previousDAGJson";
    public static final String REVISION_COUNT = "revisionCount";

    public enum Phase {
        INIT, ANALYZE, ARCHITECTURE, SPEC_AUTHOR, SPEC_REVIEW,
        PLAN, APPROVAL, SCHEDULE, DISPATCH, COLLECT,
        REVIEW, TEST, INTEGRATION_TEST, ACCEPT, INTEGRATE,
        DONE, ERROR
    }

    public enum ApprovalStatus {
        PENDING, WAITING_APPROVAL, APPROVED, REJECTED
    }

    // 关键：保留对父类内部 HashMap 的引用
    // super(new HashMap<>(initData)) 把这个 HashMap 存为 this.data（private）
    // data() 返回 unmodifiableMap(data) — 不可变视图
    // 但我们持有的 mutable 引用可以直接写
    private final Map<String, Object> mutable;

    public MainState() {
        this(new HashMap<>());
        initDefaults();
    }

    public MainState(Map<String, Object> initData) {
        super(new HashMap<>(initData != null ? initData : Map.of()));
        // super() 会把传入的 HashMap 存为内部 data 字段
        // data() 返回它的不可变视图，但底层就是我们传进去的这个 HashMap
        // 所以我们再 new 一个引用指向同一个对象是不行的
        // 正确做法：直接 new 一个 HashMap，传给 super，同时自己保留引用
        this.mutable = (Map<String, Object>) getUnderlyingMap();
    }

    public MainState(AgentState other) {
        this(other.data());
    }

    // 反射拿 super 的 private data 字段（唯一可靠的方式）
    @SuppressWarnings("unchecked")
    private Map<String, Object> getUnderlyingMap() {
        try {
            var field = AgentState.class.getDeclaredField("data");
            field.setAccessible(true);
            return (Map<String, Object>) field.get(this);
        } catch (Exception e) {
            // fallback：如果反射失败，用独立 Map（功能降级但不崩）
            return new HashMap<>();
        }
    }

    private void initDefaults() {
        mutable.putIfAbsent(SUB_TASKS, new ArrayList<SubTask>());
        mutable.putIfAbsent(SUB_SPECS, new ArrayList<SubSpec>());
        mutable.putIfAbsent(TASK_DAG, new ArrayList<TaskNode>());
        mutable.putIfAbsent(APPROVAL_STATUS, ApprovalStatus.PENDING.name());
        mutable.putIfAbsent(PHASE, Phase.INIT.name());
    }

    // ==================== Getter/Setter ====================

    public String getRequirement() { return getString(REQUIREMENT); }
    public void setRequirement(String v) { mutable.put(REQUIREMENT, v); }

    public String getAnalysis() { return getString(ANALYSIS); }
    public void setAnalysis(String v) { mutable.put(ANALYSIS, v); }

    @SuppressWarnings("unchecked")
    public List<TaskNode> getTaskDAG() { return (List<TaskNode>) mutable.get(TASK_DAG); }
    public void setTaskDAG(List<TaskNode> v) { mutable.put(TASK_DAG, v); }

    @SuppressWarnings("unchecked")
    public List<SubSpec> getSubSpecs() { return (List<SubSpec>) mutable.get(SUB_SPECS); }
    public void setSubSpecs(List<SubSpec> v) { mutable.put(SUB_SPECS, v); }

    @SuppressWarnings("unchecked")
    public List<SubTask> getSubTasks() { return (List<SubTask>) mutable.get(SUB_TASKS); }
    public void setSubTasks(List<SubTask> v) { mutable.put(SUB_TASKS, v); }
    public void addSubTask(SubTask task) {
        ((List<SubTask>) mutable.computeIfAbsent(SUB_TASKS, k -> new ArrayList<>())).add(task);
    }

    public String getCurrentNode() { return getString(CURRENT_NODE); }
    public void setCurrentNode(String v) { mutable.put(CURRENT_NODE, v); }

    public String getApprovalStatus() { return getString(APPROVAL_STATUS); }
    public void setApprovalStatus(String v) { mutable.put(APPROVAL_STATUS, v); }

    public ApprovalStatus getApprovalStatusEnum() {
        String raw = getApprovalStatus();
        try { return raw != null ? ApprovalStatus.valueOf(raw) : ApprovalStatus.PENDING; }
        catch (Exception e) { return ApprovalStatus.PENDING; }
    }

    public String getFinalResult() { return getString(FINAL_RESULT); }
    public void setFinalResult(String v) { mutable.put(FINAL_RESULT, v); }

    public String getError() { return getString(ERROR); }
    public void setError(String v) { mutable.put(ERROR, v); }

    public String getPhase() { return getString(PHASE); }
    public void setPhase(String v) { mutable.put(PHASE, v); }

    public Phase getPhaseEnum() {
        String raw = getPhase();
        try { return raw != null ? Phase.valueOf(raw) : Phase.INIT; }
        catch (Exception e) { return Phase.INIT; }
    }

    private String getString(String key) {
        Object v = mutable.get(key);
        return v != null ? v.toString() : null;
    }

    // ==================== Verdict getter/setter ====================

    public Verdict getCodeVerdict() {
        Object v = mutable.get(CODE_VERDICT);
        return v instanceof Verdict ? (Verdict) v : null;
    }

    public void setCodeVerdict(Verdict verdict) {
        mutable.put(CODE_VERDICT, verdict);
    }

    public Verdict getSecurityVerdict() {
        Object v = mutable.get(SECURITY_VERDICT);
        return v instanceof Verdict ? (Verdict) v : null;
    }

    public void setSecurityVerdict(Verdict verdict) {
        mutable.put(SECURITY_VERDICT, verdict);
    }

    /**
     * 综合判断：代码审查和安全审查是否都通过
     */
    public boolean isReviewPassed() {
        Verdict code = getCodeVerdict();
        Verdict security = getSecurityVerdict();
        // 如果没有设置 verdict，默认通过（向后兼容）
        boolean codePass = code == null || code.isPass();
        boolean securityPass = security == null || security.isPass();
        return codePass && securityPass;
    }

    // ==================== 对话记忆 getter/setter ====================

    public String getRejectionReason() { return getString(REJECTION_REASON); }
    public void setRejectionReason(String v) { mutable.put(REJECTION_REASON, v); }

    public String getPreviousDAGJson() { return getString(PREVIOUS_DAG_JSON); }
    public void setPreviousDAGJson(String v) { mutable.put(PREVIOUS_DAG_JSON, v); }

    public int getRevisionCount() {
        Object v = mutable.get(REVISION_COUNT);
        return v instanceof Integer ? (Integer) v : 0;
    }
    public void setRevisionCount(int v) { mutable.put(REVISION_COUNT, v); }
    public void incrementRevisionCount() { mutable.put(REVISION_COUNT, getRevisionCount() + 1); }

    /** 是否是驳回后的重新规划 */
    public boolean isRevision() { return getRevisionCount() > 0; }
}
