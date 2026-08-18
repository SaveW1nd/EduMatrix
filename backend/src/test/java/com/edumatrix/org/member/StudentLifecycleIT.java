package com.edumatrix.org.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退课 / 批量毕业归档 / 归档恢复（接口 23 / 24 / 25，PRD F1-7 / F1-8）。
 *
 * <p>贯穿三个接口的一条硬规则：<b>都不移动节点</b>（规则 15）——
 * 退课后学员仍留在原导师节点下，便于原责任人复盘与召回。
 */
class StudentLifecycleIT extends MemberIntegrationTestBase {

    private static final long S1_PROFILE = MemberFixtures.profileIdOf(MemberFixtures.S_BASE);

    @Test
    @DisplayName("F1-7 规则 6：退课 status 0→1，记 quit_time/quit_reason，写 change_type=7")
    void quitWritesStatusAndTrack() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"家长申请退费，课程终止\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("status").asInt()).isEqualTo(1);
        assertThat(data(response).path("quitTime").asText()).isNotBlank();
        assertThat(data(response).path("changeType").asInt())
                .isEqualTo(OrgNodeChangeLog.CHANGE_TYPE_QUIT);

        assertThat(memberFixtures.studentStatus(S1_PROFILE)).isEqualTo(1);
        assertThat(memberFixtures.changeLogCount(
                MemberFixtures.S_BASE, OrgNodeChangeLog.CHANGE_TYPE_QUIT)).isEqualTo(1);
    }

    @Test
    @DisplayName("F1-7 规则 4：退课【不移动节点】，学员仍在原导师名下")
    void quitDoesNotMoveNode() throws Exception {
        String token = loginAsRoot();
        String ancestorsBefore = memberFixtures.ancestorsOf(MemberFixtures.S_BASE);

        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"退费\"}");

        assertThat(memberFixtures.parentOf(MemberFixtures.S_BASE)).isEqualTo(MemberFixtures.T1);
        assertThat(memberFixtures.ancestorsOf(MemberFixtures.S_BASE)).isEqualTo(ancestorsBefore);
        // 退出统计分母：祖先链与导师档案各减 1
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT - 1);
        assertThat(memberFixtures.studentCountOf(MemberFixtures.A1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT - 1);
    }

    @Test
    @DisplayName("接口 24：按子树整批归档，archiveReason 默认 1，写 change_type=5，节点不移动")
    void archiveBySubtreeDefaultsToGraduated() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/students/archive", token,
                "{\"nodeId\":\"" + MemberFixtures.T1 + "\",\"remark\":\"2026 届统一结课\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("archivedCount").asInt())
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
        assertThat(data(response).path("archiveReason").asInt()).isEqualTo(1);
        assertThat(data(response).path("changeType").asInt())
                .isEqualTo(OrgNodeChangeLog.CHANGE_TYPE_GRADUATE);

        JsonNode affected = data(response).path("affectedTeachers");
        assertThat(affected).hasSize(1);
        assertThat(affected.get(0).path("archivedCount").asInt())
                .isEqualTo(MemberFixtures.STUDENT_COUNT);

        assertThat(memberFixtures.studentStatus(S1_PROFILE)).isEqualTo(2);
        // 节点不移动
        assertThat(memberFixtures.parentOf(MemberFixtures.S_BASE)).isEqualTo(MemberFixtures.T1);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1)).isZero();
    }

    @Test
    @DisplayName("接口 24：archiveReason=2 写下删除请求标记（脱敏倒计时由它决定）")
    void archiveWithDeletionRequestRecordsReason() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.postWithToken("/api/v1/org/students/archive", token,
                "{\"archiveReason\":2,\"studentIds\":[\"" + S1_PROFILE + "\"]}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(memberFixtures.studentArchiveReason(S1_PROFILE)).isEqualTo(2);
        // 刚归档，还没到 30 日，anonymized_at 仍为 NULL（PRD F7-3 第一条验收标准）
        assertThat(memberFixtures.studentAnonymizedAt(S1_PROFILE)).isNull();
    }

    @Test
    @DisplayName("接口 24：studentIds 与 nodeId 同时传或都不传 → 400")
    void archiveRequiresExactlyOneSelector() throws Exception {
        String token = loginAsRoot();

        assertThat(code(client.postWithToken("/api/v1/org/students/archive", token, "{}")))
                .isEqualTo(400);
        assertThat(code(client.postWithToken("/api/v1/org/students/archive", token,
                "{\"nodeId\":\"" + MemberFixtures.T1 + "\",\"studentIds\":[\""
                        + S1_PROFILE + "\"]}")))
                .isEqualTo(400);
    }

    @Test
    @DisplayName("接口 24：名单模式含非在读者 → 10208 整批拒绝")
    void archiveByIdsRejectsNonActiveStudent() throws Exception {
        String token = loginAsRoot();
        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"退费\"}");

        long another = MemberFixtures.profileIdOf(MemberFixtures.S_BASE + 1);
        JsonNode response = client.postWithToken("/api/v1/org/students/archive", token,
                "{\"studentIds\":[\"" + S1_PROFILE + "\",\"" + another + "\"]}");

        assertThat(code(response)).isEqualTo(10208);
        assertThat(memberFixtures.studentStatus(another)).as("另一人也不该被归档").isZero();
    }

    @Test
    @DisplayName("接口 25：恢复退课学员 → status 0，重新计入分母，写 change_type=6")
    void unarchiveRestoresQuitStudent() throws Exception {
        String token = loginAsRoot();
        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"退费\"}");

        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/unarchive", token,
                "{\"remark\":\"学员续费复课\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("status").asInt()).isZero();
        assertThat(data(response).path("changeType").asInt())
                .isEqualTo(OrgNodeChangeLog.CHANGE_TYPE_UNARCHIVE);
        assertThat(memberFixtures.studentStatus(S1_PROFILE)).isZero();
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
    }

    @Test
    @DisplayName("接口 25：对在读学员调恢复 → 10204")
    void unarchiveActiveStudentIsRejected() throws Exception {
        String token = loginAsRoot();
        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/unarchive", token, "{}");
        assertThat(code(response)).isEqualTo(10204);
    }

    @Test
    @DisplayName("F1-8 规则 6：恢复时可重新指定归属节点，走移动事务重算 ancestors")
    void unarchiveCanRemount() throws Exception {
        String token = loginAsRoot();
        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"退费\"}");

        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/unarchive", token,
                "{\"toParentNodeId\":\"" + MemberFixtures.T2 + "\",\"remark\":\"转由李强带教\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(memberFixtures.parentOf(MemberFixtures.S_BASE)).isEqualTo(MemberFixtures.T2);
        assertThat(memberFixtures.ancestorsOf(MemberFixtures.S_BASE))
                .isEqualTo("0," + MemberFixtures.TENANT_ID + "," + MemberFixtures.A1
                        + "," + MemberFixtures.T2);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T2)).isEqualTo(1);
    }

    @Test
    @DisplayName("F1-8 规则 5：教师调用归档 / 恢复返回 403（org:student:archive 未绑 teacher）")
    void archiveIsAdminOnly() throws Exception {
        String teacherToken = loginAs(MemberFixtures.T1);

        assertThat(code(client.postWithToken("/api/v1/org/students/archive", teacherToken,
                "{\"studentIds\":[\"" + S1_PROFILE + "\"]}")))
                .isEqualTo(403);
        assertThat(code(client.postWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/unarchive", teacherToken, "{}")))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("接口 18：已退课学员不可修改 → 10203")
    void quitStudentCannotBeUpdated() throws Exception {
        String token = loginAsRoot();
        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"退费\"}");

        JsonNode response = client.putWithToken("/api/v1/org/students/" + S1_PROFILE, token, """
                {"realName":"改名","phone":"17099991234","studentNo":"S07001"}
                """);
        assertThat(code(response)).isEqualTo(10203);
    }

    @Test
    @DisplayName("PRD F1-9 规则 2/7：异动轨迹只增不改；学员转走后原上级即失去查看权")
    void changeLogIsAppendOnlyAndScopeBound() throws Exception {
        String token = loginAsRoot();
        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/quit", token,
                "{\"quitReason\":\"退费\"}");
        client.postWithToken("/api/v1/org/students/" + S1_PROFILE + "/unarchive", token, "{}");

        JsonNode logs = client.getWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/change-logs", token);
        assertThat(code(logs)).isEqualTo(200);
        // 退课 + 恢复两条都在，倒序：最近的在前
        assertThat(data(logs).size()).isGreaterThanOrEqualTo(2);
        assertThat(data(logs).get(0).path("changeType").asInt())
                .isEqualTo(OrgNodeChangeLog.CHANGE_TYPE_UNARCHIVE);
        assertThat(data(logs).get(0).path("changeTypeName").asText()).isEqualTo("归档恢复");

        // 转交到 A2 之后，原上级 A1 立即查不到这名学员的轨迹（PRD F1-9 规则 7）
        client.postWithToken("/api/v1/org/students/transfer-admin", token,
                "{\"studentIds\":[\"" + S1_PROFILE + "\"],\"toNodeId\":\"" + MemberFixtures.A2 + "\"}");
        String a1Token = loginAs(MemberFixtures.A1);
        assertThat(code(client.getWithToken(
                "/api/v1/org/students/" + S1_PROFILE + "/change-logs", a1Token)))
                .isEqualTo(404);
    }
}
