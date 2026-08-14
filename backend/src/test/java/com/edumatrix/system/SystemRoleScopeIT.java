package com.edumatrix.system;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.SystemFixtures;
import com.edumatrix.system.support.SystemIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色的可见范围与 RBAC 一致性（判据 1 / 2 / 3）。
 *
 * <h2>判据 3 验的是「放行没有放过头」</h2>
 * <p>契约 §2.9 否决了 {@code ignoreTable} 整表忽略方案，理由逐字是：
 * 「忽略 {@code sys_role} 意味着<b>租户 A 能列出、甚至改删租户 B 自建的『教务主任』角色</b>」。
 * 这个用例就是那句话的验收 —— 放行必须<b>只放 {@code tenant_id = 0} 这一档</b>，
 * 不能顺带把别的租户的私有行也放出来。
 */
class SystemRoleScopeIT extends SystemIntegrationTestBase {

    // =====================================================================
    // 判据 1（本模块承担的那一半）｜层级不同、角色与菜单权限完全相同
    // =====================================================================

    /**
     * PRD F1-3 那条验收标准的前半句：「丙的 {@code role_key} 与乙<b>相同</b>、菜单权限一致」。
     *
     * <p><b>后半句（丙的子树严格包含于乙的子树）不在本模块</b>：按 03-01 §2 导语，
     * 建人走的是 {@code /org/**}（三写一事务，同时落档案表），本模块的 §2.2 是超管专用
     * 且不写档案表 —— 用它建人正是 PRD F1-3 规则 1 禁止的孤儿数据。
     * 那条判据整体归模块 07（{@code 04 §B} 模块 07 的完成判据第一条就是「PRD F1-3 全部 5 条」）。
     *
     * <p>本模块能验、也应该验的是契约 §3 / PRD F1-3 规则 7 的那一条：
     * <b>任何层级的管理员 {@code role_key} 一律 {@code org_admin}，菜单/按钮权限完全一致，
     * 层级差异只体现在数据范围</b>。夹具里 ROOT（机构最高管理员）与 ADMIN（下级管理员）
     * 正是两个层级，不需要建人就能验。
     */
    @Test
    @DisplayName("判据 1｜两个层级的 org_admin：roleKey 相同、perms 逐条相同")
    void adminsAtDifferentLevelsShareIdenticalPermissions() throws Exception {
        JsonNode root = me(AuthFixtures.ROOT_USERNAME);
        JsonNode sub = me(AuthFixtures.ADMIN_USERNAME);

        assertThat(roleKeys(root)).containsExactly("org_admin");
        assertThat(roleKeys(sub)).containsExactly("org_admin");

        List<String> rootPerms = perms(root);
        List<String> subPerms = perms(sub);
        assertThat(rootPerms).isNotEmpty();
        // 「完全一致」用 containsExactlyElementsOf 而不是 hasSameSizeAs：
        // 数量相同而内容不同，正是"层级影响了权限"这个缺陷最可能的表现
        assertThat(subPerms).containsExactlyElementsOf(rootPerms);

        // 层级差异只体现在数据范围 —— 两人的 nodeId 不同
        assertThat(root.path("data").path("nodeId").asText())
                .isNotEqualTo(sub.path("data").path("nodeId").asText());
    }

    // =====================================================================
    // 判据 2 前半｜/auth/me 的 roles / perms 非空（读侧放行仍然生效）
    // =====================================================================

    @Test
    @DisplayName("判据 2｜租户 A 的机构管理员调 /auth/me，roles 与 perms 均非空")
    void orgAdminSeesNonEmptyRolesAndPerms() throws Exception {
        JsonNode me = me(AuthFixtures.ADMIN_USERNAME);

        // 空数组有两种成因：F-1 定案②的设计意图（学生不绑菜单），
        // 或契约 §2.9 那个「接口 200、字段齐全、数组为空」的放行失效故障。
        // 对 org_admin 而言只可能是后者，所以两者都断言非空
        assertThat(me.path("data").path("roles")).isNotEmpty();
        assertThat(me.path("data").path("perms")).isNotEmpty();
    }

