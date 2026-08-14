package com.edumatrix.system.support;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.auth.support.AuthFixtures;

/**
 * 模块 03 验收用的角色 / 菜单夹具，<b>建在 {@link AuthFixtures} 那棵树之上</b>。
 *
 * <h2>为什么复用模块 02 的树而不是另起一棵</h2>
 * <p>那棵树上恰好有本模块要的两个对照组：
 * <pre>
 * ROOT(1,机构最高管理员, org_admin)     ← 判据 1 的甲
 *  ├─ ADMIN(1,下级管理员, org_admin)    ← 判据 1 的乙：层级不同、角色相同
 *  │   └─ S1(3,学生)
 *  └─ TEACHER(2,教师)
 *      └─ S2(3,学生)
 * 另有 EXPIRED_TENANT（租户 B）与平台超管
 * </pre>
 * 判据 3「租户 A 列不出租户 B 的自建角色」直接用 {@code EXPIRED_TENANT_ID} 当租户 B ——
 * 它<b>只需要存在一行自建角色</b>，不需要登录（那个租户已到期，登录会被 {@code 10007} 拒）。
 *
 * <p>本类只<b>追加</b>行、并在清理时只删自己追加的，{@link AuthFixtures} 与基线示例数据一个字不动。
 */
public final class SystemFixtures {

    /** 租户 A（= {@link AuthFixtures#TENANT_ID}）的自建角色 —— 写侧三件套的"可写"对照组。 */
    public static final long ROLE_TENANT_A_CUSTOM = 1961000000000000001L;
    /** 租户 B 的自建角色 —— 判据 3：租户 A 必须列不出它。 */
    public static final long ROLE_TENANT_B_CUSTOM = 1961000000000000002L;

    public static final String ROLE_KEY_A = "it_academic_director";
    public static final String ROLE_KEY_B = "it_tenant_b_role";

    /**
     * 一个<b>没有任何角色绑定</b>的菜单，用于 §4.4 删除菜单的正向路径。
     *
     * <p>不能拿初始化数据里的 124 行中任何一行来删：它们全都被角色绑定着，
     * 删除一律 {@code 10009}，验不到正向路径；而删完也无法还原成"从未被删过"。
     */
    public static final long MENU_ORPHAN = 1961000000000000101L;
    /** 挂在 {@link #MENU_ORPHAN} 下的子菜单，用于验 {@code 10009} 的"有子节点"分支。 */
    public static final long MENU_ORPHAN_CHILD = 1961000000000000102L;

    public static final String MENU_ORPHAN_PERMS = "system:itprobe:list";
    public static final String MENU_ORPHAN_CHILD_PERMS = "system:itprobe:add";

    /**
     * 一个 {@code org_admin} <b>没有</b>的菜单，判据 4（§3.6 防提权）拿它当"越权目标"。
     *
     * <p>用初始化数据里现成的 {@code system:user:add}（{@code 1949000000000600101}）——
     * 它只绑了 {@code super_admin}，正是"org_admin 自己没有"的真实样本。
     * 造一个新的反而验不到真实的绑定关系。
     */
    public static final long MENU_SUPER_ADMIN_ONLY = 1949000000000600101L;

    /** {@code org_admin} 确实拥有的菜单（{@code system:role:list}），用于正向对照。 */
    public static final long MENU_ORG_ADMIN_OWNED = 1949000000000600200L;

    /** 基线自带的四个内置角色（{@code tenant_id = 0}）。 */
    public static final long ROLE_PRESET_TEACHER = AuthFixtures.ROLE_TEACHER;
    public static final long ROLE_PRESET_ORG_ADMIN = AuthFixtures.ROLE_ORG_ADMIN;

    private final JdbcTemplate jdbc;

    public SystemFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void seed() {
        clean();
        insertRole(ROLE_TENANT_A_CUSTOM, "IT 教务主任", ROLE_KEY_A, AuthFixtures.TENANT_ID);
        insertRole(ROLE_TENANT_B_CUSTOM, "IT 租户B自建角色", ROLE_KEY_B, AuthFixtures.EXPIRED_TENANT_ID);
        // 租户 A 的自建角色先绑一个 org_admin 确实拥有的菜单，
        // 这样 §3.6 的"改成越权菜单被拒"能顺带断言原绑定未被破坏
        insertRoleMenu(ROLE_TENANT_A_CUSTOM, MENU_ORG_ADMIN_OWNED, AuthFixtures.TENANT_ID);

        insertMenu(MENU_ORPHAN, 0L, "IT 探针菜单", "C", MENU_ORPHAN_PERMS, "/system/it-probe");
        insertMenu(MENU_ORPHAN_CHILD, MENU_ORPHAN, "IT 探针按钮", "F", MENU_ORPHAN_CHILD_PERMS, null);
    }

