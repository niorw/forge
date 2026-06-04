package com.forge.agent.config;

import org.bsc.langgraph4j.checkpoint.CreateOption;
import org.bsc.langgraph4j.checkpoint.MysqlSaver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * LangGraph4j Checkpoint 配置
 *
 * MysqlSaver 自动创建两张表：
 * - LANGRAPH4J_THREAD（线程/会话管理）
 * - LANGRAPH4J_CHECKPOINT（状态快照）
 */
@Configuration
public class CheckpointConfig {

    /**
     * LangGraph4j MySQL Checkpoint Saver
     * 复用 Spring 管理的 DataSource
     */
    @Bean
    public MysqlSaver mysqlSaver(DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }
}
