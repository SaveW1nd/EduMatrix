package com.edumatrix.system;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.TenantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开通机构的验收（03-01 §5.3；PRD F1-1 验收标准第 2 条 = 判据 1；id 恒等 = 判据 3）。
 */
class TenantProvisionIT extends TenantIntegrationTestBase {

    // =====================================================================
    // 判据 3｜id 恒等 —— 陷阱①的回归测试
    // =====================================================================

    @Test
    @DisplayName("判据 3｜sys_tenant.id == root_node_id == org_node.id == sys_user.node_id 四者同值")
    void tenantIdRootNodeIdAndAdminNodeIdAreTheSameValue() throws Exception {
        JsonNode response = createTenant("恒等机构", "identity");

        assertThat(code(response)).isEqualTo(200);
        long tenantId = dataLong(response, "id");
        long rootNodeId = dataLong(response, "rootNodeId");
        long adminNodeId = dataLong(response, "adminNodeId");
        long adminUserId = dataLong(response, "adminUserId");

        // ① 响应里三个字段是同一个值（§5.3 字段说明：rootNodeId「其值等于租户 id，也等于 adminNodeId」）
        assertThat(rootNodeId).isEqualTo(tenantId);
        assertThat(adminNodeId).isEqualTo(tenantId);
        // ② 账号 id 是【另一个】值 —— 示例里 ...084 与 ...085。
        //    若这一条也相等，说明某处把两个 id 混成了一个
        assertThat(adminUserId).isNotEqualTo(tenantId);

        // ③ 库里四个值同样恒等。这一条【不报错也会错】：id 不等时树能建出来、账号能登录，
        //    只是 sys_tenant.root_node_id 指向一个 tenant_id 与之不同的节点，
        //    此后所有按 tenant_id 过滤的查询都对不上
        assertThat(tenantFixtures.tenantRootNodeId(tenantId)).isEqualTo(tenantId);
        assertThat(tenantFixtures.nodeRowCount(tenantId)).isEqualTo(1);
        assertThat(tenantFixtures.userNodeId(adminUserId)).isEqualTo(tenantId);

        // ④ 陷阱③：三张带 tenant_id 的表都必须写【新租户】，不是超管会话的 0
        assertThat(tenantFixtures.nodeTenantId(tenantId)).isEqualTo(tenantId);
        assertThat(tenantFixtures.userTenantId(adminUserId)).isEqualTo(tenantId);
        assertThat(tenantFixtures.userRoleTenantId(adminUserId)).isEqualTo(tenantId);
        assertThat(tenantFixtures.changeLogTenantId(tenantId)).isEqualTo(tenantId);
    }

    // =====================================================================
    // 判据 1｜PRD F1-1 验收标准第 2 条
    // =====================================================================

