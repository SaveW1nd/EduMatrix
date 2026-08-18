package com.edumatrix.org.member;

import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分配导师 / 批量分配 / 转交管理员（接口 20 / 21 / 22，PRD F1-4）。
 *
 * <p>三个动作都是模块 06 移动事务的语义化封装，本类因此重点验<b>语义那一层</b>：
 * 目标类型、学籍状态、整批拒绝、{@code changeType} 推断、冗余计数。
 */
class StudentAssignIT extends MemberIntegrationTestBase {

    private static String idsJson(long... profileIds) {
        return LongStream.of(profileIds).mapToObj(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    // =====================================================================
    // 接口 20 分配导师
    // =====================================================================

    @Test
    @DisplayName("接口 20：分配导师 = 移动节点，写 change_type=2，两边计数同事务维护")
    void assignTeacherMovesNodeAndMaintainsCounts() throws Exception {
        String token = loginAsRoot();
        long profileId = MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0]);

        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + profileId + "/assign-teacher", token,
                "{\"toTeacherNodeId\":\"" + MemberFixtures.T2 + "\",\"reason\":\"按科目匹配\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("changeType").asInt())
                .isEqualTo(OrgNodeChangeLog.CHANGE_TYPE_ASSIGN_TEACHER);
        assertThat(data(response).path("toParentId").asText())
                .isEqualTo(String.valueOf(MemberFixtures.T2));

        assertThat(memberFixtures.parentOf(MemberFixtures.STUDENTS[0]))
                .isEqualTo(MemberFixtures.T2);
        // org_teacher.student_count 由模块 06 的移动事务步骤 6 维护（有意增补的那一步）
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT - 1);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T2)).isEqualTo(1);
        // A1 是两位教师的公共祖先，净变化 0
        assertThat(memberFixtures.studentCountOf(MemberFixtures.A1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
    }

    @Test
    @DisplayName("接口 20：目标不是教师节点 → 10104（结构合法 ≠ 语义正确）")
    void assignToNonTeacherIsRejected() throws Exception {
        String token = loginAsRoot();
        long profileId = MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0]);

        // 学生挂到管理员节点下【结构上合法】，NodeTypeRule 会放行 ——
        // 但对「分配导师」这个语义来说选错了目标，必须由本模块拦下
        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + profileId + "/assign-teacher", token,
                "{\"toTeacherNodeId\":\"" + MemberFixtures.A2 + "\"}");
        assertThat(code(response)).isEqualTo(10104);
        assertThat(memberFixtures.parentOf(MemberFixtures.STUDENTS[0]))
                .isEqualTo(MemberFixtures.T1);
    }

    @Test
    @DisplayName("接口 20：目标即当前导师 → 10205")
    void assignToCurrentTeacherIsRejected() throws Exception {
        String token = loginAsRoot();
        long profileId = MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0]);

        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + profileId + "/assign-teacher", token,
                "{\"toTeacherNodeId\":\"" + MemberFixtures.T1 + "\"}");
        assertThat(code(response)).isEqualTo(10205);
    }

    @Test
    @DisplayName("接口 20：非在读学员 → 10203（单条用 10203，批量才是 10208）")
    void assignQuitStudentIsRejectedWith10203() throws Exception {
        String token = loginAsRoot();
        long profileId = MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0]);
        client.postWithToken("/api/v1/org/students/" + profileId + "/quit", token,
                "{\"quitReason\":\"家长申请退费\"}");

        JsonNode response = client.postWithToken(
                "/api/v1/org/students/" + profileId + "/assign-teacher", token,
                "{\"toTeacherNodeId\":\"" + MemberFixtures.T2 + "\"}");
        assertThat(code(response)).isEqualTo(10203);
    }

    // =====================================================================
    // 接口 21 批量分配导师 —— 整批拒绝
    // =====================================================================

    @Test
    @DisplayName("接口 21：批量分配 12 人全部成功，逐生一条 change_type=2")
    void assignBatchMovesAll() throws Exception {
        String token = loginAsRoot();
        long[] profileIds = new long[MemberFixtures.STUDENT_COUNT];
        for (int i = 0; i < profileIds.length; i++) {
            profileIds[i] = MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[i]);
        }

        JsonNode response = client.postWithToken("/api/v1/org/students/assign-teacher-batch",
                token, "{\"studentIds\":" + idsJson(profileIds)
                        + ",\"toTeacherNodeId\":\"" + MemberFixtures.T2 + "\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("assignedCount").asInt())
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1)).isZero();
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T2))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            assertThat(memberFixtures.changeLogCount(
                    studentNodeId, OrgNodeChangeLog.CHANGE_TYPE_ASSIGN_TEACHER)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("规则 6：名单含状态不符者 → 10208 整批拒绝，一个都不动")
    void batchWithInvalidStatusRejectsEverything() throws Exception {
        String token = loginAsRoot();
        // 把名单里【最后一个】改成已退课 —— 若实现是「边遍历边执行」，
        // 前 11 个会先被写掉（虽然事务会回滚），且错误码会取决于遍历顺序
        long lastProfileId = MemberFixtures.profileIdOf(
                MemberFixtures.STUDENTS[MemberFixtures.STUDENT_COUNT - 1]);
        client.postWithToken("/api/v1/org/students/" + lastProfileId + "/quit", token,
                "{\"quitReason\":\"退费\"}");

        long[] profileIds = new long[MemberFixtures.STUDENT_COUNT];
        for (int i = 0; i < profileIds.length; i++) {
            profileIds[i] = MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[i]);
        }

        JsonNode response = client.postWithToken("/api/v1/org/students/assign-teacher-batch",
                token, "{\"studentIds\":" + idsJson(profileIds)
                        + ",\"toTeacherNodeId\":\"" + MemberFixtures.T2 + "\"}");

        assertThat(code(response)).isEqualTo(10208);
        // 【整批拒绝】前 11 个也一个都没动
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            assertThat(memberFixtures.parentOf(studentNodeId))
                    .as("学员 %s 不应被移动", studentNodeId)
                    .isEqualTo(MemberFixtures.T1);
        }
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T2)).isZero();
    }

    @Test
    @DisplayName("规则 6：名单含不存在的档案 id → 10208（不暴露「它在别的租户存在」）")
    // id 取一个【合法但不存在】的雪花值：超过 Long.MAX 的字面量会在 JSON 反序列化阶段
    // 就被拒成 400，那测的是 Jackson 而不是本模块的整批拒绝
    void batchWithUnknownIdRejectsEverything() throws Exception {
        String token = loginAsRoot();
        JsonNode response = client.postWithToken("/api/v1/org/students/assign-teacher-batch",
                token, "{\"studentIds\":[\""
                        + MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0])
                        + "\",\"1967000000000099999\"],\"toTeacherNodeId\":\""
                        + MemberFixtures.T2 + "\"}");
        assertThat(code(response)).isEqualTo(10208);
        assertThat(memberFixtures.parentOf(MemberFixtures.STUDENTS[0]))
                .isEqualTo(MemberFixtures.T1);
    }

    // =====================================================================
    // 接口 22 转交给其他管理员
    // =====================================================================

    @Test
    @DisplayName("接口 22：转交后 teacherNodeId 置 null，detachedTeachers 列出原导师")
    void transferAdminDetachesTeacher() throws Exception {
        String token = loginAsRoot();
        long[] profileIds = {
                MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0]),
                MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[1])};

        JsonNode response = client.postWithToken("/api/v1/org/students/transfer-admin", token,
                "{\"studentIds\":" + idsJson(profileIds)
                        + ",\"toNodeId\":\"" + MemberFixtures.A2 + "\"}");

        assertThat(code(response)).isEqualTo(200);
        assertThat(data(response).path("transferredCount").asInt()).isEqualTo(2);
        assertThat(data(response).path("changeType").asInt())
                .isEqualTo(OrgNodeChangeLog.CHANGE_TYPE_TRANSFER_ADMIN);

        JsonNode detached = data(response).path("detachedTeachers");
        assertThat(detached).hasSize(1);
        assertThat(detached.get(0).path("teacherNodeId").asText())
                .isEqualTo(String.valueOf(MemberFixtures.T1));
        assertThat(detached.get(0).path("detachedCount").asInt()).isEqualTo(2);

        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT - 2);

        // 转交后学员在列表里的 teacherNodeId 为 null（父节点是管理员）
        JsonNode list = client.getWithToken(
                "/api/v1/org/students?nodeId=" + MemberFixtures.A2 + "&pageSize=10", token);
        assertThat(data(list).path("total").asInt()).isEqualTo(2);
        assertThat(data(list).path("list").get(0).path("teacherNodeId").isNull()).isTrue();
        assertThat(data(list).path("list").get(0).path("parentNodeType").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("接口 22：目标是教师节点 → 10104（请改用接口 20/21）")
    void transferToTeacherIsRejected() throws Exception {
        String token = loginAsRoot();
        JsonNode response = client.postWithToken("/api/v1/org/students/transfer-admin", token,
                "{\"studentIds\":[\""
                        + MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0])
                        + "\"],\"toNodeId\":\"" + MemberFixtures.T2 + "\"}");
        assertThat(code(response)).isEqualTo(10104);
    }

    @Test
    @DisplayName("契约 §2.4：跨子树转交被禁止 —— 下级管理员选不到平级的节点 → 10107")
    void crossSubtreeTransferIsRejected() throws Exception {
        // A1 看不见 A2（平级），「你不能把数据交给你看不见的人」
        String a1Token = loginAs(MemberFixtures.A1);
        JsonNode response = client.postWithToken("/api/v1/org/students/transfer-admin", a1Token,
                "{\"studentIds\":[\""
                        + MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[0])
                        + "\"],\"toNodeId\":\"" + MemberFixtures.A2 + "\"}");
        assertThat(code(response)).isEqualTo(10107);
    }
}
