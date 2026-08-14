package com.edumatrix.system.support;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.auth.support.AuthFixtures;

/**
 * 模块 04 验收用的夹具：<b>不预置任何租户</b>，只负责清理与断言取数。
 *
 * <h2>为什么不预置</h2>
 * <p>本模块要验的核心是「开通机构」<b>这个动作本身</b>（三步事务、四值恒等、失败回滚），
 * 预置一个现成的租户等于把被验对象直接写成正确答案。所以用例一律走
 * {@code POST /api/v1/system/tenants} 真实开通，本类只在前后把痕迹清干净。
 *
 * <h2>清理按名称前缀，且要连带清五张表</h2>
 * <p>一次开通会落 {@code sys_tenant} + {@code sys_user} + {@code org_node} +
 * {@code org_node_change_log} + {@code sys_user_role}（配置用例另落 {@code sys_tenant_config}）。
 * 只删租户行会留下一棵挂在平台根下的孤儿树，而<b>下一个用例的
 * 「仅存在一个 {@code node_type=1} / {@code parent_id=0} 的机构根节点」断言会被它污染</b>。
 *
 * <p>用 {@link JdbcTemplate} 直接写：它绕过 MyBatis，不受租户插件与逻辑删除影响，
 * 因此看得见已被逻辑删除的行——清理必须看得见它们。业务代码永远不该这么干。
 *
 * <h2>平台根的 {@code child_count} 要还原</h2>
 * <p>每开通一个机构，{@code org_node(id=0).child_count} 就 +1（{@code PlatformNodeWriter}
 * 维护的纯结构计数）。用例结束把节点删了，计数却留在那儿——于是这个冗余列会随测试次数
 * <b>单调漂移</b>。快照 + 还原是最稳的做法：不依赖"当前有几个活着的子节点"，
 * 因而不受同一上下文里其它夹具（{@code AuthFixtures} 也建 {@code parent_id=0} 的节点）的干扰。
 */
public final class TenantFixtures {

    /** 用例开通的机构一律用这个前缀命名，清理按它匹配。 */
    public static final String TENANT_NAME_PREFIX = "IT04 ";
    /** 用例开通的初始管理员用户名前缀。 */
    public static final String ADMIN_USERNAME_PREFIX = "it04_";

    private final JdbcTemplate jdbc;

    private Integer platformRootChildCountSnapshot;

    public TenantFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    public void snapshotAndClean() {
        clean();
        platformRootChildCountSnapshot = jdbc.queryForObject(
                "SELECT child_count FROM org_node WHERE id = 0", Integer.class);
    }

    public void restoreAndClean() {
        clean();
        if (platformRootChildCountSnapshot != null) {
            jdbc.update("UPDATE org_node SET child_count = ? WHERE id = 0",
                    platformRootChildCountSnapshot);
        }
    }

    /** 删掉本模块用例造出的一切，含已被逻辑删除的行。 */
    public void clean() {
        for (Long tenantId : testTenantIds()) {
            jdbc.update("DELETE FROM sys_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM sys_login_log WHERE user_id IN "
                    + "(SELECT id FROM sys_user WHERE tenant_id = ?)", tenantId);
            jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM org_node_change_log WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM org_node WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM sys_tenant_config WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
        // 事务回滚的用例里租户行本就不该存在，但账号可能因【缺陷】残留（那正是判据 2 要抓的）——
        // 按用户名前缀再兜一次，免得一次失败的断言污染后面全部用例
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE ?", ADMIN_USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN "
                + "(SELECT id FROM sys_user WHERE username LIKE ?)", ADMIN_USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE ?", ADMIN_USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM sys_tenant WHERE name LIKE ?", TENANT_NAME_PREFIX + "%");

        // §6 的用例是在 AuthFixtures 那个测试租户上写配置的（那两个接口只对 org_admin 开放，
        // 而 org_admin 只存在于那棵树上），所以它们的配置行也要清。
        // 基线不带任何 sys_tenant_config 行，故按租户清是安全的
        jdbc.update("DELETE FROM sys_tenant_config WHERE tenant_id IN (?, ?)",
                AuthFixtures.TENANT_ID, AuthFixtures.EXPIRED_TENANT_ID);
    }

    private List<Long> testTenantIds() {
        return jdbc.queryForList("SELECT id FROM sys_tenant WHERE name LIKE ?",
                Long.class, TENANT_NAME_PREFIX + "%");
    }

    // =====================================================================
    // 断言取数（一律绕过逻辑删除过滤，因为要验的正是 deleted_at 本身）
    // =====================================================================

