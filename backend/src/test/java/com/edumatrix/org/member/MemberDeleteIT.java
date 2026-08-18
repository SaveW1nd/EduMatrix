package com.edumatrix.org.member;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.member.support.MemberFixtures;
import com.edumatrix.org.member.support.MemberIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删人（接口 10 / 14 / 19，03-02 §4.4 / §5.4 / §6.4）。
 *
 * <p>三个接口共用一条硬规则：<b>写 {@code sys_user.deleted_at}，不写 {@code status}</b>
 * ——{@code status} 只表达「账号级封禁」，「这个人已被删除」是另一件事。
 */
class MemberDeleteIT extends MemberIntegrationTestBase {

    @Test
    @DisplayName("§5.4：教师名下仍有学员 → 10206（判据是节点级的「有没有学生子节点」）")
    void deleteTeacherWithStudentsIsRejected() throws Exception {
        String token = loginAsRoot();
        long teacherProfileId = MemberFixtures.profileIdOf(MemberFixtures.T1);

        JsonNode response = deleteWithToken("/api/v1/org/teachers/" + teacherProfileId, token);
        assertThat(code(response)).isEqualTo(10206);
    }

    @Test
    @DisplayName("§5.4：名下学员【全部已归档】仍返回 10206 —— 节点上还挂着学生子节点")
    void deleteTeacherWithArchivedStudentsIsStillRejected() throws Exception {
        String token = loginAsRoot();
        // 整棵子树归档：学籍状态全变 2，但节点一个都没移走
        client.postWithToken("/api/v1/org/students/archive", token,
                "{\"nodeId\":\"" + MemberFixtures.T1 + "\"}");

        JsonNode response = deleteWithToken(
                "/api/v1/org/teachers/" + MemberFixtures.profileIdOf(MemberFixtures.T1), token);
        assertThat(code(response))
                .as("§5.4：名下学员即使全部已退课/已归档，同样返回 10206 —— 请先转出再删除")
                .isEqualTo(10206);
    }

    @Test
    @DisplayName("§5.4：学员全部转走后可删除；账号写 deleted_at 而不是 status")
    void deleteEmptyTeacherSucceeds() throws Exception {
        String token = loginAsRoot();
        long[] profileIds = new long[MemberFixtures.STUDENT_COUNT];
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < profileIds.length; i++) {
            ids.append(i == 0 ? "" : ",").append("\"")
                    .append(MemberFixtures.profileIdOf(MemberFixtures.STUDENTS[i])).append("\"");
        }
        ids.append("]");
        client.postWithToken("/api/v1/org/students/assign-teacher-batch", token,
                "{\"studentIds\":" + ids + ",\"toTeacherNodeId\":\"" + MemberFixtures.T2 + "\"}");

        JsonNode response = deleteWithToken(
                "/api/v1/org/teachers/" + MemberFixtures.profileIdOf(MemberFixtures.T1), token);
        assertThat(code(response)).isEqualTo(200);

        // 【写 deleted_at，不写 status】契约 §2.3：status 只表达账号级封禁
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM sys_user WHERE id = ?", Integer.class,
                MemberFixtures.userIdOf(MemberFixtures.T1));
        Long deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM sys_user WHERE id = ?", Long.class,
                MemberFixtures.userIdOf(MemberFixtures.T1));
        assertThat(status).as("不得改 status").isZero();
        assertThat(deletedAt).as("必须写 deleted_at 毫秒时间戳").isPositive();

        // 父节点 child_count - 1
        assertThat(memberFixtures.childCountOf(MemberFixtures.A1)).isEqualTo(1);
    }

    @Test
    @DisplayName("§4.4：管理员节点下有任何子节点 → 10108（与教师的 10206 是两个码）")
    void deleteAdminWithChildrenIsRejected() throws Exception {
        String token = loginAsRoot();
        JsonNode response = deleteWithToken("/api/v1/org/admins/" + MemberFixtures.A1, token);
        assertThat(code(response)).isEqualTo(10108);
    }

    @Test
    @DisplayName("§4.4：空管理员节点可删；不得删自己 → 10012")
    void deleteEmptyAdminSucceedsButNotSelf() throws Exception {
        String token = loginAsRoot();

        assertThat(code(deleteWithToken("/api/v1/org/admins/" + MemberFixtures.A2, token)))
                .isEqualTo(200);
        assertThat(code(deleteWithToken("/api/v1/org/admins/" + MemberFixtures.ROOT, token)))
                .isEqualTo(10012);
    }

    @Test
    @DisplayName("§6.4：删学生 —— 在读时才减冗余计数，学习记录不在本模块删除范围内")
    void deleteStudentDecrementsCountsOnlyWhenActive() throws Exception {
        String token = loginAsRoot();
        long profileId = MemberFixtures.profileIdOf(MemberFixtures.S_BASE);

        assertThat(code(deleteWithToken("/api/v1/org/students/" + profileId, token)))
                .isEqualTo(200);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT - 1);
        assertThat(memberFixtures.studentCountOf(MemberFixtures.A1))
                .isEqualTo(MemberFixtures.STUDENT_COUNT - 1);

        // 已退课的学员：退课时已经减过一次，删除时不能再减（否则计数变负）
        long second = MemberFixtures.profileIdOf(MemberFixtures.S_BASE + 1);
        client.postWithToken("/api/v1/org/students/" + second + "/quit", token,
                "{\"quitReason\":\"退费\"}");
        int afterQuit = memberFixtures.teacherStudentCountOf(MemberFixtures.T1);
        deleteWithToken("/api/v1/org/students/" + second, token);
        assertThat(memberFixtures.teacherStudentCountOf(MemberFixtures.T1)).isEqualTo(afterQuit);
    }

    @Test
    @DisplayName("删除后同名重建放行（uk_username 追加 deleted_at 的直接收益）")
    void sameUsernameCanBeRecreatedAfterDeletion() throws Exception {
        String token = loginAsRoot();
        long profileId = MemberFixtures.profileIdOf(MemberFixtures.S_BASE);
        String phone = MemberFixtures.phoneOf(MemberFixtures.S_BASE);

        deleteWithToken("/api/v1/org/students/" + profileId, token);

        JsonNode recreated = client.postWithToken("/api/v1/org/students", token, """
                {"guardianConsent":true,"realName":"重建学员","phone":"%s","studentNo":"S07001"}
                """.formatted(phone));
        assertThat(code(recreated))
                .as("deleted_at 用时间戳而非 0/1，同一 username 可容纳任意多条已删除行")
                .isEqualTo(200);
    }
}
