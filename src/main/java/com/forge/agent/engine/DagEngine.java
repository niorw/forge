package com.forge.agent.engine;

import com.forge.agent.state.TaskNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DAG 编排引擎
 * 
 * 从旧 DAGOrchestrator 重命名并精简：
 * 1. 移除了 @Component 注解上的旧注释
 * 2. 保留纯算法逻辑：拓扑排序、就绪检测、合法性校验
 * 3. 与 LangGraph4j 完全无关 — 这是子任务级别的图算法工具
 * 
 * 职责边界：
 * - DagEngine: 子任务 DAG 的图算法（拓扑排序、就绪节点、环检测）
 * - MainAgentGraph: 主流程的 LangGraph4j StateGraph 编排
 * 两者各管一层，互不依赖
 */
@Component
public class DagEngine {

    private static final Logger log = LoggerFactory.getLogger(DagEngine.class);

    /**
     * 拓扑排序（Kahn 算法）
     * 
     * @param nodes 所有 DAG 节点
     * @return 排序后的节点列表
     * @throws IllegalStateException 存在循环依赖
     */
    public List<TaskNode> topologicalSort(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, TaskNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(TaskNode::nodeId, n -> n));

        // 计算入度
        Map<String, Integer> inDegree = new HashMap<>();
        for (TaskNode node : nodes) {
            inDegree.putIfAbsent(node.nodeId(), 0);
            if (node.dependencies() != null) {
                inDegree.put(node.nodeId(), node.dependencies().size());
            }
        }

        // 构建反向邻接表
        Map<String, List<String>> dependents = new HashMap<>();
        for (TaskNode node : nodes) {
            if (node.dependencies() != null) {
                for (String dep : node.dependencies()) {
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.nodeId());
                }
            }
        }

        // Kahn 算法
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<TaskNode> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(nodeMap.get(current));

            for (String next : dependents.getOrDefault(current, Collections.emptyList())) {
                int newDegree = inDegree.get(next) - 1;
                inDegree.put(next, newDegree);
                if (newDegree == 0) {
                    queue.add(next);
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new IllegalStateException("DAG 中存在循环依赖！节点数: " + nodes.size() + ", 排序后: " + sorted.size());
        }

        log.info("DAG 拓扑排序完成，共 {} 个节点: {}", sorted.size(),
                sorted.stream().map(TaskNode::name).collect(Collectors.joining(" -> ")));

        return sorted;
    }

    /**
     * 获取当前就绪的节点（所有依赖都已完成）
     */
    public List<TaskNode> getReadyNodes(List<TaskNode> nodes, Set<String> completedIds) {
        if (nodes == null) {
            return Collections.emptyList();
        }

        return nodes.stream()
                .filter(node -> !completedIds.contains(node.nodeId()))
                .filter(node -> {
                    if (node.dependencies() == null || node.dependencies().isEmpty()) return true;
                    return node.dependencies().stream().allMatch(completedIds::contains);
                })
                .toList();
    }

    /**
     * 检查是否全部完成
     */
    public boolean isAllCompleted(List<TaskNode> nodes, Set<String> completedIds) {
        if (nodes == null || nodes.isEmpty()) return true;
        return nodes.stream().allMatch(node -> completedIds.contains(node.nodeId()));
    }

    /**
     * 获取需要审批的节点
     */
    public List<TaskNode> getApprovalRequiredNodes(List<TaskNode> nodes) {
        if (nodes == null) return Collections.emptyList();
        return nodes.stream().filter(TaskNode::requiresApproval).toList();
    }

    /**
     * 验证 DAG 合法性
     * 
     * @return 错误列表，空 = 合法
     */
    public List<String> validateDAG(List<TaskNode> nodes) {
        List<String> errors = new ArrayList<>();

        if (nodes == null || nodes.isEmpty()) {
            errors.add("DAG 节点列表为空");
            return errors;
        }

        // 检查节点ID重复
        Set<String> nodeIds = new HashSet<>();
        for (TaskNode node : nodes) {
            if (!nodeIds.add(node.nodeId())) {
                errors.add("重复的节点ID: " + node.nodeId());
            }
        }

        // 检查依赖是否存在
        for (TaskNode node : nodes) {
            if (node.dependencies() != null) {
                for (String dep : node.dependencies()) {
                    if (!nodeIds.contains(dep)) {
                        errors.add(String.format("节点 %s 依赖不存在的节点: %s", node.nodeId(), dep));
                    }
                }
            }
        }

        // 检查循环依赖
        try {
            topologicalSort(nodes);
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
        }

        return errors;
    }
}
