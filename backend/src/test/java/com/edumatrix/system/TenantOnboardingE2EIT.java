package com.edumatrix.system;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.system.support.TenantIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判据 4：<b>开通 → 用初始密码登录 → 强制改密 → {@code /auth/me} 拿到非空 roles/perms</b>。
 *
 * <p>这条把模块 02（认证）、03（角色菜单链路）、04（开通）串成一条真实链路，
 * 是<b>"系统真的能用了"的第一个证据</b>：在此之前，库里一个机构都没有、
 * 一个能建人的管理员都没有；跑通它意味着平台侧可以开出一个机构，
 * 机构管理员可以登进来并看到自己的菜单权限。
 *
 * <p>全程<b>不碰数据库造数</b>（除了断言取数）：每一步都走真实 HTTP 接口，
 * 验证码也走真流程（{@code AuthTestClient} 从 Redis 读回原文）。
 * 任何一环退化成"测试专用后门"，这条链路就不再证明它想证明的东西。
 */
class TenantOnboardingE2EIT extends TenantIntegrationTestBase {

    private static final String ME = "/api/v1/auth/me";
    private static final String CHANGE_PASSWORD = "/api/v1/auth/password";

    private static final String NEW_PASSWORD = "Boya@20260814";

    @Test
    @DisplayName("判据 4｜开通机构 → 初始密码登录（needChangePassword=true）→ 改密 → /auth/me 的 roles/perms 非空")
    void newlyProvisionedAdminCanLogInAndLoadPermissions() throws Exception {
        // ---------- ① 平台超管开通机构 ----------
        JsonNode created = createTenant("端到端机构", "e2e");
        assertThat(code(created)).isEqualTo(200);

        long tenantId = dataLong(created, "id");
        long adminUserId = dataLong(created, "adminUserId");
        String username = dataText(created, "adminUsername");
        String initialPassword = dataText(created, "initialPassword");

        // ---------- ② 机构管理员用【响应里返回的那一次】初始密码登录 ----------
        JsonNode login = client.login(username, initialPassword);
        assertThat(code(login)).isEqualTo(200);
        // PRD F1-1 规则 6 / §5.3：首次登录 needChangePassword = true，前端强制跳改密页
        assertThat(login.path("data").path("needChangePassword").asBoolean()).isTrue();
        String firstToken = login.path("data").path("accessToken").asText();

        // ---------- ③ 强制改密（03-01 §1.6） ----------
        JsonNode changed = client.putWithToken(CHANGE_PASSWORD, firstToken, """
                {"oldPassword":"%s","newPassword":"%s"}
                """.formatted(initialPassword, NEW_PASSWORD));
        assertThat(code(changed)).isEqualTo(200);
        // 改密后 pwd_reset_flag 归零，下次登录不再强制
        assertThat(tenantFixtures.userPwdResetFlag(adminUserId)).isZero();

        // ---------- ④ 用新密码重新登录，调 /auth/me ----------
        JsonNode reLogin = client.login(username, NEW_PASSWORD);
        assertThat(code(reLogin)).isEqualTo(200);
        assertThat(reLogin.path("data").path("needChangePassword").asBoolean()).isFalse();
        String token = reLogin.path("data").path("accessToken").asText();

        JsonNode me = client.getWithToken(ME, token);
        assertThat(code(me)).isEqualTo(200);

        // roles 非空：sys_user_role → sys_role 这一跳走的是契约 §2.9 的平台级行放行
        // （预置角色 tenant_id = 0）。放行没生效的话这里就是空数组
        List<String> roleKeys = new ArrayList<>();
        me.path("data").path("roles").forEach(role -> roleKeys.add(role.path("roleKey").asText()));
        assertThat(roleKeys).containsExactly("org_admin");

        // perms 非空：再往下 sys_role → sys_role_menu → sys_menu。
        // 空数组意味着这个新机构的管理员【零权限】——前端所有按钮隐藏、
        // 后端每个 @SaCheckPermission 一律 403，即契约 §2.9 开篇说的"开箱即不可用"
        List<String> perms = textList(me.path("data").path("perms"));
        assertThat(perms).isNotEmpty();
        assertThat(perms).contains("system:tenantConfig:list");
        // 他是【机构】管理员，不是平台超管：租户管理那组标识他一个都不该有
        assertThat(perms).doesNotContain("system:tenant:add");

        // 会话上下文落在新租户与新节点上（数据权限的起点，契约 §2.4）。
        // 这三个字段在这里【必须全等】—— 它是判据 3 的恒等关系经由 auth 链路的又一次确认
        assertThat(me.path("data").path("nodeId").asLong()).isEqualTo(tenantId);
        assertThat(me.path("data").path("tenant").path("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(me.path("data").path("tenant").path("rootNodeId").asLong()).isEqualTo(tenantId);
        // 面包屑自机构根起、不含平台根哨兵（契约 §2.9），根节点即机构名，故只有一段
        assertThat(me.path("data").path("nodePath").asText()).isEqualTo(tenantName("端到端机构"));

        // ---------- ⑤ 他能真的用起来：调一个属于自己的接口 ----------
        JsonNode configs = client.getWithToken(TENANT_CONFIGS, token);
        assertThat(code(configs)).isEqualTo(200);
        assertThat(configs.path("data")).hasSize(2);
    }

    private static List<String> textList(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(node -> list.add(node.asText()));
        return list;
    }
}