    // =====================================================================
    // 判据 3｜租户 A 列不出租户 B 的自建角色
    // =====================================================================

    @Test
    @DisplayName("判据 3｜租户 A 的角色列表：含平台预置角色，不含租户 B 的自建角色")
    void tenantACannotListTenantBCustomRole() throws Exception {
        String token = client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);

        JsonNode response = client.getWithToken(
                "/api/v1/system/roles?pageNum=1&pageSize=100", token);

        assertThat(code(response)).isEqualTo(200);
        List<String> keys = new ArrayList<>();
        response.path("data").path("list").forEach(row -> keys.add(row.path("roleKey").asText()));

        // 放行生效：四个 tenant_id = 0 的内置角色读得到（否则全员零权限）
        assertThat(keys).contains("org_admin", "teacher", "student", "super_admin");
        // 本租户自建角色读得到
        assertThat(keys).contains(SystemFixtures.ROLE_KEY_A);
        // 【关键】别的租户的私有行读不到 —— 这正是契约 §2.9 否决 ignoreTable 的那句话
        assertThat(keys).doesNotContain(SystemFixtures.ROLE_KEY_B);
    }

    @Test
    @DisplayName("判据 3 补｜租户 A 按 id 直查租户 B 的自建角色 → 404，不暴露存在性")
    void tenantACannotReadTenantBRoleById() throws Exception {
        String token = client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);

        JsonNode response = client.getWithToken(
                "/api/v1/system/roles/" + SystemFixtures.ROLE_TENANT_B_CUSTOM, token);

        // 契约 §2.4 三分法：路径上的资源不在我的范围内 → 404（不暴露存在性），不是 403
        assertThat(code(response)).isEqualTo(404);
    }

    @Test
    @DisplayName("判据 3 补｜租户 A 也改不动租户 B 的自建角色 → 404")
    void tenantACannotWriteTenantBRole() throws Exception {
        String token = client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String before = systemFixtures.roleName(SystemFixtures.ROLE_TENANT_B_CUSTOM);

        JsonNode response = client.putWithToken(
                "/api/v1/system/roles/" + SystemFixtures.ROLE_TENANT_B_CUSTOM, token,
                """
                {"roleName":"被隔壁租户改了","status":0}
                """);

        assertThat(code(response)).isEqualTo(404);
        assertThat(systemFixtures.roleName(SystemFixtures.ROLE_TENANT_B_CUSTOM)).isEqualTo(before);
    }

    @Test
    @DisplayName("§3.2｜角色详情回显 menuIds（预置角色的绑定读得到，靠的正是 §2.9 放行）")
    void presetRoleDetailReturnsMenuIds() throws Exception {
        String token = client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);

        JsonNode response = client.getWithToken(
                "/api/v1/system/roles/" + SystemFixtures.ROLE_PRESET_TEACHER, token);

        assertThat(code(response)).isEqualTo(200);
        assertThat(response.path("data").path("preset").asBoolean()).isTrue();
        // 少了 sys_role_menu 的放行，这里会是空数组而接口仍返回 200 ——
        // 前端的"分配菜单"弹窗一个勾都不回显
        assertThat(response.path("data").path("menuIds")).isNotEmpty();
    }

    // =====================================================================

    private JsonNode me(String username) throws Exception {
        String token = client.loginForToken(username, AuthFixtures.PASSWORD);
        return client.getWithToken("/api/v1/auth/me", token);
    }

    private static List<String> roleKeys(JsonNode me) {
        List<String> keys = new ArrayList<>();
        me.path("data").path("roles").forEach(r -> keys.add(r.path("roleKey").asText()));
        return keys;
    }

    private static List<String> perms(JsonNode me) {
        List<String> list = new ArrayList<>();
        me.path("data").path("perms").forEach(p -> list.add(p.asText()));
        return list;
    }
}
