package com.edumatrix.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.SystemIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>本组写接口一律仅 {@code super_admin}</b> 的验收（判据 8 + 冲突一的回归测试）。
 *
 * <h2>这个文件存在的理由：让 04 §B 那个口径再也回不来</h2>
 * <p>{@code 04-实施计划.md} §B 模块 03 的「必须落地的规则」第 2 条只写了
 * 「{@code PUT /users/{id}/status} 仅超管可调」，<b>漏了 §2.2~§2.5 四条</b>。
 * 而 03-01 §2 导语与 §2.2~§2.6 的「允许角色」栏逐条写的是<b>五个写接口全部仅 super_admin</b>，
 * 菜单初始化数据 {@code V202608140000} 里那五个标识也<b>只绑了 super_admin</b>。
 *
 * <p>三方一致、{@code 04 §B} 落单。本文件把那个一致性钉在测试里：
 * 任何一次"顺手给 org_admin 开一个写入口"的改动都会在这里红掉。
 *
 * <h2>为什么 §2.2 那条尤其不能开</h2>
 * <p>§2 导语给了理由：本组的创建<b>不写 {@code org_teacher} / {@code org_student} 档案</b>，
 * 机构侧经此路径建人会产生 PRD F1-3 规则 1 明令禁止的<b>孤儿数据</b>
 * （有节点无档案，在 {@code /org/teachers} 列表里查不到、无工号无科目）。
 * 把 org_admin 放进来，等于给机构侧开了一条造孤儿数据的路。
 */
class SystemUserWriteScopeIT extends SystemIntegrationTestBase {

    private static final String USERS = "/api/v1/system/users";

    // =====================================================================
    // 判据 13｜五个写接口对 org_admin 全部 403
    // =====================================================================

    @Test
    @DisplayName("判据 13-①｜§2.2 创建用户对 org_admin → 403")
    void createIsForbiddenForOrgAdmin() throws Exception {
        JsonNode response = client.postWithToken(USERS, orgAdminToken(),
                """
                {"username":"it_new_by_orgadmin","password":"Abcd1234","realName":"越权建号",
                 "userType":2,"parentNodeId":"%d","roleIds":["%d"]}
                """.formatted(AuthFixtures.ADMIN_NODE, AuthFixtures.ROLE_TEACHER));

        assertThat(code(response)).isEqualTo(403);
        assertThat(systemFixtures.userRowCount("it_new_by_orgadmin")).isZero();
    }

    @Test
    @DisplayName("判据 13-②｜§2.3 修改用户对 org_admin → 403")
    void updateIsForbiddenForOrgAdmin() throws Exception {
        JsonNode response = client.putWithToken(USERS + "/" + AuthFixtures.TEACHER_USER,
                orgAdminToken(),
                """
                {"realName":"越权改名","roleIds":["%d"]}
                """.formatted(AuthFixtures.ROLE_TEACHER));

        assertThat(code(response)).isEqualTo(403);
    }

    @Test
    @DisplayName("判据 13-③｜§2.4 删除用户对 org_admin → 403")
    void deleteIsForbiddenForOrgAdmin() throws Exception {
        JsonNode response = deleteWithToken(USERS + "/" + AuthFixtures.STUDENT2_USER, orgAdminToken());

        assertThat(code(response)).isEqualTo(403);
        assertThat(systemFixtures.userIdByUsername(AuthFixtures.STUDENT2_USERNAME)).isNotNull();
    }

    @Test
    @DisplayName("判据 13-④｜§2.5 重置密码对 org_admin → 403")
    void resetPasswordIsForbiddenForOrgAdmin() throws Exception {
        String before = systemFixtures.userPasswordHash(AuthFixtures.STUDENT2_USER);

        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/password/reset", orgAdminToken(),
                """
                {"newPassword":"Reset@2026"}
                """);

