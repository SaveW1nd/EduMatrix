package com.edumatrix.system;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.system.support.SystemFixtures;
import com.edumatrix.system.support.SystemIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>写侧收紧</b>的验收（判据 2 / 4 / 5 / 6 / 7）—— 本模块最要紧的一组用例。
 *
 * <h2>模块 02 只验了读侧，写侧一个测试都没有</h2>
 * <p>契约 §2.9 把 {@code sys_role} / {@code sys_role_menu} 的租户过滤放宽为
 * {@code (tenant_id = ? OR tenant_id = 0)}，模块 02 的 {@code AuthMeIT} 验的是那一半
 * （org_admin 取得到 94 个 perms）。<b>但放行的只是读</b> ——
 * 写侧必须反向收紧，否则一个租户的管理员改一次预置角色，全平台跟着变。
 * 那一半就是这个文件。
 *
 * <h2>为什么用 {@code ADMIN_USERNAME} 而不是 {@code ROOT_USERNAME} 当 org_admin</h2>
 * <p>{@code ROOT_USER} 的 {@code pwd_reset_flag = 1}（模块 02 用它验 PRD F1-1 规则 6），
 * 而 {@code AuthSessionGuard} 的强制改密门禁只放行 {@code /auth/password}、
 * {@code /auth/logout}、{@code /auth/me} 三条 —— 拿它调业务接口会拿到 403，
 * 那个 403 来自门禁而不是来自本模块的收紧，<b>会把用例验成一个假阳性</b>。
 */
class SystemRoleWriteGuardIT extends SystemIntegrationTestBase {

    private static final String ROLES = "/api/v1/system/roles/";

    // =====================================================================
    // 判据 4｜收紧三件套：org_admin 对预置角色的改名称 / 改状态 / 改菜单绑定，分别被拒（400）
    // =====================================================================

    @Test
    @DisplayName("判据 4-①｜org_admin 改预置 teacher 角色的名称 → 400，库里一个字未变")
    void orgAdminCannotRenamePresetRole() throws Exception {
        String token = orgAdminToken();
        String before = systemFixtures.roleName(SystemFixtures.ROLE_PRESET_TEACHER);

        JsonNode response = client.putWithToken(ROLES + SystemFixtures.ROLE_PRESET_TEACHER, token,
                """
                {"roleName":"被租户改过的教师","status":0,"remark":"越权尝试"}
                """);

        assertThat(code(response)).isEqualTo(400);
        // 这一条比 code 更重要：预置角色是全平台共用的同一行，
        // 「返回了 400 但其实已经写进去了」才是真正的事故形态
        assertThat(systemFixtures.roleName(SystemFixtures.ROLE_PRESET_TEACHER)).isEqualTo(before);
    }

    @Test
    @DisplayName("判据 4-②｜org_admin 停用预置 teacher 角色 → 400（否则全平台教师失权）")
    void orgAdminCannotDisablePresetRole() throws Exception {
        String token = orgAdminToken();

        JsonNode response = client.putWithToken(ROLES + SystemFixtures.ROLE_PRESET_TEACHER, token,
                """
                {"roleName":"教师","status":1}
                """);

        assertThat(code(response)).isEqualTo(400);
        assertThat(systemFixtures.roleStatus(SystemFixtures.ROLE_PRESET_TEACHER)).isZero();
    }

    @Test
    @DisplayName("判据 2 / 4-③｜org_admin 改预置 teacher 角色的菜单绑定 → 400，绑定未被改写")
    void orgAdminCannotReassignPresetRoleMenus() throws Exception {
        String token = orgAdminToken();
        List<Long> before = systemFixtures.roleMenuIds(SystemFixtures.ROLE_PRESET_TEACHER);
        assertThat(before).isNotEmpty();

        JsonNode response = client.putWithToken(
                ROLES + SystemFixtures.ROLE_PRESET_TEACHER + "/menus", token,
                """
                {"menuIds":[]}
                """);

        assertThat(code(response)).isEqualTo(400);
        // §3.6 原文：预置角色的菜单绑定被改写 = 一次性改变全平台所有租户同类用户的功能权限。
        // 传的是 []（清空），若被放行，全平台教师会在下一个请求里失去全部按钮
        assertThat(systemFixtures.roleMenuIds(SystemFixtures.ROLE_PRESET_TEACHER))
                .containsExactlyElementsOf(before);
    }

    // =====================================================================
    // 判据 5｜super_admin 改得动
    // =====================================================================

    @Test
    @DisplayName("判据 5｜super_admin 改预置角色 → 成功（改预置角色属平台级操作）")
    void superAdminCanUpdatePresetRole() throws Exception {
        String token = superAdminToken();

        JsonNode response = client.putWithToken(ROLES + SystemFixtures.ROLE_PRESET_TEACHER, token,
                """
                {"roleName":"教师","status":0,"remark":"IT 超管改过"}
                """);

        assertThat(code(response)).isEqualTo(200);
        assertThat(systemFixtures.roleName(SystemFixtures.ROLE_PRESET_TEACHER)).isEqualTo("教师");
    }

    // =====================================================================
    // 判据 6｜任何人删不掉预置角色（含超管）
    // =====================================================================

