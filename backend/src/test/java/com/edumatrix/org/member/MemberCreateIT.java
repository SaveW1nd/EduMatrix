package com.edumatrix.org.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;
import com.edumatrix.org.member.service.MemberOperLogWriter;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建人：PRD F1-3（三写一事务、唯一性、初始密码、默认挂创建者节点）+ PRD F7-1（监护人同意留痕）。
 *
 * <p>其中 {@link #missingGuardianConsentCreatesNothing} 是<b>工单的自检项</b>：
 * 「未勾选监护人同意时创建学生返回 {@code 400} 且<b>无任何节点/账号/档案产生</b>」——
 * 验的是「三写一事务」在<b>校验失败</b>时也成立。
 */
class MemberCreateIT extends MemberIntegrationTestBase {

    // =====================================================================
    // PRD F7-1 监护人同意留痕（合规验收标准）
    // =====================================================================

    @Test
    @DisplayName("F7-1 自检：未勾选监护人同意 → 400，且无任何节点/账号/档案产生")
    void missingGuardianConsentCreatesNothing() throws Exception {
        String token = loginAsRoot();
        int nodesBefore = memberFixtures.nodeCountInTenant();
        int usersBefore = memberFixtures.userCountInTenant();
        int profilesBefore = memberFixtures.studentProfileCountInTenant();

        // 不传 guardianConsent
        JsonNode omitted = client.postWithToken("/api/v1/org/students", token, """
                {"realName":"陈嘉禾","phone":"17099990001","studentNo":"S07901"}
                """);
        assertThat(code(omitted)).isEqualTo(400);

        // 显式传 false
        JsonNode explicitFalse = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":false,"realName":"陈嘉禾",
                 "phone":"17099990001","studentNo":"S07901"}
                """);
        assertThat(code(explicitFalse)).isEqualTo(400);

        // 【自检的核心】三张表一行都不能多
        assertThat(memberFixtures.nodeCountInTenant()).isEqualTo(nodesBefore);
        assertThat(memberFixtures.userCountInTenant()).isEqualTo(usersBefore);
        assertThat(memberFixtures.studentProfileCountInTenant()).isEqualTo(profilesBefore);
        // 留痕也不能有 —— 建档回滚了却留下一条「已取得监护人同意」比没有更糟
        assertThat(memberFixtures.operLogCount(MemberOperLogWriter.ACTION_GUARDIAN_CONSENT))
                .isZero();
    }

    @Test
    @DisplayName("F7-1：勾选监护人同意 → 写一条 sys_oper_log 留痕（操作人 + 时间戳）")
    void guardianConsentIsRecorded() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"realName":"陈嘉禾","phone":"17099990002",
                 "studentNo":"S07902","guardianName":"陈国安","guardianPhone":"17088880002"}
                """);
        assertThat(code(response)).isEqualTo(200);

        assertThat(memberFixtures.operLogCount(MemberOperLogWriter.ACTION_GUARDIAN_CONSENT))
                .as("F7-1 要求留痕，而全库此前没有任何一处往 sys_oper_log 写行（F-25）")
                .isEqualTo(1);
    }

    // =====================================================================
    // PRD F1-3 三写一事务
    // =====================================================================

    @Test
    @DisplayName("F1-3 规则 1：创建学生三写一事务 —— 节点 + 账号 + 档案同时产生")
    void createStudentWritesThreeTables() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"realName":"陈嘉禾","phone":"17099990003",
                 "studentNo":"S07903"}
                """);
        assertThat(code(response)).isEqualTo(200);

        JsonNode data = data(response);
        assertThat(data.path("id").asText()).isNotBlank();      // org_student.id
        assertThat(data.path("nodeId").asText()).isNotBlank();  // org_node.id
        assertThat(data.path("userId").asText()).isNotBlank();  // sys_user.id
        assertThat(data.path("status").asInt()).isZero();       // 在读
        assertThat(data.path("changeType").asInt()).isEqualTo(1); // 建档轨迹

        // 默认挂创建者所在节点（PRD F1-3 规则 4）—— 操作人是机构最高管理员
        assertThat(data.path("parentNodeId").asText())
                .isEqualTo(String.valueOf(MemberFixtures.ROOT));
    }

    @Test
    @DisplayName("F1-3 规则 3：初始密码明文仅返回一次，且不是手机号后 6 位")
    void initialPasswordIsReturnedOnceAndIsNotDerivable() throws Exception {
        String token = loginAsRoot();
        String phone = "17099990004";

        JsonNode response = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"realName":"陈嘉禾","phone":"%s","studentNo":"S07904"}
                """.formatted(phone));

        String initPassword = data(response).path("initPassword").asText();
        assertThat(initPassword).isNotBlank();
        assertThat(initPassword.length()).isGreaterThanOrEqualTo(12);
        assertThat(data(response).path("pwdResetFlag").asInt()).isEqualTo(1);

        // 【严禁可由账号推导】用户名即手机号，同源意味着拿到名单即可登录任意账号
        assertThat(initPassword).isNotEqualTo(phone.substring(phone.length() - 6));
        assertThat(initPassword).doesNotContain(phone);

        // 明文不落库：库里只有 BCrypt 密文
        String stored = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE username = ?", String.class, phone);
        assertThat(stored).startsWith("$2");
        assertThat(stored).isNotEqualTo(initPassword);
    }

    @Test
    @DisplayName("F1-3 规则 4：教师创建学生 → 学生直接挂在该教师节点下，即刻成为名下学员")
    void teacherCreatedStudentLandsUnderTheTeacher() throws Exception {
        // 权限取舍见 F-29：菜单数据把 org:student:add 绑给了 teacher，
        // 而 03-02 §6.2 的权限栏写「仅 org_admin」——实现按 PRD + 菜单数据
        String teacherToken = loginAs(MemberFixtures.T1);

        JsonNode response = client.postWithToken("/api/v1/org/students", teacherToken, """
                {"guardianConsent":true,"realName":"新学员","phone":"17099990005",
                 "studentNo":"S07905"}
                """);
        assertThat(code(response))
                .as("PRD F1-3 规则 4「教师创建 → 即刻成为其名下学员」要求教师能调本接口")
                .isEqualTo(200);
        assertThat(data(response).path("parentNodeId").asText())
                .isEqualTo(String.valueOf(MemberFixtures.T1));

        // 即刻成为名下学员：教师档案计数 +1
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT + 1);
    }

    // =====================================================================
    // PRD F1-3 规则 2 唯一性四码
    // =====================================================================

    @Test
    @DisplayName("F1-3 规则 2：学号机构内唯一 → 10202，且冲突时不产生任何行")
    void duplicateStudentNoIsRejected() throws Exception {
        String token = loginAsRoot();
        int nodesBefore = memberFixtures.nodeCountInTenant();

        JsonNode response = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"realName":"重号学员","phone":"17099990006",
                 "studentNo":"S07001"}
                """);
        assertThat(code(response)).isEqualTo(10202);
        assertThat(memberFixtures.nodeCountInTenant()).isEqualTo(nodesBefore);
    }

    @Test
    @DisplayName("F1-3 规则 2：手机号本租户内唯一 → 10013")
    void duplicatePhoneIsRejected() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"realName":"重号学员",
                 "phone":"%s","studentNo":"S07906"}
                """.formatted(MemberFixtures.phoneOf(MemberFixtures.STUDENTS[0])));
        assertThat(code(response)).isEqualTo(10013);
    }

    @Test
    @DisplayName("F1-3 规则 2：教师工号机构内唯一 → 10201")
    void duplicateTeacherNoIsRejected() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/teachers", token, """
                {"parentNodeId":"%s","realName":"周雨","phone":"17099990007",
                 "teacherNo":"T07001"}
                """.formatted(MemberFixtures.A1));
        assertThat(code(response)).isEqualTo(10201);
    }

    // =====================================================================
    // 承载规则（NodeTypeRule 的唯一判定，建人入口也走它）
    // =====================================================================

    @Test
    @DisplayName("承载规则：教师节点下不能建教师 → 10105；学生节点下不能建任何人 → 10106")
    void parentChildTypeRulesApplyToCreation() throws Exception {
        String token = loginAsRoot();

        JsonNode underTeacher = client.postWithToken("/api/v1/org/teachers", token, """
                {"parentNodeId":"%s","realName":"周雨","phone":"17099990008",
                 "teacherNo":"T07903"}
                """.formatted(MemberFixtures.T1));
        assertThat(code(underTeacher)).isEqualTo(10105);

        JsonNode underStudent = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"parentNodeId":"%s","realName":"学员",
                 "phone":"17099990009","studentNo":"S07907"}
                """.formatted(MemberFixtures.STUDENTS[0]));
        assertThat(code(underStudent)).isEqualTo(10106);
    }

    @Test
    @DisplayName("建教师：三写一事务 + 冗余计数 + 建档轨迹")
    void createTeacherWritesThreeTables() throws Exception {
        String token = loginAsRoot();
        int childCountBefore = memberFixtures.childCountOf(MemberFixtures.A1);

        JsonNode response = client.postWithToken("/api/v1/org/teachers", token, """
                {"parentNodeId":"%s","realName":"周雨","phone":"17099990010",
                 "teacherNo":"T07904","subject":"英语","entryDate":"2026-08-12"}
                """.formatted(MemberFixtures.A1));

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("studentCount").asInt()).isZero();
        assertThat(data(response).path("changeType").asInt()).isEqualTo(1);
        assertThat(memberFixtures.childCountOf(MemberFixtures.A1)).isEqualTo(childCountBefore + 1);

        long nodeId = data(response).path("nodeId").asLong();
        assertThat(memberFixtures.changeLogCount(nodeId, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("建管理员：两写一事务（管理员没有档案表）")
    void createAdminWritesTwoTables() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/admins", token, """
                {"parentNodeId":"%s","realName":"孙浩","phone":"17099990011","sort":3}
                """.formatted(MemberFixtures.A1));

        assertThat(code(response)).isEqualTo(200);
        // 管理员无档案表 —— id 恒为 null，nodeId 才是本节其余接口的 {id}
        assertThat(data(response).path("id").isNull()).isTrue();
        assertThat(data(response).path("nodeId").asText()).isNotBlank();
    }
}