        assertThat(code(response)).isEqualTo(403);
        assertThat(systemFixtures.userPasswordHash(AuthFixtures.STUDENT2_USER)).isEqualTo(before);
    }

    // =====================================================================
    // 判据 8｜§2.6 启停用：org_admin 403、超管可调
    // =====================================================================

    @Test
    @DisplayName("判据 8 / 13-⑤｜§2.6 启停用对 org_admin → 403，sys_user.status 未被改动")
    void changeStatusIsForbiddenForOrgAdmin() throws Exception {
        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/status", orgAdminToken(),
                """
                {"status":1}
                """);

        assertThat(code(response)).isEqualTo(403);
        // 契约 §2.3：机构侧改不了 sys_user.status，正是不允许停用节点时顺手写它的原因 ——
        // 机构管理员没有任何接口能把它改回来，写进去就只能提工单改库
        assertThat(systemFixtures.userStatus(AuthFixtures.STUDENT2_USER)).isZero();
    }

    @Test
    @DisplayName("判据 8｜§2.6 启停用对 super_admin → 200，status 落库且在线会话被作废")
    void changeStatusIsAllowedForSuperAdmin() throws Exception {
        // 目标账号先登录一次，好验"停用后在线 Token 立即作废"
        String victimToken = client.loginForToken(
                AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD);
        assertThat(code(client.getWithToken("/api/v1/auth/me", victimToken))).isEqualTo(200);

        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/status", superAdminToken(),
                """
                {"status":1}
                """);

        assertThat(code(response)).isEqualTo(200);
        assertThat(systemFixtures.userStatus(AuthFixtures.STUDENT2_USER)).isEqualTo(1);
        // §2.6 数据权限栏：「停用后该用户在线 Token 立即作废」。
        // 少了这一步，被停用的人在接下来最长 2 小时里照常访问
        assertThat(code(client.getWithToken("/api/v1/auth/me", victimToken))).isEqualTo(401);
    }

    @Test
    @DisplayName("§2.6｜启用不作废会话（否则刚恢复的用户会被莫名踢下线）")
    void enablingDoesNotRevokeSessions() throws Exception {
        String token = client.loginForToken(AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD);

        JsonNode response = client.putWithToken(
                USERS + "/" + AuthFixtures.STUDENT2_USER + "/status", superAdminToken(),
                """
                {"status":0}
                """);

        assertThat(code(response)).isEqualTo(200);
        assertThat(code(client.getWithToken("/api/v1/auth/me", token))).isEqualTo(200);
    }

    // =====================================================================
    // §2.1 是本组唯一对 org_admin 开放的接口
    // =====================================================================

    @Test
    @DisplayName("§2.1｜org_admin 可查列表，且只看得到自身子树内的账号")
    void listIsAllowedForOrgAdminAndScopedToOwnSubtree() throws Exception {
        JsonNode response = client.getWithToken(USERS + "?pageNum=1&pageSize=100", orgAdminToken());

        assertThat(code(response)).isEqualTo(200);
        java.util.List<String> usernames = new java.util.ArrayList<>();
        response.path("data").path("list")
                .forEach(row -> usernames.add(row.path("username").asText()));

        // ADMIN 的子树 = 他自己 + S1（夹具树形见 AuthFixtures）
        assertThat(usernames).contains(AuthFixtures.ADMIN_USERNAME, AuthFixtures.STUDENT1_USERNAME);
        // 子树之外的一律看不到：TEACHER 与 S2 挂在 ROOT 的另一支下
        assertThat(usernames).doesNotContain(
                AuthFixtures.TEACHER_USERNAME, AuthFixtures.STUDENT2_USERNAME,
                AuthFixtures.ROOT_USERNAME, AuthFixtures.SUPER_ADMIN_USERNAME);
    }

    @Test
    @DisplayName("§2.1｜org_admin 传一个不在自身子树内的 nodeId → 10107（三分法，非 §2.1 写的 403）")
    void listRejectsOutOfScopeNodeId() throws Exception {
        JsonNode response = client.getWithToken(
                USERS + "?nodeId=" + AuthFixtures.TEACHER_NODE, orgAdminToken());

        // 契约 §2.4 三分法：「请求参数中显式指定的目标」越界 → 10107（HTTP 200），
        // 因为用户是主动选的、需要明确提示"请重新选择"。
        // §2.1 参数表写的是 403，但本字段与 §2.2 的 parentNodeId 是同一形状，
        // 而 §2.2 用的就是 10107 —— 同一分册内同形状参数给了两种码。
        // 已登记为 F-23，分册未改
        assertThat(code(response)).isEqualTo(10107);
        assertThat(response.path("msg").asText()).contains("管辖范围");

        // §2.2 的 parentNodeId 越界【无法经 HTTP 验到】：super_admin 不受子树约束，
        // 而 org_admin 在 @SaCheckPermission 那一层就 403 了，到不了 Service。
        // 两处走的是【同一个方法】SubtreeScopeHelper#assertTargetInSubtree，
        // 码由它一处决定 —— 这才是「同形状 → 同码」的保证，不是靠两条测试并列
    }

    @Test
    @DisplayName("§2.1｜super_admin 不传 tenantId → 查平台级账号；传了 → 查该租户")
    void superAdminListDefaultsToPlatformAccounts() throws Exception {
        String token = superAdminToken();

        JsonNode platform = client.getWithToken(USERS + "?pageNum=1&pageSize=100", token);
        java.util.List<String> platformUsers = new java.util.ArrayList<>();
        platform.path("data").path("list")
                .forEach(row -> platformUsers.add(row.path("username").asText()));
        assertThat(platformUsers).contains(AuthFixtures.SUPER_ADMIN_USERNAME);
        assertThat(platformUsers).doesNotContain(AuthFixtures.ADMIN_USERNAME);

        JsonNode tenantA = client.getWithToken(
                USERS + "?pageNum=1&pageSize=100&tenantId=" + AuthFixtures.TENANT_ID, token);
        java.util.List<String> tenantUsers = new java.util.ArrayList<>();
        tenantA.path("data").path("list")
                .forEach(row -> tenantUsers.add(row.path("username").asText()));
        assertThat(tenantUsers).contains(AuthFixtures.ADMIN_USERNAME, AuthFixtures.TEACHER_USERNAME);
        assertThat(tenantUsers).doesNotContain(AuthFixtures.SUPER_ADMIN_USERNAME);
    }

    // =====================================================================

    private String orgAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }

    private String superAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }
}
