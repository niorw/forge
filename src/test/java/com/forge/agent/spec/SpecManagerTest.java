package com.forge.agent.spec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpecManager 单元测试
 * 使用临时目录模拟文件系统，不启动 Spring 容器
 */
@DisplayName("SpecManager Spec 管理器测试")
class SpecManagerTest {

    private SpecManager specManager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        specManager = new SpecManager();
        // 通过反射设置 @Value 注入的 specDirectory
        Field field = SpecManager.class.getDeclaredField("specDirectory");
        field.setAccessible(true);
        field.set(specManager, tempDir.toString());
    }

    // ==================== listSpecs ====================

    @Test
    @DisplayName("listSpecs 目录为空应返回空列表")
    void listSpecs_emptyDir_shouldReturnEmptyList() {
        List<String> specs = specManager.listSpecs();
        assertNotNull(specs);
        assertTrue(specs.isEmpty());
    }

    @Test
    @DisplayName("listSpecs 应列出所有 .md 文件并按名排序")
    void listSpecs_shouldListMdFilesSorted() throws IOException {
        // 创建测试文件
        Files.writeString(tempDir.resolve("beta-spec.md"), "内容B");
        Files.writeString(tempDir.resolve("alpha-spec.md"), "内容A");
        Files.writeString(tempDir.resolve("gamma-spec.md"), "内容C");
        // 非 .md 文件应被忽略
        Files.writeString(tempDir.resolve("readme.txt"), "忽略我");

        List<String> specs = specManager.listSpecs();
        assertEquals(3, specs.size());
        // 应按文件名排序
        assertEquals("alpha-spec.md", specs.get(0));
        assertEquals("beta-spec.md", specs.get(1));
        assertEquals("gamma-spec.md", specs.get(2));
    }

    @Test
    @DisplayName("listSpecs 目录不存在应返回空列表")
    void listSpecs_dirNotExist_shouldReturnEmptyList() throws Exception {
        // 设置一个不存在的目录
        Field field = SpecManager.class.getDeclaredField("specDirectory");
        field.setAccessible(true);
        field.set(specManager, "/non/existent/path");

        List<String> specs = specManager.listSpecs();
        assertNotNull(specs);
        assertTrue(specs.isEmpty());
    }

    // ==================== loadSpec ====================

    @Test
    @DisplayName("loadSpec 存在的文件应返回内容")
    void loadSpec_existingFile_shouldReturnContent() throws IOException {
        String expected = "# Order Service Spec\n\n这是一个订单服务的 Spec";
        Files.writeString(tempDir.resolve("order-spec.md"), expected);

        String content = specManager.loadSpec("order-spec.md");
        assertEquals(expected, content);
    }

    @Test
    @DisplayName("loadSpec 不存在的文件应返回 null")
    void loadSpec_nonExistentFile_shouldReturnNull() {
        String content = specManager.loadSpec("no-such-spec.md");
        assertNull(content);
    }

    @Test
    @DisplayName("loadSpec 空文件应返回空字符串")
    void loadSpec_emptyFile_shouldReturnEmptyString() throws IOException {
        Files.writeString(tempDir.resolve("empty-spec.md"), "");

        String content = specManager.loadSpec("empty-spec.md");
        assertEquals("", content);
    }

    // ==================== matchSpecs ====================

    @Test
    @DisplayName("matchSpecs 应根据内容关键词匹配")
    void matchSpecs_shouldMatchByContentKeywords() throws IOException {
        Files.writeString(tempDir.resolve("order-spec.md"),
                "# 订单服务\n\n## 功能\n- 创建订单\n- 退款\n- 查询订单状态");
        Files.writeString(tempDir.resolve("user-spec.md"),
                "# 用户服务\n\n## 功能\n- 用户注册\n- 用户登录\n- 修改密码");
        Files.writeString(tempDir.resolve("payment-spec.md"),
                "# 支付服务\n\n## 功能\n- 发起支付\n- 支付回调\n- 退款");

        // "退款" 应匹配 order-spec 和 payment-spec，不匹配 user-spec
        // 注意：extractKeywords 按长度>=2过滤，所以用精确关键词
        List<String> matched = specManager.matchSpecs("退款 订单");
        assertFalse(matched.isEmpty());
        assertTrue(matched.contains("order-spec.md"));
        // user-spec 不包含"退款"关键词
        assertFalse(matched.contains("user-spec.md"));
    }

    @Test
    @DisplayName("matchSpecs 需求为 null 应返回空列表")
    void matchSpecs_nullRequirement_shouldReturnEmptyList() throws IOException {
        Files.writeString(tempDir.resolve("order-spec.md"), "# 订单服务");

        List<String> matched = specManager.matchSpecs(null);
        assertNotNull(matched);
        assertTrue(matched.isEmpty());
    }

    @Test
    @DisplayName("matchSpecs 无匹配内容应返回空列表")
    void matchSpecs_noMatch_shouldReturnEmptyList() throws IOException {
        Files.writeString(tempDir.resolve("order-spec.md"), "# 订单服务");

        List<String> matched = specManager.matchSpecs("人工智能机器学习");
        assertTrue(matched.isEmpty());
    }

    @Test
    @DisplayName("matchSpecs 文件名匹配应得分更高")
    void matchSpecs_fileNameMatch_shouldScoreHigher() throws IOException {
        // order-spec.md 文件名包含 "order"
        Files.writeString(tempDir.resolve("order-spec.md"),
                "# 通用内容");
        // other-spec.md 内容包含 "order"
        Files.writeString(tempDir.resolve("other-spec.md"),
                "# 其他服务\n\n包含订单 order 相关内容");

        List<String> matched = specManager.matchSpecs("order 订单");
        assertFalse(matched.isEmpty());
        // order-spec.md 应排在前面（文件名匹配得分 +100）
        assertEquals("order-spec.md", matched.get(0));
    }

    // ==================== loadAllSpecs ====================

    @Test
    @DisplayName("loadAllSpecs 应返回所有 Spec 的文件名和内容映射")
    void loadAllSpecs_shouldReturnAllSpecs() throws IOException {
        Files.writeString(tempDir.resolve("a-spec.md"), "内容A");
        Files.writeString(tempDir.resolve("b-spec.md"), "内容B");

        var allSpecs = specManager.loadAllSpecs();
        assertEquals(2, allSpecs.size());
        assertEquals("内容A", allSpecs.get("a-spec.md"));
        assertEquals("内容B", allSpecs.get("b-spec.md"));
    }
}