    /** {@code sys_tenant} 的行数（含已逻辑删除），用于「库中不留孤儿租户行」。 */
    public int tenantRowCount(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_tenant WHERE name = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    public Long tenantIdByName(String name) {
        return jdbc.query("SELECT id FROM sys_tenant WHERE name = ? AND deleted_at = 0",
                rs -> rs.next() ? rs.getLong(1) : null, name);
    }

    public Long tenantRootNodeId(long tenantId) {
        return jdbc.query("SELECT root_node_id FROM sys_tenant WHERE id = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, tenantId);
    }

    public Integer tenantStatus(long tenantId) {
        return jdbc.queryForObject("SELECT status FROM sys_tenant WHERE id = ?",
                Integer.class, tenantId);
    }

    public Long tenantDeletedAt(long tenantId) {
        return jdbc.queryForObject("SELECT deleted_at FROM sys_tenant WHERE id = ?",
                Long.class, tenantId);
    }

    public int userRowCount(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    public Long userIdByUsername(String username) {
        return jdbc.query("SELECT id FROM sys_user WHERE username = ? AND deleted_at = 0",
                rs -> rs.next() ? rs.getLong(1) : null, username);
    }

    public Long userNodeId(long userId) {
        return jdbc.queryForObject("SELECT node_id FROM sys_user WHERE id = ?", Long.class, userId);
    }

    public Integer userPwdResetFlag(long userId) {
        return jdbc.queryForObject("SELECT pwd_reset_flag FROM sys_user WHERE id = ?",
                Integer.class, userId);
    }

    public Long userTenantId(long userId) {
        return jdbc.queryForObject("SELECT tenant_id FROM sys_user WHERE id = ?",
                Long.class, userId);
    }

    public int nodeRowCount(long nodeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM org_node WHERE id = ?", Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    public Long nodeRefUserId(long nodeId) {
        return jdbc.queryForObject("SELECT ref_user_id FROM org_node WHERE id = ?",
                Long.class, nodeId);
    }

    public Long nodeTenantId(long nodeId) {
        return jdbc.queryForObject("SELECT tenant_id FROM org_node WHERE id = ?",
                Long.class, nodeId);
    }

    public Integer nodeType(long nodeId) {
        return jdbc.queryForObject("SELECT node_type FROM org_node WHERE id = ?",
                Integer.class, nodeId);
    }

    public Long nodeParentId(long nodeId) {
        return jdbc.queryForObject("SELECT parent_id FROM org_node WHERE id = ?",
                Long.class, nodeId);
    }

    public String nodeAncestors(long nodeId) {
        return jdbc.queryForObject("SELECT ancestors FROM org_node WHERE id = ?",
                String.class, nodeId);
    }

    public String nodeName(long nodeId) {
        return jdbc.queryForObject("SELECT node_name FROM org_node WHERE id = ?",
                String.class, nodeId);
    }

    public Long nodeDeletedAt(long nodeId) {
        return jdbc.queryForObject("SELECT deleted_at FROM org_node WHERE id = ?",
                Long.class, nodeId);
    }

    /** 判据 1：该租户内 {@code node_type=1} 且 {@code parent_id=0} 的<b>未删除</b>节点数。 */
    public int rootNodeCount(long tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM org_node WHERE tenant_id = ? AND node_type = 1 "
                        + "AND parent_id = 0 AND deleted_at = 0", Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    /** 建档轨迹（{@code change_type=1}）条数。 */
    public int createChangeLogCount(long nodeId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM org_node_change_log WHERE node_id = ? AND change_type = 1",
                Integer.class, nodeId);
        return count == null ? 0 : count;
    }

    public Long changeLogFromParentId(long nodeId) {
        return jdbc.query("SELECT from_parent_id FROM org_node_change_log "
                        + "WHERE node_id = ? AND change_type = 1 LIMIT 1",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, nodeId);
    }

    public Long changeLogTenantId(long nodeId) {
        return jdbc.queryForObject("SELECT tenant_id FROM org_node_change_log "
                + "WHERE node_id = ? AND change_type = 1 LIMIT 1", Long.class, nodeId);
    }

    public int userRoleCount(long userId, long roleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sys_user_role WHERE user_id = ? AND role_id = ? "
                        + "AND deleted_at = 0", Integer.class, userId, roleId);
        return count == null ? 0 : count;
    }

    public Long userRoleTenantId(long userId) {
        return jdbc.queryForObject(
                "SELECT tenant_id FROM sys_user_role WHERE user_id = ? LIMIT 1", Long.class, userId);
    }

    public Integer platformRootChildCount() {
        return jdbc.queryForObject("SELECT child_count FROM org_node WHERE id = 0", Integer.class);
    }

    // =====================================================================
    // 租户配置
    // =====================================================================

    public String configValue(long tenantId, String configKey) {
        return jdbc.query("SELECT config_value FROM sys_tenant_config "
                        + "WHERE tenant_id = ? AND config_key = ? AND deleted_at = 0",
                rs -> rs.next() ? rs.getString(1) : null, tenantId, configKey);
    }

    public void deleteConfig(long tenantId, String configKey) {
        jdbc.update("DELETE FROM sys_tenant_config WHERE tenant_id = ? AND config_key = ?",
                tenantId, configKey);
    }

    public void insertConfig(long tenantId, String configKey, String configValue) {
        deleteConfig(tenantId, configKey);
        jdbc.update("INSERT INTO sys_tenant_config (id, config_key, config_value, tenant_id) "
                        + "VALUES (?, ?, ?, ?)",
                System.nanoTime(), configKey, configValue, tenantId);
    }

    // =====================================================================
    // 判据 2 用：让开通的第②步失败
    // =====================================================================

    /**
     * 把平台根哨兵行临时置为已删除，使 {@code PlatformNodeWriter#createTenantRootNode}
     * 的 {@code requireParent(0)} 查不到父节点，在<b>第②步的建节点这一小步</b>抛 {@code 10101}。
     *
     * <p><b>为什么用它，而不是 {@code @MockBean}</b>：{@code AuthIntegrationTestBase} 的类注释
     * 立过一条硬规矩——{@code TenantHelper} 的 provider 是<b>静态字段</b>，
     * 而 {@code @MockBean} 会改变 Spring 测试上下文的缓存键、<b>造出第二个上下文</b>，
     * 于是先前上下文里的测试类会读到别人的 provider。那条警告不绕。
     *
     * <p>本方法只改一行数据、且由 {@code try/finally} 立刻还原，不碰上下文配置。
     * 它让失败点落在真实代码路径上（父节点查不到），而不是一个被打桩的假异常——
     * 这一点反而比 mock 更接近判据 2 想验的东西。
     */
    public void hidePlatformRootNode() {
        jdbc.update("UPDATE org_node SET deleted_at = 1 WHERE id = 0");
    }

    public void restorePlatformRootNode() {
        jdbc.update("UPDATE org_node SET deleted_at = 0 WHERE id = 0");
    }
}
