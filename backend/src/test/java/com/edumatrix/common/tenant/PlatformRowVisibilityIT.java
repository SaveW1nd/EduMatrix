package com.edumatrix.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.support.mapper.ProbeMapper;

/**
 * <b>契约 §2.9 的原始验收</b>（模块 01 完成判据第 4 条）。
 *
 * <p>用<b>租户 A 的非超管账号</b>证明两件事<b>同时</b>成立：
 * <ol>
 *   <li>读得到 {@code tenant_id = 0} 的内置角色行；
 *   <li>读不到租户 B 的自建角色行。
 * </ol>
 *
 * <p><b>两半都要验，缺一半都会漏掉一类错误</b>：
 * <ul>
 *   <li>只验前半 —— 可能是把整张表 {@code ignoreTable} 忽略了，那样租户 A 连租户 B 的
 *       「教务主任」都能列出、甚至改删；
 *   <li>只验后半 —— 可能是压根没放行，那样每个非超管用户 {@code roles=[]}、{@code perms=[]}，
 *       前端按钮全隐、后端 {@code @SaCheckPermission} 全部 403。而这是个<b>不报错的故障</b>：
 *       接口返回 200、格式正确，只是数组为空。
 * </ul>
 *
 * <p>再加一组<b>反向对照</b>：{@code sys_oper_log} / {@code sys_user} 同样承载
 * {@code tenant_id = 0} 的行（超管的操作日志与账号），但它们<b>不在放行清单里</b>，
 * 必须读不到 —— 否则就是把超管的账号、手机号、操作轨迹暴露给了每一个租户管理员。
 */
@IntegrationTest
class PlatformRowVisibilityIT {

    /** 基线示例数据里的租户 A（机构根节点 id 即 tenant_id，契约 §2.1）。 */
    private static final long TENANT_A = 1953827104412590001L;
    /** 本测试新建的租户 B。 */
    private static final long TENANT_B = 2953827104412590001L;

    private static final long ROLE_A_ID = 8800000000000000001L;
    private static final long ROLE_B_ID = 8800000000000000002L;
    private static final long OPER_LOG_PLATFORM_ID = 8800000000000000003L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProbeMapper probeMapper;

    @Autowired
    private TestCurrentContextProvider context;

