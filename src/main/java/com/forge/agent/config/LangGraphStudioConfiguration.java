package com.forge.agent.config;

import com.forge.agent.engine.MainAgentGraph;
import com.forge.agent.state.MainState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MysqlSaver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * LangGraph Studio 可视化配置
 *
 * 提供图结构的 REST API，供前端可视化使用。
 * 实际执行使用 MainAgentGraph 中编译的 StateGraph。
 */
@Configuration
@RestController
@RequestMapping("/api/graph")
public class LangGraphStudioConfiguration {

    @Autowired(required = false)
    private MainAgentGraph mainAgentGraph;

    /**
     * 获取图结构信息（用于可视化渲染）
     */
    @GetMapping("/info")
    public Map<String, Object> graphInfo() {
        return Map.of(
            "name", "Forge 多Agent编排图",
            "description", "SDD + DAG 多Agent编排系统",
            "nodes", new String[]{
                "analyze", "plan", "reviewSpec", "approval",
                "schedule", "dispatch", "collect", "integrate"
            },
            "edges", new String[]{
                "START→analyze", "analyze→plan", "plan→reviewSpec",
                "reviewSpec→approval", "approval→schedule(APPROVED)/plan(REJECTED)",
                "schedule→dispatch", "dispatch→collect",
                "collect→integrate(allDone)/schedule(more)", "integrate→END"
            },
            "interrupts", new String[]{"approval"},
            "conditionalEdges", new String[]{
                "approval: APPROVED→schedule, REJECTED→plan",
                "collect: done→integrate, more→schedule"
            }
        );
    }
}
