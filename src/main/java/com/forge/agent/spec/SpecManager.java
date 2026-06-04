package com.forge.agent.spec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spec 管理器（只读）
 * 
 * 职责：
 * - 从 openspec/specs/ 目录加载人写好的 Spec 文件
 * - 提供 Spec 列表、按名称加载、模糊匹配
 * - 不负责写入（Spec 是人维护的，不是 LLM 生成的）
 * 
 * Spec 格式：YAML，结构见 order-service-spec.yaml
 */
@Component
public class SpecManager {

    private static final Logger log = LoggerFactory.getLogger(SpecManager.class);

    @Value("${agent.spec.directory:./openspec/specs}")
    private String specDirectory;

    /**
     * 列出所有可用的 Spec 文件名
     */
    public List<String> listSpecs() {
        try {
            Path dir = Paths.get(specDirectory);
            if (!Files.exists(dir)) {
                log.warn("Spec 目录不存在: {}", specDirectory);
                return List.of();
            }
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取 Spec 目录失败: " + specDirectory, e);
        }
    }

    /**
     * 加载指定 Spec 文件的完整内容
     * 
     * @param specFileName 文件名（如 "order-service-spec.yaml"）
     * @return Spec YAML 内容，不存在返回 null
     */
    public String loadSpec(String specFileName) {
        try {
            Path filePath = Paths.get(specDirectory, specFileName);
            if (!Files.exists(filePath)) {
                log.warn("Spec 文件不存在: {}", filePath);
                return null;
            }
            String content = Files.readString(filePath);
            log.info("Spec 已加载: {} ({} 字符)", specFileName, content.length());
            return content;
        } catch (IOException e) {
            throw new RuntimeException("读取 Spec 失败: " + specFileName, e);
        }
    }

    /**
     * 根据需求描述模糊匹配相关 Spec
     * 
     * 匹配策略：
     * 1. 按 Spec 的 module/name 字段匹配关键词
     * 2. 按接口名、方法名匹配
     * 3. 返回匹配度排序的结果
     * 
     * @param requirement 需求描述
     * @return 匹配的 Spec 文件名列表（按相关度排序）
     */
    public List<String> matchSpecs(String requirement) {
        List<String> allSpecs = listSpecs();
        if (allSpecs.isEmpty() || requirement == null) {
            return List.of();
        }

        String reqLower = requirement.toLowerCase();

        // 简单关键词匹配（生产环境可以用向量相似度）
        return allSpecs.stream()
                .map(specName -> {
                    String content = loadSpec(specName);
                    if (content == null) return null;
                    
                    String contentLower = content.toLowerCase();
                    int score = 0;
                    
                    // 文件名匹配
                    String nameKey = specName.replace("-spec.md", "").replace("-spec.yml", "");
                    if (reqLower.contains(nameKey.replace("-", " ")) || reqLower.contains(nameKey.replace("-", ""))) {
                        score += 100;
                    }
                    
                    // 内容关键词匹配
                    for (String keyword : extractKeywords(reqLower)) {
                        if (contentLower.contains(keyword)) {
                            score += 10;
                        }
                    }
                    
                    return score > 0 ? new AbstractMap.SimpleEntry<>(specName, score) : null;
                })
                .filter(Objects::nonNull)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 加载所有 Spec 的内容（用于 Planner Agent 拆 DAG 时的上下文）
     * 
     * @return Map<文件名, 内容>
     */
    public Map<String, String> loadAllSpecs() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String specName : listSpecs()) {
            String content = loadSpec(specName);
            if (content != null) {
                result.put(specName, content);
            }
        }
        return result;
    }

    private List<String> extractKeywords(String text) {
        // 提取有意义的关键词（去掉停用词）
        Set<String> stopWords = Set.of("的", "了", "是", "在", "和", "与", "对", "为", "the", "a", "an", "to", "for");
        return Arrays.stream(text.split("[\\s,，。、;；]+"))
                .filter(w -> w.length() >= 2 && !stopWords.contains(w))
                .toList();
    }
}