    @BeforeEach
    void setUp() {
        cleanUp();
        // 用 JdbcTemplate 直接写：它绕过 MyBatis，因此不受租户插件影响，
        // 可以造出跨租户的数据。业务代码永远不该这么干。
        insertRole(ROLE_A_ID, "org_teaching_director_a", "教务主任A", TENANT_A);
        insertRole(ROLE_B_ID, "org_teaching_director_b", "教务主任B", TENANT_B);
        jdbcTemplate.update(
                "INSERT INTO sys_oper_log (id, user_id, module, action, method, ip, status, cost_ms, "
                        + "oper_time, tenant_id, update_time, deleted_at) "
                        + "VALUES (?, 1, '平台超管操作', '开通机构', 'POST /api/v1/system/tenants', "
                        + "'127.0.0.1', 0, 10, NOW(), 0, NOW(), 0)",
                OPER_LOG_PLATFORM_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
        context.asNoSession();
        TenantHelper.reset();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM sys_role WHERE id IN (?, ?)", ROLE_A_ID, ROLE_B_ID);
        jdbcTemplate.update("DELETE FROM sys_oper_log WHERE id = ?", OPER_LOG_PLATFORM_ID);
    }

    private void insertRole(long id, String roleKey, String roleName, long tenantId) {
        jdbcTemplate.update(
                "INSERT INTO sys_role (id, role_name, role_key, status, sort, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, 0, 0, ?, NOW(), NOW(), 0)",
                id, roleName, roleKey, tenantId);
    }

    @Test
    @DisplayName("租户 A 的非超管账号：既读得到内置角色，又读不到租户 B 的自建角色")
    void tenantUserSeesPlatformRolesButNotOtherTenantRoles() {
        context.asTenantUser(TENANT_A, 1953827104412590102L, TENANT_A);

        List<String> roleKeys = probeMapper.selectRoleKeys();

        // ① 前半：读得到 tenant_id = 0 的四个内置角色
        assertThat(roleKeys)
                .as("读不到内置角色 → roles=[] / perms=[] → 每个非超管用户零权限。"
                        + "而它是个不报错的故障：接口 200、字段齐全，只是数组为空")
                .contains("super_admin", "org_admin", "teacher", "student");

        // ② 后半：读不到租户 B 的自建角色
        assertThat(roleKeys)
                .as("读得到别的租户的自建角色 → 说明用了 ignoreTable 整表忽略，"
                        + "租户 A 能列出甚至改删租户 B 的角色")
                .doesNotContain("org_teaching_director_b");

        // ③ 自己租户的自建角色照常可见
        assertThat(roleKeys).contains("org_teaching_director_a");
    }

    @Test
    @DisplayName("sys_role_menu 同样放行：内置角色的菜单绑定读得到（否则 perms 仍为空）")
    void roleMenuBindingsAreVisible() {
        context.asTenantUser(TENANT_A, 1953827104412590102L, TENANT_A);

        assertThat(probeMapper.countRoleMenus())
                .as("/auth/me 的链路是 sys_user_role → sys_role → sys_role_menu，"
                        + "放行断在任何一环，结果都是 perms=[]")
                // 200 → 201（模块 06 的 V202608150000 补 teacher → org:node:list）
                //     → 206（模块 07 的 V202608160000 拆 org:staff:list，补 5 条）
                //     → 203（模块 10 的 V202608200000 撤销 teacher 的 question:category:* 三条，F-72）
                //     → 202（V202608210000 撤销 teacher 的 vod:video:add 一条，需方 2026-08-21 定案二）。
                // 这里断言的是【放行是否生效】，行数只是它的载体，随初始化数据增减都是正常的
                .isEqualTo(202);
    }

    @Test
    @DisplayName("反向对照：sys_oper_log 不放行 —— 超管的操作日志读不到")
    void operLogIsNotWhitelisted() {
        context.asTenantUser(TENANT_A, 1953827104412590102L, TENANT_A);

        assertThat(probeMapper.selectOperLogModules())
                .as("放行 sys_oper_log 等于把超管的操作轨迹暴露给每一个租户管理员（契约 §2.9）")
                .doesNotContain("平台超管操作");
    }

    @Test
    @DisplayName("反向对照：sys_user 不放行 —— 超管账号读不到")
    void sysUserIsNotWhitelisted() {
        context.asTenantUser(TENANT_A, 1953827104412590102L, TENANT_A);

        List<String> usernames = probeMapper.selectUsernames();
        assertThat(usernames)
                .as("超管读自己这些行不靠放行，而靠「租户插件对超管整体放行」——"
                        + "这是两条不同的通道，不要混用")
                .doesNotContain("superadmin");
        assertThat(usernames).isNotEmpty();
    }

    @Test
    @DisplayName("超管会话：整体放行，跨租户可见（第二条通道）")
    void superAdminSeesEverything() {
        context.asSuperAdmin(1953827104412590101L, 0L);

        List<String> roleKeys = probeMapper.selectRoleKeys();
        assertThat(roleKeys)
                .as("超管跨租户靠租户插件整体放行（02-数据库设计 §3.2），与 tenant_id = 0 放行无关")
                .contains("org_teaching_director_a", "org_teaching_director_b", "super_admin");
    }

    @Test
    @DisplayName("租户 B 的账号看得到自己的角色，看不到租户 A 的 —— 隔离是双向的")
    void isolationIsSymmetric() {
        context.asTenantUser(TENANT_B, 999L, TENANT_B);

        List<String> roleKeys = probeMapper.selectRoleKeys();
        assertThat(roleKeys).contains("org_teaching_director_b", "org_admin");
        assertThat(roleKeys).doesNotContain("org_teaching_director_a");
    }
}
