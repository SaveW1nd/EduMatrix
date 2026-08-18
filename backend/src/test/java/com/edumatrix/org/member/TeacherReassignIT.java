package com.edumatrix.org.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD F1-4 教师调岗（03-02 接口 4 移动节点 + 模块 07 规则 5）。
 *
 * <p><b>这是模块 07 完成判据里被单独点名的一条</b>：「教师带 12 名学员调岗后
 * <b>13 个节点 {@code ancestors} 全部重算</b>、<b>原上级立即查不到</b>、
 * <b>只新增 1 条 {@code change_type=4}</b>」。三句话对应三个 {@code @Test}。
 *
 * <p><b>调岗走的是模块 06 的接口 4</b>，本模块<b>没有</b>「教师调岗」这个接口 ——
 * 03-02 §5.3 逐字：「上级变更（调岗）不走本接口，须使用接口 4（移动节点）」。
 * 本类因此测的是「模块 07 的数据形态下，模块 06 的移动事务表现是否符合 F1-4」，
 * 而这正是当初把 12 名学员播进夹具的原因。
 */
class TeacherReassignIT extends MemberIntegrationTestBase {

    @Test
    @DisplayName("F1-4：教师带 12 名学员调岗 → 13 个节点 ancestors 全部重算")
    void reassigningTeacherRecalculatesThirteenNodes() throws Exception {
        String token = loginAsRoot();

        JsonNode response = client.putWithToken(
                "/api/v1/org/nodes/" + MemberFixtures.T1 + "/move", token,
                "{\"toParentId\":\"" + MemberFixtures.A2 + "\",\"reason\":\"调岗至华南大区\"}");

        assertThat(code(response)).isEqualTo(200);
        // affectedNodeCount「含被移动节点自身」（§3.4 响应字段说明）：12 名学员 + 教师 = 13
        assertThat(data(response).path("affectedNodeCount").asInt()).isEqualTo(13);

        String expectedTeacherAncestors = "0," + MemberFixtures.TENANT_ID + "," + MemberFixtures.A2;
        assertThat(memberFixtures.ancestorsOf(MemberFixtures.T1))
                .isEqualTo(expectedTeacherAncestors);

        // 【逐个学员断言，不是抽查】12 个后代的 ancestors 必须全部重算 ——
        // 前缀替换 UPDATE 漏掉一行的表现是「那名学员的数据权限还挂在旧上级下」，
        // 抽查一两个是发现不了的
        String expectedStudentAncestors = expectedTeacherAncestors + "," + MemberFixtures.T1;
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            assertThat(memberFixtures.ancestorsOf(studentNodeId))
                    .as("学员 %s 的 ancestors", studentNodeId)
                    .isEqualTo(expectedStudentAncestors);
        }
    }

    @Test
    @DisplayName("F1-4：调岗后原上级立即查不到这 13 个节点，新上级立即看得到")
    void formerSupervisorLosesAccessImmediately() throws Exception {
        String rootToken = loginAsRoot();
        client.putWithToken("/api/v1/org/nodes/" + MemberFixtures.T1 + "/move", rootToken,
                "{\"toParentId\":\"" + MemberFixtures.A2 + "\"}");

        // 原上级 A1：教师与学员都已不在其子树内 —— 路径上的对象越界一律 404（契约 §2.4）
        String a1Token = loginAs(MemberFixtures.A1);
        assertThat(code(client.getWithToken("/api/v1/org/nodes/" + MemberFixtures.T1, a1Token)))
                .as("原上级查教师节点")
                .isEqualTo(404);
        assertThat(code(client.getWithToken(
                "/api/v1/org/nodes/" + MemberFixtures.STUDENTS[0], a1Token)))
                .as("原上级查随行学员")
                .isEqualTo(404);

        // 教师名下学员列表：原上级也查不到（接口 15 走同一套子树判定）
        assertThat(code(client.getWithToken(
                "/api/v1/org/teachers/" + MemberFixtures.profileIdOf(MemberFixtures.T1)
                        + "/students", a1Token)))
                .isEqualTo(404);

        // 新上级 A2 立即看得到
        String a2Token = loginAs(MemberFixtures.A2);
        assertThat(code(client.getWithToken("/api/v1/org/nodes/" + MemberFixtures.T1, a2Token)))
                .isEqualTo(200);
        JsonNode students = client.getWithToken(
                "/api/v1/org/teachers/" + MemberFixtures.profileIdOf(MemberFixtures.T1)
                        + "/students", a2Token);
        assertThat(code(students)).isEqualTo(200);
        assertThat(data(students).path("total").asInt()).isEqualTo(MemberFixtures.STUDENT_COUNT);
    }

    @Test
    @DisplayName("F1-4：调岗只新增 1 条 change_type=4，12 名随行学员一条都不写")
    void reassignWritesExactlyOneChangeLog() throws Exception {
        int teacherLogsBefore = memberFixtures.changeLogCount(MemberFixtures.T1);

        String token = loginAsRoot();
        client.putWithToken("/api/v1/org/nodes/" + MemberFixtures.T1 + "/move", token,
                "{\"toParentId\":\"" + MemberFixtures.A2 + "\"}");

        // 教师节点：恰好多 1 条，且类型是 4 教师调岗
        assertThat(memberFixtures.changeLogCount(MemberFixtures.T1))
                .isEqualTo(teacherLogsBefore + 1);
        assertThat(memberFixtures.changeLogCount(
                MemberFixtures.T1, OrgNodeChangeLog.CHANGE_TYPE_TEACHER_REASSIGN))
                .isEqualTo(1);

        // 【判据的核心】12 名随行学员一条轨迹都没有 ——
        // 轨迹记的是「这次操作」，不是「影响了几个节点」。写 13 条是错的
        for (long studentNodeId : MemberFixtures.STUDENTS) {
            assertThat(memberFixtures.changeLogCount(studentNodeId))
                    .as("随行学员 %s 不应新增任何轨迹", studentNodeId)
                    .isZero();
        }
    }

    @Test
    @DisplayName("F1-4：调岗后两条祖先链的 student_count 与 org_teacher.student_count 都对得上")
    void reassignMaintainsRedundantCounts() throws Exception {
        String token = loginAsRoot();
        client.putWithToken("/api/v1/org/nodes/" + MemberFixtures.T1 + "/move", token,
                "{\"toParentId\":\"" + MemberFixtures.A2 + "\"}");

        // 旧祖先链 A1 减 12，新祖先链 A2 加 12；ROOT 是两者的公共祖先，净变化 0
        assertThat(memberFixtures.studentCountOf(MemberFixtures.A1)).isZero();
        assertThat(memberFixtures.studentCountOf(MemberFixtures.A2))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);
        assertThat(memberFixtures.studentCountOf(MemberFixtures.ROOT))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);

        // 教师自己的档案计数【不变】：学员跟着一起走了，没换导师
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT);

        assertThat(memberFixtures.childCountOf(MemberFixtures.A1)).isEqualTo(1);
        assertThat(memberFixtures.childCountOf(MemberFixtures.A2)).isEqualTo(1);
    }
}
