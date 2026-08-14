package com.edumatrix.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.TenantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户生命周期：查询 / 修改 / 删除 / 续期 / 启停用（03-01 §5.1、§5.2、§5.4~§5.7）
 * 与 PRD F1-1 规则 3/4/7。
 */
class TenantLifecycleIT extends TenantIntegrationTestBase {

    // =====================================================================
    // §5.1 / §5.2 查询
    // =====================================================================

    @Test
    @DisplayName("§5.1｜分页列表返回 rootNodeId 与在读学生数；按名称模糊、状态、临期筛选")
    void pageReturnsTenantsWithStudentCount() throws Exception {
        long tenantId = dataLong(createTenant("列表机构", "list"), "id");

        JsonNode response = client.getWithToken(
                TENANTS + "?pageNum=1&pageSize=10&name=" + tenantName("列表机构"), superAdminToken());

        assertThat(code(response)).isEqualTo(200);
        JsonNode row = response.path("data").path("list").get(0);
        assertThat(row.path("id").asLong()).isEqualTo(tenantId);
        // 恒等（契约 §2.1）——分册示例里这两个是不同值，那是 F-24 登记的残留
        assertThat(row.path("rootNodeId").asLong()).isEqualTo(tenantId);
        // 新机构一个学生都没有：0 是确定的事实，不是 null
        assertThat(row.path("currentStudentCount").asLong()).isZero();
    }