    @Test
    @DisplayName("判据 6-①｜org_admin 删预置角色 → 400，未被逻辑删除")
    void orgAdminCannotDeletePresetRole() throws Exception {
        String token = orgAdminToken();

        JsonNode response = deleteWithToken(ROLES + SystemFixtures.ROLE_PRESET_TEACHER, token);

        assertThat(code(response)).isEqualTo(400);
        assertThat(systemFixtures.roleDeletedAt(SystemFixtures.ROLE_PRESET_TEACHER)).isZero();
    }

    @Test
    @DisplayName("判据 6-②｜super_admin 也删不掉预置角色 → 400（§3.5：任何人不可删）")
    void superAdminCannotDeletePresetRoleEither() throws Exception {
        String token = superAdminToken();

        JsonNode response = deleteWithToken(ROLES + SystemFixtures.ROLE_PRESET_TEACHER, token);

        // 这一条容易被实现成 10008「角色已被用户引用」——预置角色确实被引用着。
        // 但那条提示语说的是"先给相关用户改派角色"，而这个动作【根本不该被尝试】：
        // 四个角色是契约第 3 节的固定集合，删掉即全平台该类用户失权。
        // 所以 PresetRoleGuard 的删除断言排在引用检查之前
        assertThat(code(response)).isEqualTo(400);
        assertThat(systemFixtures.roleDeletedAt(SystemFixtures.ROLE_PRESET_TEACHER)).isZero();
    }

    @Test
    @DisplayName("对照｜org_admin 删本租户自建角色（无人引用）→ 成功")
    void orgAdminCanDeleteOwnCustomRole() throws Exception {
        String token = orgAdminToken();

        JsonNode response = deleteWithToken(ROLES + SystemFixtures.ROLE_TENANT_A_CUSTOM, token);

        assertThat(code(response)).isEqualTo(200);
        assertThat(systemFixtures.roleDeletedAt(SystemFixtures.ROLE_TENANT_A_CUSTOM)).isNotZero();
    }

    // =====================================================================
    // 判据 7｜§3.6 防提权
    // =====================================================================

    @Test
    @DisplayName("判据 7｜org_admin 给自建角色分配一个自己没有的菜单 → 400，原绑定未变")
    void orgAdminCannotAssignMenuBeyondOwnScope() throws Exception {
        String token = orgAdminToken();
        List<Long> before = systemFixtures.roleMenuIds(SystemFixtures.ROLE_TENANT_A_CUSTOM);

        JsonNode response = client.putWithToken(
                ROLES + SystemFixtures.ROLE_TENANT_A_CUSTOM + "/menus", token,
                """
                {"menuIds":[%d]}
                """.formatted(SystemFixtures.MENU_SUPER_ADMIN_ONLY));

        assertThat(code(response)).isEqualTo(400);
        // MENU_SUPER_ADMIN_ONLY 是 system:user:add，初始化数据里只绑了 super_admin。
        // 放行的话，org_admin 就能造一个"能建用户"的角色再把自己挂上去 —— 提权闭环
        assertThat(systemFixtures.roleMenuIds(SystemFixtures.ROLE_TENANT_A_CUSTOM))
                .containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("判据 7 对照｜分配自己确实拥有的菜单 → 成功")
    void orgAdminCanAssignMenuWithinOwnScope() throws Exception {
        String token = orgAdminToken();

        JsonNode response = client.putWithToken(
                ROLES + SystemFixtures.ROLE_TENANT_A_CUSTOM + "/menus", token,
                """
                {"menuIds":[%d]}
                """.formatted(SystemFixtures.MENU_ORG_ADMIN_OWNED));

        assertThat(code(response)).isEqualTo(200);
        assertThat(systemFixtures.roleMenuIds(SystemFixtures.ROLE_TENANT_A_CUSTOM))
                .containsExactly(SystemFixtures.MENU_ORG_ADMIN_OWNED);
    }

    @Test
    @DisplayName("判据 7 变体｜§3.3 建角色时塞越权菜单 → 400（否则是绕过 §3.6 的后门）")
    void orgAdminCannotSmuggleMenuThroughRoleCreation() throws Exception {
        String token = orgAdminToken();

        JsonNode response = client.postWithToken("/api/v1/system/roles", token,
                """
                {"roleName":"IT 提权角色","roleKey":"it_escalated","menuIds":[%d]}
                """.formatted(SystemFixtures.MENU_SUPER_ADMIN_ONLY));

        // §3.3 参数表：menuIds「等价于创建后调用 3.6」——所以必须走同一条防提权校验。
        // 不共用的话，这里就是把自己没有的菜单塞进新角色再挂上去的后门
        assertThat(code(response)).isEqualTo(400);
    }

    @Test
    @DisplayName("§3.3｜roleKey 不得使用预置值 → 400")
    void roleKeyCannotReusePresetValue() throws Exception {
        String token = orgAdminToken();

        JsonNode response = client.postWithToken("/api/v1/system/roles", token,
                """
                {"roleName":"IT 冒名教师","roleKey":"teacher"}
                """);

        assertThat(code(response)).isEqualTo(400);
    }

    // =====================================================================

    private String orgAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }

    private String superAdminToken() throws Exception {
        return client.loginForToken(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
    }
}
