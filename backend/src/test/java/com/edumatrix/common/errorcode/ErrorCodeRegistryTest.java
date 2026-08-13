package com.edumatrix.common.errorcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ErrorCode} 与 {@code 00-通用约定.md} §9 错误码登记册的<b>逐码比对</b>。
 *
 * <h2>为什么要有它</h2>
 * <p>一致性检查器的 C3 比对的是各分册与登记册这两份<b>文档</b>，
 * 05-工程结构.md §E 明写它的盲区：「C3 会抓，但<b>代码里的字面量它看不到</b>」。
 * 这个测试补的正是那一段 —— 它直接解析文档表格，让「文档新增了一个码但枚举没跟上」
 * 或「枚举里多了一个文档没登记的码」在构建期就失败。
 *
 * <p><b>方向是文档 → 代码</b>：登记册是权威，枚举是它的副本。测试失败时改的是枚举，
 * 不是文档（契约 §6.3：新增错误码必须先在 §9 登记再在分册使用）。
 */
class ErrorCodeRegistryTest {

    /** §9 各表的数据行：{@code | code | 含义 | ... |}。§9.1 多一列 HTTP 状态。 */
    private static final Pattern ROW = Pattern.compile("^\\|\\s*(\\d{3,5})\\s*\\|(.+)$");

    private static Path registryPath() {
        // 测试的工作目录是 backend/
        return Paths.get("..", "docs", "03-API接口文档", "00-通用约定.md").normalize();
    }

    /** 从 §9 各表里抽出 code → 含义。 */
    private static Map<Integer, String> parseRegistry() throws IOException {
        List<String> lines = Files.readAllLines(registryPath(), StandardCharsets.UTF_8);
        Map<Integer, String> registry = new LinkedHashMap<>();
        boolean inSection9 = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inSection9 = line.startsWith("## 9. 错误码总表");
                continue;
            }
            if (!inSection9) {
                continue;
            }
            Matcher m = ROW.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            int code = Integer.parseInt(m.group(1));
            String[] cells = m.group(2).split("\\|");
            // §9.1 的列是 code | HTTP 状态 | 含义 | 处理建议；其余段是 code | 含义 | 典型触发场景
            String meaning = code < 1000 && cells.length >= 2 ? cells[1] : cells[0];
            registry.put(code, meaning.trim());
        }
        return registry;
    }

    @Test
    @DisplayName("登记册里的每一个码都必须在 ErrorCode 枚举里")
    void everyRegisteredCodeIsInEnum() throws IOException {
        Map<Integer, String> registry = parseRegistry();
        assertThat(registry)
                .as("§9 至少应解析出 90 个码；解析不到说明文档表格结构变了，先修本测试的解析")
                .hasSizeGreaterThan(90);

        TreeSet<Integer> missing = new TreeSet<>();
        for (Integer code : registry.keySet()) {
            if (ErrorCode.of(code) == null) {
                missing.add(code);
            }
        }
        assertThat(missing)
                .as("文档 §9 已登记但 ErrorCode 枚举缺失的码 —— 补枚举，不要改文档")
                .isEmpty();
    }

    @Test
    @DisplayName("枚举里不得出现登记册没有的码")
    void enumHasNoUnregisteredCode() throws IOException {
        Map<Integer, String> registry = parseRegistry();
        TreeSet<Integer> extra = new TreeSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            if (!registry.containsKey(code.getCode())) {
                extra.add(code.getCode());
            }
        }
        assertThat(extra)
                .as("枚举里有、但 §9 登记册没有的码 —— 契约 §6.3：新增错误码必须先在 §9 登记再使用")
                .isEmpty();
    }

    @Test
    @DisplayName("业务错误 HTTP 一律 200，框架层错误 HTTP 与 code 一致（§3.3）")
    void httpStatusFollowsLayer() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.getLayer() == ErrorCode.Layer.BUSINESS) {
                assertThat(code.httpStatus())
                        .as("业务错误 %s 的 HTTP 状态必须是 200", code.name())
                        .isEqualTo(200);
                assertThat(code.getCode())
                        .as("业务错误 %s 的 code 必须落在 1xxxx~4xxxx", code.name())
                        .isBetween(10000, 49999);
            } else {
                assertThat(code.httpStatus())
                        .as("框架层错误 %s 的 HTTP 状态必须与 code 一致", code.name())
                        .isEqualTo(code.getCode());
            }
        }
    }

    @Test
    @DisplayName("20017 保留号位、不复用（§9.3 定案）")
    void code20017IsReservedAndDeprecated() throws NoSuchFieldException {
        ErrorCode code = ErrorCode.of(20017);
        assertThat(code).isNotNull();
        assertThat(ErrorCode.class.getField(code.name()).isAnnotationPresent(Deprecated.class))
                .as("20017 已废弃：转码事件改为拉取消息队列后无签名校验环节。"
                        + "保留号位是为了不让历史日志里的 20017 产生歧义")
                .isTrue();
    }
}
