package com.forge.agent.config;

import io.lettuce.core.internal.HostAndPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Redis 配置（Spring Boot 3.x + Lettuce）
 *
 * 集群/单机自动切换
 * 集群模式自动处理 NAT 映射（Redis 节点通告 127.0.0.1 → 外部 IP）
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.cluster.nodes:}")
    private String clusterNodes;

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.timeout:6000ms}")
    private Duration timeout;

    private boolean isClusterMode() {
        return clusterNodes != null && !clusterNodes.isBlank()
            && !clusterNodes.contains("localhost");
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        return isClusterMode() ? createClusterFactory() : createStandaloneFactory();
    }

    private LettuceConnectionFactory createClusterFactory() {
        List<String> nodes = Arrays.asList(clusterNodes.split(","));
        var clusterConfig = new RedisClusterConfiguration(nodes);
        clusterConfig.setMaxRedirects(3);
        if (password != null && !password.isBlank()) {
            clusterConfig.setPassword(password);
        }

        // NAT 映射：集群节点内部通告 127.0.0.1，外部需要连实际 IP
        String externalHost = nodes.get(0).split(":")[0].trim();

        var socketResolver = io.lettuce.core.resource.MappingSocketAddressResolver.create(
            (HostAndPort hostAndPort) -> {
                if ("127.0.0.1".equals(hostAndPort.getHostText())
                    || "localhost".equals(hostAndPort.getHostText())) {
                    return HostAndPort.of(externalHost, hostAndPort.getPort());
                }
                return hostAndPort;
            }
        );

        var clientResources = io.lettuce.core.resource.ClientResources.builder()
                .socketAddressResolver(socketResolver)
                .build();

        var factory = new LettuceConnectionFactory(clusterConfig,
                LettuceClientConfiguration.builder()
                        .clientResources(clientResources)
                        .commandTimeout(timeout)
                        .build());
        factory.afterPropertiesSet();

        System.out.println("[RedisConfig] 集群模式: " + nodes + " NAT: 127.0.0.1→" + externalHost);
        return factory;
    }

    private LettuceConnectionFactory createStandaloneFactory() {
        var config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        System.out.println("[RedisConfig] 单机模式: " + host + ":" + port);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public StreamMessageListenerContainer<String, ?> streamMessageListenerContainer(
            RedisConnectionFactory factory) {
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(2))
                .build();
        return StreamMessageListenerContainer.create(factory, options);
    }
}