    public void clean() {
        for (long roleId : new long[]{ROLE_TENANT_A_CUSTOM, ROLE_TENANT_B_CUSTOM}) {
            jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
            jdbc.update("DELETE FROM sys_user_role WHERE role_id = ?", roleId);
            jdbc.update("DELETE FROM sys_role WHERE id = ?", roleId);
        }
        // 用例中新建的角色/菜单按 key 前缀清（id 由雪花生成，事先不知道）
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id IN "
                + "(SELECT id FROM sys_role WHERE role_key LIKE 'it\\_%')");
        jdbc.update("DELETE FROM sys_role WHERE role_key LIKE 'it\\_%'");
        jdbc.update("DELETE FROM sys_role_menu WHERE menu_id IN "
                + "(SELECT id FROM sys_menu WHERE perms LIKE 'system:itprobe:%')");
        jdbc.update("DELETE FROM sys_menu WHERE perms LIKE 'system:itprobe:%'");
        // 用例中经 §2.2 建出的账号与节点（用户名统一 it_new_ 前缀）
        jdbc.update("DELETE FROM org_node_change_log WHERE node_id IN "
                + "(SELECT node_id FROM sys_user WHERE username LIKE 'it\\_new\\_%')");
        jdbc.update("DELETE FROM org_node WHERE ref_user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'it\\_new\\_%')");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE 'it\\_new\\_%')");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'it\\_new\\_%'");
    }

    // =====================================================================
    // 断言用的读取
    // =====================================================================

    public String roleName(long roleId) {
        return jdbc.query("SELECT role_name FROM sys_role WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, roleId);
    }

    public Integer roleStatus(long roleId) {
        return jdbc.query("SELECT status FROM sys_role WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, roleId);
    }

    public Long roleDeletedAt(long roleId) {
        return jdbc.query("SELECT deleted_at FROM sys_role WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, roleId);
    }

    public List<Long> roleMenuIds(long roleId) {
        return jdbc.queryForList(
                "SELECT menu_id FROM sys_role_menu WHERE role_id = ? AND deleted_at = 0 ORDER BY menu_id",
                Long.class, roleId);
    }

    /** 按用户名取账号 id（含已逻辑删除的行需另判 {@code deleted_at}）。 */
    public Long userIdByUsername(String username) {
        return jdbc.query("SELECT id FROM sys_user WHERE username = ? AND deleted_at = 0",
                rs -> rs.next() ? rs.getLong(1) : null, username);
    }

    public int userRowCount(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    public Long userNodeId(long userId) {
        return jdbc.query("SELECT node_id FROM sys_user WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, userId);
    }

    public Integer nodeType(long nodeId) {
        return jdbc.query("SELECT node_type FROM org_node WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, nodeId);
    }

    public String nodeAncestors(long nodeId) {
        return jdbc.query("SELECT ancestors FROM org_node WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, nodeId);
    }

    public Long nodeRefUserId(long nodeId) {
        return jdbc.query("SELECT ref_user_id FROM org_node WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, nodeId);
    }

    public Long nodeDeletedAt(long nodeId) {
        return jdbc.query("SELECT deleted_at FROM org_node WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, nodeId);
    }

    public Integer nodeChildCount(long nodeId) {
        return jdbc.query("SELECT child_count FROM org_node WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, nodeId);
    }

    /** {@code org_node_change_log} 里该节点的建档轨迹条数（{@code change_type = 1}）。 */
    public int createChangeLogCount(long nodeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_node_change_log WHERE node_id = ? AND change_type = 1",
                Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    /** 该节点全部异动轨迹条数 —— 用于断言删除路径<b>不写</b>轨迹。 */
    public int changeLogCount(long nodeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_node_change_log WHERE node_id = ?", Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    public Long changeLogToParentId(long nodeId) {
        return jdbc.query("SELECT to_parent_id FROM org_node_change_log "
                        + "WHERE node_id = ? AND change_type = 1 ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, nodeId);
    }

    public Object changeLogFromParentId(long nodeId) {
        return jdbc.query("SELECT from_parent_id FROM org_node_change_log "
                        + "WHERE node_id = ? AND change_type = 1 ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getObject(1) : null, nodeId);
    }

    public Integer userStatus(long userId) {
        return jdbc.query("SELECT status FROM sys_user WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId);
    }

    public String userPasswordHash(long userId) {
        return jdbc.query("SELECT password FROM sys_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
    }

    public Integer userPwdResetFlag(long userId) {
        return jdbc.query("SELECT pwd_reset_flag FROM sys_user WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId);
    }

    /** 直接建一个子节点，用于验 §2.4 的 {@code 10108}（节点下存在子节点，不可删除）。 */
    public void attachChildNode(long childNodeId, long parentNodeId, String ancestors,
                                int nodeType, long refUserId, long tenantId) {
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, 'IT 子节点', ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)",
                childNodeId, parentNodeId, ancestors, nodeType, refUserId, tenantId);
    }

    public void removeNode(long nodeId) {
        jdbc.update("DELETE FROM org_node WHERE id = ?", nodeId);
    }

    // =====================================================================

    private void insertRole(long id, String roleName, String roleKey, long tenantId) {
        jdbc.update("INSERT INTO sys_role (id, role_name, role_key, status, sort, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, 0, 10, ?, NOW(), NOW(), 0)",
                id, roleName, roleKey, tenantId);
    }

    private void insertRoleMenu(long roleId, long menuId, long tenantId) {
        jdbc.update("INSERT INTO sys_role_menu (id, role_id, menu_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                roleId + menuId % 1000, roleId, menuId, tenantId);
    }

    private void insertMenu(long id, long parentId, String menuName, String menuType,
                            String perms, String path) {
        jdbc.update("INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, perms, path, "
                        + "sort, visible, status, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 99, 1, 0, NOW(), NOW(), 0)",
                id, parentId, menuName, menuType, perms, path);
    }
}
