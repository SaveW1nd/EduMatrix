package com.edumatrix.common.operlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code sys_oper_log.params} 脱敏 —— 契约 §7.2 与 {@code @OperLog} 类注释的落地验证。
 *
 * <h2>攻击侧与保留侧都要有</h2>
 * <p>只测「敏感字段被脱掉」的话，把脱敏器换成「所有字段一律 {@code ***}」也全绿，
 * 而那会让 {@code params} 这一列彻底失去审计价值。所以每组都配一条保留侧断言。
 */
class SensitiveParamMaskerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode maskOf(Object value) {
        return SensitiveParamMasker.mask(mapper.valueToTree(value));
    }

    // ====================================================================
    // 攻击侧
    // ====================================================================

    @Test
    @DisplayName("口令类字段整值替换（PRD §7.3「明文永不落库」）")
    void secretsAreRedacted() {
        JsonNode masked = maskOf(Map.of(
                "password", "P@ssw0rd-2026",
                "newPassword", "N3wPass!",
                "oldPassword", "Old1234!",
                "initPassword", "Init5678!",
                "confirmPassword", "Init5678!"));

        assertThat(masked.get("password").asText()).isEqualTo(SensitiveParamMasker.REDACTED);
        assertThat(masked.get("newPassword").asText()).isEqualTo(SensitiveParamMasker.REDACTED);
        assertThat(masked.get("oldPassword").asText()).isEqualTo(SensitiveParamMasker.REDACTED);
        assertThat(masked.get("initPassword").asText()).isEqualTo(SensitiveParamMasker.REDACTED);
        assertThat(masked.get("confirmPassword").asText()).isEqualTo(SensitiveParamMasker.REDACTED);
    }

    @Test
    @DisplayName("掩码而不是整值替换：口令不留长度线索，手机号留掩码位供对账（契约 §7.2 第 3 条）")
    void secretsLeakNoLengthWhilePhonesKeepMaskDigits() {
        JsonNode masked = maskOf(Map.of("password", "a", "phone", "13812345678"));

        // 口令：连长度都不能泄露 —— 输出与明文长度无关
        assertThat(masked.get("password").asText()).isEqualTo(SensitiveParamMasker.REDACTED);
        // 手机号：保留掩码位，与契约 §7.2 第 2 条跑马灯水印同格式
        assertThat(masked.get("phone").asText()).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("K12 敏感个人信息：guardianPhone 必须被掩码（契约 §7.2）")
    void guardianPhoneIsMasked() {
        JsonNode masked = maskOf(Map.of(
                "guardianName", "李建国",
                "guardianPhone", "13912344001"));

        assertThat(masked.get("guardianPhone").asText()).isEqualTo("139****4001");
        // 监护人【姓名】不是要脱敏的对象：契约 §7.2 第 3 条点名的是 guardian_phone / phone。
        // 姓名脱掉的话「谁给谁建的档」这条审计线索就断了
        assertThat(masked.get("guardianName").asText()).isEqualTo("李建国");
    }

    /**
     * 这一条守的是黑名单的<b>覆盖面</b>：全库现有的四种手机号字段名一次列全。
     *
     * <p>新增第五种（比如某个模块写了 {@code emergencyPhone}）时，如果它没被脱敏，
     * 由于匹配规则是「小写后包含 {@code phone}」，它<b>会</b>被脱掉 —— 这正是选包含
     * 而不是全等的理由。本用例把这条规则钉住：改成全等匹配，下面四个里至少三个会红。
     */
    @Test
    @DisplayName("全库四种手机号字段名全覆盖（改成全等匹配会红）")
    void allKnownPhoneFieldNamesAreCovered() {
        JsonNode masked = maskOf(Map.of(
                "phone", "13800000001",
                "guardianPhone", "13800000002",
                "contactPhone", "13800000003",
                "refUserPhone", "13800000004"));

        assertThat(masked.get("phone").asText()).isEqualTo("138****0001");
        assertThat(masked.get("guardianPhone").asText()).isEqualTo("138****0002");
        assertThat(masked.get("contactPhone").asText()).isEqualTo("138****0003");
        assertThat(masked.get("refUserPhone").asText()).isEqualTo("138****0004");
    }

    @Test
    @DisplayName("嵌套 DTO 与数组里的敏感字段一样要脱（正则替换方案会在这里漏）")
    void nestedAndArrayValuesAreMasked() {
        JsonNode masked = maskOf(Map.of(
                "students", List.of(
                        Map.of("realName", "李小明", "guardianPhone", "13912344001"),
                        Map.of("realName", "王小红", "guardianPhone", "13912344002")),
                "operator", Map.of("username", "admin_qmx", "phone", "13800001111")));

        assertThat(masked.get("students").get(0).get("guardianPhone").asText()).isEqualTo("139****4001");
        assertThat(masked.get("students").get(1).get("guardianPhone").asText()).isEqualTo("139****4002");
        assertThat(masked.get("operator").get("phone").asText()).isEqualTo("138****1111");
    }

    @Test
    @DisplayName("短于 7 位的手机号整值替换 —— 掩不住的短串原样落库比掩错更糟")
    void shortPhoneIsRedactedEntirely() {
        assertThat(SensitiveParamMasker.maskPhone("12345")).isEqualTo(SensitiveParamMasker.REDACTED);
    }

    // ====================================================================
    // 保留侧 —— 没有这一组，「一律 *** 」也会全绿
    // ====================================================================

    @Test
    @DisplayName("【保留侧】非敏感字段一字不改：换成「一律脱敏」本用例会红")
    void nonSensitiveFieldsSurviveUntouched() {
        JsonNode masked = maskOf(Map.of(
                "username", "s20260001",
                "realName", "李小明",
                "nodeId", "1953827104412590402",
                "userType", 3,
                "status", 0,
                "remark", "高一3班转入"));

        assertThat(masked.get("username").asText()).isEqualTo("s20260001");
        assertThat(masked.get("realName").asText()).isEqualTo("李小明");
        assertThat(masked.get("nodeId").asText()).isEqualTo("1953827104412590402");
        assertThat(masked.get("userType").asInt()).isEqualTo(3);
        assertThat(masked.get("status").asInt()).isZero();
        assertThat(masked.get("remark").asText()).isEqualTo("高一3班转入");
    }

    @Test
    @DisplayName("【保留侧】名字里带 phone 之外字样的字段不受影响（如 telephoneAllowed 之类不存在时也不误伤）")
    void unrelatedFieldsAreNotTouched() {
        JsonNode masked = maskOf(Map.of("phoneticName", "LI XIAO MING", "pageNum", 1));

        // phoneticName 含 "phone" —— 会走手机号掩码路径，得到 "LI ****MING"。
        // 这是【有意的过度覆盖】：宁可多脱一个非敏感字段（代价是审计里少一列可读值），
        // 也不能漏脱一个未成年人监护人手机号（代价是敏感个人信息进了一张
        // "可按时间归档清理"的表）。本用例把这个取舍显式钉住 ——
        // 后来者看到 "LI ****MING" 这种怪值时，应该知道它是设计而不是 bug。
        assertThat(masked.get("phoneticName").asText()).isEqualTo("LI ****MING");
        assertThat(masked.get("pageNum").asInt()).isEqualTo(1);
    }
}
