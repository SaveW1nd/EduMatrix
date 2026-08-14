package com.edumatrix.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.auth.support.ProtectedProbeController;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.frozen.FrozenNodeCache;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结集鉴权的验收（判据 4 的前半 + 判据 6）。
 *
 * <p>停用/启用节点本身是<b>模块 06</b> 的接口，此刻还不存在，所以这里按契约 §2.3 规定的
 * <b>顺序</b>手工模拟这两个动作：
 * <pre>
 * 停用：先 SADD 冻结集  →  再提交 org_node.status = 1
 * 启用：先提交事务      →  再 SREM
 * </pre>
 * 两个方向都朝「宁可多拦一瞬，不可漏放一瞬」偏 —— 反过来会出现
 * 「库里已停用但 Redis 还没写」的放行窗口。
 */
class FrozenNodeAuthIT extends AuthIntegrationTestBase {

    @Autowired
    private FrozenNodeCache frozenNodeCache;

    // =====================================================================
    // 判据 4（前半）：已持有的 accessToken 下一次请求即 10017，不等 2 小时
    // =====================================================================

    @Test
    @DisplayName("判据 4｜停用管理员节点后，其子树内已登录账号的下一次请求即 10017")
    void frozenBranchRejectsAlreadyIssuedToken() throws Exception {
        String token = client.loginForToken(AuthFixtures.STUDENT1_USERNAME, AuthFixtures.PASSWORD);

        // 停用之前：同一个 Token 正常通行
        assertThat(client.getWithToken(ProtectedProbeController.PATH, token).path("code").asInt())
                .isEqualTo(200);

        // 停用管理员分支（先 SADD 再提交）
        frozenNodeCache.add(AuthFixtures.TENANT_ID, AuthFixtures.ADMIN_NODE);
        fixtures.setNodeStatus(AuthFixtures.ADMIN_NODE, 1);

        // 停用之后：同一个 Token 立即失效 —— 不重新登录、不等 accessToken 过期
        assertThat(client.getWithToken(ProtectedProbeController.PATH, token).path("code").asInt())
                .as("契约 §2.3：只堵登录不够，已下发的 accessToken 还有最长 2 小时有效期，"
                        + "而分支冻结的业务意图（分部关停、欠费停服、合规冻结）不接受这个延迟")
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());
    }

    @Test
    @DisplayName("判据 4 的反面｜停用教师节点后，其名下学员已持有的 Token 仍然有效")
    void frozenTeacherDoesNotAffectHisStudents() throws Exception {
        String studentToken = client.loginForToken(AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD);
        String teacherToken = client.loginForToken(AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD);

        frozenNodeCache.add(AuthFixtures.TENANT_ID, AuthFixtures.TEACHER_NODE);
        fixtures.setNodeStatus(AuthFixtures.TEACHER_NODE, 1);

        assertThat(client.getWithToken(ProtectedProbeController.PATH, teacherToken).path("code").asInt())
                .as("自身命中冻结集 → 拒（教师/学生的「仅本人」停用）")
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());

        assertThat(client.getWithToken(ProtectedProbeController.PATH, studentToken).path("code").asInt())
                .as("学员的祖先链含被停用的教师节点，但 node_type=2 不是 1 —— 不冻结。"
                        + "照契约冻结集那一节的字面（求交即拒）实现，这里会 10017，"
                        + "学员被踢出去后重新登录还能进（登录侧是对的），进来一发请求又被踢：死循环")
                .isEqualTo(200);
    }

    // =====================================================================
    // 判据 6：停用 → 启用往返，sys_user.status 全程未被改动
    // =====================================================================

    @Test
    @DisplayName("判据 6｜停用教师再启用，该教师能正常登录；sys_user.status 全程为 0")
    void disableThenEnableIsSymmetric() throws Exception {
        assertThat(fixtures.userStatus(AuthFixtures.TEACHER_USER))
                .as("前置：账号级封禁位是 0")
                .isEqualTo(0);

        // ---- 停用：先 SADD 再提交 ----
        frozenNodeCache.add(AuthFixtures.TENANT_ID, AuthFixtures.TEACHER_NODE);
        fixtures.setNodeStatus(AuthFixtures.TEACHER_NODE, 1);

        assertThat(client.login(AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());
        assertThat(fixtures.userStatus(AuthFixtures.TEACHER_USER))
                .as("停用只写 org_node.status 一行 —— 顺手把 sys_user.status 也置 1 的话，"
                        + "启用时无人复位（启用只改回 org_node.status），该账号永久登不进来，"
                        + "而机构管理员没有任何接口能修复它（契约 §2.3）")
                .isEqualTo(0);

        // ---- 启用：先提交事务再 SREM ----
        fixtures.setNodeStatus(AuthFixtures.TEACHER_NODE, 0);
        frozenNodeCache.remove(AuthFixtures.TENANT_ID, AuthFixtures.TEACHER_NODE);

        JsonNode relogin = client.login(AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD);
        assertThat(relogin.path("code").asInt())
                .as("停用与启用天然对称可逆，正因为写的是同一字段的两个方向")
                .isEqualTo(200);

        assertThat(fixtures.userStatus(AuthFixtures.TEACHER_USER))
                .as("往返一圈后账号级封禁位仍是 0 —— 本模块任何路径都不写这一列")
                .isEqualTo(0);
        assertThat(fixtures.nodeStatus(AuthFixtures.TEACHER_NODE)).isEqualTo(0);

        // 启用后已持有的新 Token 也照常通行
        String token = relogin.path("data").path("accessToken").asText();
        assertThat(client.getWithToken(ProtectedProbeController.PATH, token).path("code").asInt())
                .isEqualTo(200);
    }

    // =====================================================================
    // 刷新令牌同样受停用约束（工单规则 7：停用后不许续签）
    // =====================================================================

    @Test
    @DisplayName("停用后不许续签：refreshToken 刷新同样返回 10017")
    void frozenAccountCannotRefresh() throws Exception {
        JsonNode login = client.login(AuthFixtures.STUDENT1_USERNAME, AuthFixtures.PASSWORD);
        String refreshToken = login.path("data").path("refreshToken").asText();

        frozenNodeCache.add(AuthFixtures.TENANT_ID, AuthFixtures.ADMIN_NODE);
        fixtures.setNodeStatus(AuthFixtures.ADMIN_NODE, 1);

        assertThat(client.refresh(refreshToken).path("code").asInt())
                .as("少跑这一段的话，被停用的账号能靠 7 天有效期的 refreshToken 无限续命")
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());
    }
}
