package com.forge.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Forge 多Agent编排系统启动类
 * 
 * 基于 LangGraph4j + Spring AI 的多Agent DAG编排系统
 * 支持DAG拓扑编排、人工审批、子Agent并行调度
 */
@SpringBootApplication
@EnableAsync
public class ForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeApplication.class, args);
    }
}