    @Test
    @DisplayName("§5.2｜详情的 id / rootNodeId / adminNodeId 三者同值，adminUserId 是另一个")
    void detailExposesTheIdentityRelation() throws Exception {
        JsonNode created = createTenant("详情机构", "detail");
        long tenantId = dataLong(created, "id");
        long adminUserId = dataLong(created, "adminUserId");

        JsonNode response = client.getWithToken(TENANTS + "/" + tenantId, superAdminToken());

        assertThat(code(response)).isEqualTo(200);
        assertThat(dataLong(response, "rootNodeId")).isEqualTo(tenantId);
        // §5.2 的字段说明写「挂在 rootNodeId 之下」并给了第三个 id，那是已废弃的两层结构残留
        // （F-24）。契约 §2.1 与 §5.0：机构根节点【就是】最高管理员本人
        assertThat(dataLong(response, "adminNodeId")).isEqualTo(tenantId);
        assertThat(dataLong(response, "adminUserId")).isEqualTo(adminUserId);
        assertThat(dataText(response, "adminUsername")).isEqualTo(adminUsername("detail"));
        // 子树内节点总数：刚开通只有根节点自己
        assertThat(response.path("data").path("nodeCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("§5.2｜不存在的租户 → 404（三分法：路径上的资源，不暴露存在性）")
    void unknownTenantIsNotFound() throws Exception {
        assertThat(code(client.getWithToken(TENANTS + "/1234567890123456789", superAdminToken())))
                .isEqualTo(404);
    }

    // =====================================================================
    // §5.4 修改
    // =====================================================================

    @Test
    @DisplayName("§5.4｜改机构名同步刷机构根节点的 node_name")
    void renamingTenantAlsoRenamesRootNode() throws Exception {
        long tenantId = dataLong(createTenant("旧名机构", "rename"), "id");

        JsonNode response = client.putWithToken(TENANTS + "/" + tenantId, superAdminToken(), """
                {"name":"%s","contactName":"陈静","contactPhone":"13900139003",
                 "maxStudentCount":2500,"remark":"扩容至 2500"}
                """.formatted(tenantName("新名机构")));

        assertThat(code(response)).isEqualTo(200);
        assertThat(tenantFixtures.nodeName(tenantId)).isEqualTo(tenantName("新名机构"));
    }

    @Test
    @DisplayName("§5.4｜maxStudentCount 低于当前在读学生数 → 400")
    void maxStudentCountBelowCurrentIsRejected() throws Exception {
        // AuthFixtures 那个租户有 2 名在读学生，拿它当样本 —— 新开通的机构一个学生都没有，
        // 验不到这条规则
        JsonNode response = client.putWithToken(
                TENANTS + "/" + AuthFixtures.TENANT_ID, superAdminToken(), """
                        {"name":"IT 测试机构","contactName":"联系人","contactPhone":"13900139001",
                         "maxStudentCount":1}
                        """);

        assertThat(code(response)).isEqualTo(400);
        assertThat(response.path("msg").asText()).contains("在读学生数");
    }

    // =====================================================================
    // §5.6 续期（PRD F1-1 规则 3）
    // =====================================================================

    @Test
    @DisplayName("§5.6｜续期后到期机构的账号立即登得进来（数据保留不删除，续期后恢复）")
    void renewRestoresLoginOfAnExpiredTenant() throws Exception {
        // 判据前提：到期机构的管理员现在登录被 10007 拒（模块 02 已验，这里作为对照）
        assertThat(code(client.login(AuthFixtures.EXPIRED_USERNAME, AuthFixtures.PASSWORD)))
                .isEqualTo(10007);

        JsonNode response = client.putWithToken(
                TENANTS + "/" + AuthFixtures.EXPIRED_TENANT_ID + "/renew", superAdminToken(), """
                        {"expireTime":"2099-08-31 23:59:59"}
                        """);

        assertThat(code(response)).isEqualTo(200);
        // §5.6 响应示例逐字：msg 是「续期成功」
        assertThat(response.path("msg").asText()).isEqualTo("续期成功");
        assertThat(code(client.login(AuthFixtures.EXPIRED_USERNAME, AuthFixtures.PASSWORD)))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("§5.6｜新到期时间早于当前 → 400")
    void renewIntoThePastIsRejected() throws Exception {
        long tenantId = dataLong(createTenant("续期机构", "renew"), "id");

        JsonNode response = client.putWithToken(TENANTS + "/" + tenantId + "/renew",
                superAdminToken(), """
                        {"expireTime":"2020-01-01 00:00:00"}
                        """);

        assertThat(code(response)).isEqualTo(400);
    }

    // =====================================================================
    // §5.7 启停用（PRD F1-1 规则 4）
    // =====================================================================

    @Test
    @DisplayName("§5.7｜停用后全员在线 Token 作废、登录返回 10007；重新启用即时恢复")
    void disablingTenantRevokesSessionsAndBlocksLogin() throws Exception {
        String adminToken = client.loginForToken(AuthFixtures.ROOT_USERNAME, AuthFixtures.PASSWORD);
        assertThat(code(client.getWithToken("/api/v1/auth/me", adminToken))).isEqualTo(200);

        assertThat(code(changeStatus(AuthFixtures.TENANT_ID, 1))).isEqualTo(200);

        // ① 在线 Token 立即作废（§5.7 原文）
        assertThat(code(client.getWithToken("/api/v1/auth/me", adminToken))).isEqualTo(401);
        // ② 登录返回 10007（找平台），【不是】10017（找机构管理员）—— 两个码不得合并
        assertThat(code(client.login(AuthFixtures.ROOT_USERNAME, AuthFixtures.PASSWORD)))
                .isEqualTo(10007);
        // ③ 停用的是租户行，机构根节点【没有】被停用（PRD F1-1 规则 7）
        assertThat(fixtures.nodeStatus(AuthFixtures.ROOT_NODE)).isZero();
        // ④ 也没有顺手动账号级封禁（那是仅超管可写的 sys_user.status，契约 §2.3）
        assertThat(fixtures.userStatus(AuthFixtures.ROOT_USER)).isZero();

        // 重新启用即时恢复
        assertThat(code(changeStatus(AuthFixtures.TENANT_ID, 0))).isEqualTo(200);
        assertThat(code(client.login(AuthFixtures.ROOT_USERNAME, AuthFixtures.PASSWORD)))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("§5.7｜停用踢的是【全员】：同租户三个账号的 Token 全失效，别的租户不受影响")
    void disablingTenantRevokesEveryMemberButOnlyThatTenant() throws Exception {
        // 三个账号、三种 user_type，都在租户 A 下。【一个账号验不到循环踢线】——
        // 只踢第一个（或只踢调用者自己）的实现同样能让单账号的用例通过
        String subAdminToken = client.loginForToken(
                AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String teacherToken = client.loginForToken(
                AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD);
        String studentToken = client.loginForToken(
                AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD);

        // 租户 B 的对照组：先续期让它能登录，拿一个活着的 Token
        assertThat(code(client.putWithToken(
                TENANTS + "/" + AuthFixtures.EXPIRED_TENANT_ID + "/renew", superAdminToken(), """
                        {"expireTime":"2099-08-31 23:59:59"}
                        """))).isEqualTo(200);
        String otherTenantToken = client.loginForToken(
                AuthFixtures.EXPIRED_USERNAME, AuthFixtures.PASSWORD);

        for (String token : new String[]{subAdminToken, teacherToken, studentToken, otherTenantToken}) {
            assertThat(code(client.getWithToken("/api/v1/auth/me", token))).isEqualTo(200);
        }

        assertThat(code(changeStatus(AuthFixtures.TENANT_ID, 1))).isEqualTo(200);

        // 租户 A 的三个账号【全部】失效 —— 循环踢线的正确性只在"多个"上验得到
        assertThat(code(client.getWithToken("/api/v1/auth/me", subAdminToken))).isEqualTo(401);
        assertThat(code(client.getWithToken("/api/v1/auth/me", teacherToken))).isEqualTo(401);
        assertThat(code(client.getWithToken("/api/v1/auth/me", studentToken))).isEqualTo(401);
        // 租户 B 的账号【毫发无损】：踢线的名单是按 tenant_id 显式圈定的。
        // 少写那个条件时，超管会话下插件整体放行，这里就是一次【全平台】踢线
        assertThat(code(client.getWithToken("/api/v1/auth/me", otherTenantToken))).isEqualTo(200);
    }

    // =====================================================================
    // §5.5 删除
    // =====================================================================

    @Test
    @DisplayName("§5.5｜未停用直接删 → 400（防误删）")
    void deletingActiveTenantIsRejected() throws Exception {
        long tenantId = dataLong(createTenant("在用机构", "active"), "id");

        JsonNode response = deleteWithToken(TENANTS + "/" + tenantId, superAdminToken());

        assertThat(code(response)).isEqualTo(400);
        assertThat(tenantFixtures.tenantDeletedAt(tenantId)).isZero();
    }

    @Test
    @DisplayName("§5.5｜停用后删除：租户行与整棵子树一并逻辑删除，root_node_id 指向不变")
    void deletingDisabledTenantCascadesToTheSubtree() throws Exception {
        long tenantId = dataLong(createTenant("待删机构", "delete"), "id");
        assertThat(code(changeStatus(tenantId, 1))).isEqualTo(200);

        JsonNode response = deleteWithToken(TENANTS + "/" + tenantId, superAdminToken());

        assertThat(code(response)).isEqualTo(200);
        // 逻辑删除写毫秒时间戳而非 0/1（契约 §2.2）
        assertThat(tenantFixtures.tenantDeletedAt(tenantId)).isNotZero();
        // 机构根节点及其整棵子树一并逻辑删除（§5.5：唯一允许级联的场景）
        assertThat(tenantFixtures.nodeDeletedAt(tenantId)).isNotZero();
        // root_node_id 指向保持不变，以便恢复
        assertThat(tenantFixtures.tenantRootNodeId(tenantId)).isEqualTo(tenantId);
        // 详情不再查得到（逻辑删除后按 404，不暴露存在性）
        assertThat(code(client.getWithToken(TENANTS + "/" + tenantId, superAdminToken())))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("§5.5｜删除后该租户账号立即登录被拒（10007：租户行查不到即拒登）")
    void deletedTenantMembersCannotLogIn() throws Exception {
        JsonNode created = createTenant("禁登机构", "banned");
        long tenantId = dataLong(created, "id");
        String initialPassword = dataText(created, "initialPassword");
        String username = dataText(created, "adminUsername");
        assertThat(code(client.login(username, initialPassword))).isEqualTo(200);

        assertThat(code(changeStatus(tenantId, 1))).isEqualTo(200);
        assertThat(code(deleteWithToken(TENANTS + "/" + tenantId, superAdminToken()))).isEqualTo(200);

        assertThat(code(client.login(username, initialPassword))).isEqualTo(10007);
        // 账号行本身【没有】被删（用户名不释放）—— §5.5 只说级联组织树，
        // 而释放用户名会让"可恢复"变成一句空话
        assertThat(tenantFixtures.userRowCount(username)).isEqualTo(1);
    }

    // =====================================================================
    // §5 导语：其余角色一律 403
    // =====================================================================

    @Test
    @DisplayName("§5 导语｜org_admin 调七个接口全部 403")
    void orgAdminIsForbiddenOnEveryTenantEndpoint() throws Exception {
        String token = orgAdminToken();
        long tenantId = AuthFixtures.TENANT_ID;

        assertThat(code(client.getWithToken(TENANTS, token))).isEqualTo(403);
        assertThat(code(client.getWithToken(TENANTS + "/" + tenantId, token))).isEqualTo(403);
        assertThat(code(client.putWithToken(TENANTS + "/" + tenantId, token, """
                {"name":"改名","contactName":"x","contactPhone":"13900139001","maxStudentCount":10}
                """))).isEqualTo(403);
        assertThat(code(deleteWithToken(TENANTS + "/" + tenantId, token))).isEqualTo(403);
        assertThat(code(client.putWithToken(TENANTS + "/" + tenantId + "/renew", token, """
                {"expireTime":"2099-01-01 00:00:00"}
                """))).isEqualTo(403);
        assertThat(code(client.putWithToken(TENANTS + "/" + tenantId + "/status", token, """
                {"status":1}
                """))).isEqualTo(403);
        // 一次都没生效
        assertThat(tenantFixtures.tenantStatus(tenantId)).isZero();
    }

    // =====================================================================

    private JsonNode changeStatus(long tenantId, int status) throws Exception {
        return client.putWithToken(TENANTS + "/" + tenantId + "/status", superAdminToken(), """
                {"status":%d}
                """.formatted(status));
    }
}