    @Test
    @DisplayName("判据 1｜仅存在一个 node_type=1/parent_id=0 的机构根节点，与管理员互为反向引用")
    void exactlyOneRootNodeAndMutualBackReference() throws Exception {
        JsonNode response = createTenant("唯一根机构", "onlyroot");

        assertThat(code(response)).isEqualTo(200);
        long tenantId = dataLong(response, "id");
        long adminUserId = dataLong(response, "adminUserId");

        // 「存在且仅存在一个」——两层结构（机构节点 + 下挂管理员）会在这里数出 2
        assertThat(tenantFixtures.rootNodeCount(tenantId)).isEqualTo(1);
        assertThat(tenantFixtures.nodeType(tenantId)).isEqualTo(1);
        assertThat(tenantFixtures.nodeParentId(tenantId)).isZero();
        // ancestors = "0"：平台根哨兵 + 本节点不含自身（§5 导语逐字）
        assertThat(tenantFixtures.nodeAncestors(tenantId)).isEqualTo("0");

        // sys_tenant.root_node_id 指向它，且与初始管理员互为反向引用
        assertThat(tenantFixtures.tenantRootNodeId(tenantId)).isEqualTo(tenantId);
        assertThat(tenantFixtures.nodeRefUserId(tenantId)).isEqualTo(adminUserId);
        assertThat(tenantFixtures.userNodeId(adminUserId)).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("§5.3 步骤②｜建档轨迹 ×1（change_type=1、from_parent_id 为 NULL）、平台根 child_count +1")
    void provisioningWritesChangeLogAndMaintainsChildCount() throws Exception {
        Integer childCountBefore = tenantFixtures.platformRootChildCount();

        JsonNode response = createTenant("轨迹机构", "changelog");
        long tenantId = dataLong(response, "id");

        assertThat(tenantFixtures.createChangeLogCount(tenantId)).isEqualTo(1);
        // DDL 注释：change_type=1 建档时 from_parent_id 为 NULL
        assertThat(tenantFixtures.changeLogFromParentId(tenantId)).isNull();
        assertThat(tenantFixtures.platformRootChildCount()).isEqualTo(childCountBefore + 1);
    }

    @Test
    @DisplayName("§5.3｜账号 user_type=1、pwd_reset_flag=1、绑定预置 org_admin 角色")
    void adminAccountIsOrgAdminWithForcedPasswordChange() throws Exception {
        JsonNode response = createTenant("账号机构", "account");
        long adminUserId = dataLong(response, "adminUserId");

        // PRD F1-1 规则 6：初始密码强制首次登录修改
        assertThat(tenantFixtures.userPwdResetFlag(adminUserId)).isEqualTo(1);
        // 绑的是【平台预置】的 org_admin（tenant_id = 0 的那一行），不是租户自建角色
        assertThat(tenantFixtures.userRoleCount(adminUserId, AuthFixtures.ROLE_ORG_ADMIN)).isEqualTo(1);
        // node_type 与 user_type 恒等（契约 §5）
        assertThat(tenantFixtures.nodeType(dataLong(response, "rootNodeId"))).isEqualTo(1);
    }

    @Test
    @DisplayName("§5.3｜initialPassword 12 位含大小写字母与数字，仅本次返回，且不落库")
    void initialPasswordIsGeneratedOnceAndNeverStored() throws Exception {
        JsonNode response = createTenant("口令机构", "password");

        String initialPassword = dataText(response, "initialPassword");
        // §5.3 逐字：12 位，含大小写字母与数字（响应示例 aB3kQ9mZ7x2P 亦为 12 位纯字母数字）
        assertThat(initialPassword).hasSize(12);
        assertThat(initialPassword).matches("^[A-Za-z0-9]+$")
                .matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*");

        // 明文不落库：详情接口再查一次，任何字段都不该带出它
        long tenantId = dataLong(response, "id");
        JsonNode detail = client.getWithToken(TENANTS + "/" + tenantId, superAdminToken());
        assertThat(detail.toString()).doesNotContain(initialPassword);
    }

    @Test
    @DisplayName("§5.3｜机构根节点的 node_name 取【机构名称】，adminNodePath 只有一段")
    void rootNodeIsNamedAfterTheOrganization() throws Exception {
        JsonNode response = createTenant("博雅外国语学校", "boya");
        long tenantId = dataLong(response, "id");

        // 四比一：§5.3 步骤②表、§5.3 响应示例的 adminNodePath、§5.0 树形图、§5.4 同步改名
        // 都说是机构名；只有 §5.3 参数表 adminRealName 的括注说是管理员姓名（已登记 F-24）
        assertThat(tenantFixtures.nodeName(tenantId)).isEqualTo(tenantName("博雅外国语学校"));
        assertThat(dataText(response, "adminNodePath")).isEqualTo(tenantName("博雅外国语学校"));
        assertThat(dataText(response, "adminRealName")).isEqualTo("陈静");
    }

    // =====================================================================
    // 错误码
    // =====================================================================

    @Test
    @DisplayName("§5.3｜adminUsername 与现有账号冲突 → 10001")
    void duplicateAdminUsernameIsRejected() throws Exception {
        assertThat(code(createTenant("首个机构", "dup"))).isEqualTo(200);

        JsonNode again = createTenant("第二个机构", "dup");

        assertThat(code(again)).isEqualTo(10001);
    }

    @Test
    @DisplayName("§5.3｜机构名称重复 → 400（uk_name），不是业务码")
    void duplicateTenantNameIsRejectedWith400() throws Exception {
        assertThat(code(createTenant("重名机构", "namea"))).isEqualTo(200);

        JsonNode again = createTenant("重名机构", "nameb");

        assertThat(code(again)).isEqualTo(400);
    }

    @Test
    @DisplayName("§5.3｜expireTime 早于当前时间 → 400")
    void pastExpireTimeIsRejected() throws Exception {
        JsonNode response = createTenant("过期机构", "expired", "2020-01-01 00:00:00", 100);

        assertThat(code(response)).isEqualTo(400);
        assertThat(tenantFixtures.tenantRowCount(tenantName("过期机构"))).isZero();
    }

    @Test
    @DisplayName("§5 导语｜org_admin 调开通接口 → 403（权限标识只绑 super_admin）")
    void orgAdminCannotProvisionTenant() throws Exception {
        JsonNode response = client.postWithToken(TENANTS, orgAdminToken(), """
                {"name":"%s","contactName":"陈静","contactPhone":"13900139002",
                 "expireTime":"2099-12-31 23:59:59","maxStudentCount":100,
                 "adminUsername":"%s","adminRealName":"陈静"}
                """.formatted(tenantName("越权机构"), adminUsername("forbidden")));

        assertThat(code(response)).isEqualTo(403);
        assertThat(tenantFixtures.tenantRowCount(tenantName("越权机构"))).isZero();
    }
}
