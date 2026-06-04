package com.forge.agent.config;

import com.forge.agent.llm.PromptRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提示词注册中心配置
 * 
 * 注册默认提示词模板
 * 未来可以通过 application.yml 或数据库覆盖
 */
@Configuration
public class PromptConfig {

    @Bean
    public PromptRegistry promptRegistry() {
        return PromptRegistry.withDefaults();
    }
}
