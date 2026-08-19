package com.edumatrix.common.operlog;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * {@code sys_oper_log.params} 的脱敏。{@code @OperLog} 注解的类注释逐字：
 * 「<b>敏感字段必须脱敏后再进 {@code params}</b>（DDL 注释明写）。K12 场景下
 * {@code guardian_phone} / {@code phone} 属敏感个人信息（契约 §7.2），
 * 原样写进操作日志等于绕过了脱敏。切面实现时按字段名白/黑名单处理。」
 *
 * <h2>为什么是黑名单而不是白名单</h2>
 * <p>白名单要求穷举"哪些字段<b>可以</b>记"，而 {@code params} 的全部价值就是
 * 「操作者当时提交了什么」—— 白名单会把审计价值清零，最后没人看这一列。
 *
 * <p>黑名单的漏网风险由 {@code SensitiveParamMaskerTest} 兜：它对着一组<b>真实 DTO 的字段名</b>
 * 断言，新增一个含 {@code phone} 字样的字段而忘了它会不会被脱敏，那条用例会红。
 *
 * <h2>两类处理不同，不可混为一谈</h2>
 * <table border="1">
 *   <caption>两类敏感字段</caption>
 *   <tr><th>类</th><th>字段</th><th>处理</th><th>为什么</th></tr>
 *   <tr><td>口令</td><td>{@code password} / {@code newPassword} / {@code oldPassword} /
 *       {@code initPassword} / {@code confirmPassword}</td><td><b>整值 {@code ***}</b></td>
 *       <td>PRD §7.3「明文永不落库」。掩码也不行 —— 掩码泄露长度与首尾字符</td></tr>
 *   <tr><td>手机号</td><td>{@code phone} / {@code guardianPhone} / {@code contactPhone} /
 *       {@code refUserPhone}</td><td><b>掩码 {@code 138****5678}</b></td>
 *       <td>契约 §7.2；掩码位要留着供对账（§7.2 第 3 条「保留掩码位供对账」），
 *           与跑马灯水印同格式</td></tr>
 * </table>
 *
 * <p><b>匹配按"字段名小写后包含关键字"</b>，不是全等。理由：全库的手机号字段名有
 * {@code phone} / {@code guardianPhone} / {@code contactPhone} / {@code refUserPhone} 至少四种，
 * 而下一个模块一定会造出第五种。宁可多脱一个（审计里少一个非敏感字段），
 * 不可漏脱一个（把未成年人监护人手机号写进一张"可按时间归档清理"的表）。
 */
public final class SensitiveParamMasker {

    /** 整值替换的占位符。 */
    public static final String REDACTED = "***";

    /** 口令类关键字：命中即整值替换。 */
    private static final List<String> SECRET_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token", "accesskey", "credential");

    /** 手机号类关键字：命中即掩码。 */
    private static final List<String> PHONE_KEYS = List.of("phone", "mobile");

    private SensitiveParamMasker() {
    }

    /** 原地脱敏整棵 JSON 树（对象 / 数组 / 嵌套 DTO 全部覆盖）。 */
    public static JsonNode mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            maskObject(object);
        } else if (node instanceof ArrayNode array) {
            array.forEach(SensitiveParamMasker::mask);
        }
        return node;
    }

    private static void maskObject(ObjectNode object) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            String field = entry.getKey().toLowerCase(Locale.ROOT);
            JsonNode value = entry.getValue();
            if (containsAny(field, SECRET_KEYS)) {
                object.set(entry.getKey(), TextNode.valueOf(REDACTED));
            } else if (containsAny(field, PHONE_KEYS) && value.isTextual()) {
                object.set(entry.getKey(), TextNode.valueOf(maskPhone(value.asText())));
            } else {
                mask(value);
            }
        }
    }

    private static boolean containsAny(String lowerFieldName, List<String> keys) {
        for (String key : keys) {
            if (lowerFieldName.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code 13812345678} → {@code 138****5678}。
     *
     * <p>与契约 §7.2 第 2 条跑马灯水印的格式（{@code 李子墨 138****5678}）同源 ——
     * 全库只有一种手机号掩码写法。
     *
     * <p>长度不足 7 位的值整体替换：那不是一个正常手机号，而"掩不住"的短串
     * 原样落库比掩错更糟。
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String trimmed = phone.trim();
        if (trimmed.length() < 7) {
            return REDACTED;
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
