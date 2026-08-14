package com.edumatrix.auth.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 模块 02 验收用的组织树夹具。
 *
 * <h2>为什么不用基线自带的示例数据</h2>
 * <ol>
 *   <li>示例账号的密码是 <b>{@code $2a$10$BCRYPT_PLACEHOLDER_REPLACE_ON_DEPLOY_...} 占位符</b>，
 *       不是合法 BCrypt 密文，<b>根本登不进来</b>；
 *   <li>模块 01 的 {@code SubtreeScopeHelperIT} 对示例租户的子树用了
 *       {@code containsExactlyInAnyOrder}。<b>往那棵树上加任何一个节点，那批测试立刻红</b>。
 * </ol>
 * 所以本夹具建在一个<b>独立的测试租户</b>下，且每次用例前后都先删后插，
 * 示例树一个字不动。
 *
 * <h2>树形（判据 3 的两个对照组就在这棵树上）</h2>
 * <pre>
 * ROOT(1,机构最高管理员, pwd_reset_flag=1 初始密码)
 *  ├─ ADMIN(1,管理员)        ← 停用它：分支冻结，S1 被拒
 *  │   └─ S1(3,学生)
 *  └─ TEACHER(2,教师)        ← 停用它：仅本人，S2 照常登录
 *      └─ S2(3,学生)
 * </pre>
 */
public final class AuthFixtures {

    /** 测试租户（契约 §2.1：机构根节点 id 即 tenant_id）。 */
    public static final long TENANT_ID = 1960000000000000001L;
    /** 到期租户（判据 1）。 */
    public static final long EXPIRED_TENANT_ID = 1960000000000000101L;

    public static final long ROOT_NODE = TENANT_ID;
    public static final long ADMIN_NODE = 1960000000000000010L;
    public static final long STUDENT1_NODE = 1960000000000000011L;
    public static final long TEACHER_NODE = 1960000000000000020L;
    public static final long STUDENT2_NODE = 1960000000000000021L;
    public static final long EXPIRED_ROOT_NODE = EXPIRED_TENANT_ID;

    public static final long ROOT_USER = 1960000000000000110L;
    public static final long ADMIN_USER = 1960000000000000111L;
    public static final long STUDENT1_USER = 1960000000000000112L;
    public static final long TEACHER_USER = 1960000000000000113L;
    public static final long STUDENT2_USER = 1960000000000000114L;
    public static final long EXPIRED_ADMIN_USER = 1960000000000000115L;
    /**
     * 平台超管夹具账号。
     *
     * <p>基线自带的 {@code superadmin} 密码是占位符、登不进来，而判据 5 要验
     * {@code super_admin} 的 31 个 perms，只能自建一个。它挂在平台根节点（{@code id = 0}）上 ——
     * {@code sys_user} 对 {@code node_id} 没有唯一约束，而 {@code org_node.uk_ref_user_id}
     * 约束的是反向那一列，故不与基线的 superadmin 冲突。
     */
    public static final long SUPER_ADMIN_USER = 1960000000000000116L;

    public static final String ROOT_USERNAME = "it_root_admin";
    public static final String ADMIN_USERNAME = "it_sub_admin";
    public static final String STUDENT1_USERNAME = "it_student_one";
    public static final String TEACHER_USERNAME = "it_teacher";
    public static final String STUDENT2_USERNAME = "it_student_two";
    public static final String EXPIRED_USERNAME = "it_expired_admin";
    public static final String SUPER_ADMIN_USERNAME = "it_super_admin";

    /** 所有夹具账号的明文密码（8~20 位、含字母与数字，符合 03-01 §1.2 的参数约束）。 */
    public static final String PASSWORD = "Test@123456";

    /** PRD F1-1 验收标准逐字给出的到期时间。 */
    public static final String EXPIRE_TIME = "2026-08-11 23:59:59";

    /** 基线自带的四个内置角色（{@code tenant_id = 0}，契约 §2.9）。 */
    public static final long ROLE_SUPER_ADMIN = 1953827104412590201L;
    public static final long ROLE_ORG_ADMIN = 1953827104412590202L;
    public static final long ROLE_TEACHER = 1953827104412590203L;
    public static final long ROLE_STUDENT = 1953827104412590204L;

    private static final long[] ALL_NODES = {
            ROOT_NODE, ADMIN_NODE, STUDENT1_NODE, TEACHER_NODE, STUDENT2_NODE, EXPIRED_ROOT_NODE};
    private static final long[] ALL_USERS = {
            ROOT_USER, ADMIN_USER, STUDENT1_USER, TEACHER_USER, STUDENT2_USER,
            EXPIRED_ADMIN_USER, SUPER_ADMIN_USER};

    private final JdbcTemplate jdbc;
    private final String encodedPassword;

    public AuthFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 与生产同一个 cost（PRD §7.3：≥ 10）
        this.encodedPassword = new BCryptPasswordEncoder(10).encode(PASSWORD);
    }

    /**
     * 先删后插，保证可重复运行。
     *
     * <p>用 {@link JdbcTemplate} 直接写：它绕过 MyBatis，因此不受租户插件影响，
     * 可以造出跨租户的数据。<b>业务代码永远不该这么干</b>（模块 01 的
     * {@code PlatformRowVisibilityIT} 已立此先例）。
     */
    public void seed() {
        clean();

        insertTenant(TENANT_ID, ROOT_NODE, "IT 测试机构", null);
        insertTenant(EXPIRED_TENANT_ID, EXPIRED_ROOT_NODE, "IT 已到期机构", EXPIRE_TIME);

        // 机构最高管理员：初始密码未改（PRD F1-1 规则 6 → 判据 2）
        insertNode(ROOT_NODE, 0L, "0", "IT 测试机构", 1, ROOT_USER, TENANT_ID);
        insertUser(ROOT_USER, ROOT_USERNAME, 1, "机构管理员", ROOT_NODE, TENANT_ID, 1);
        insertUserRole(ROOT_USER, ROLE_ORG_ADMIN, TENANT_ID);

        // 管理员分支：停用 ADMIN 会冻结整棵子树（含 S1）
        insertNode(ADMIN_NODE, ROOT_NODE, "0," + ROOT_NODE, "校区管理员", 1, ADMIN_USER, TENANT_ID);
        insertUser(ADMIN_USER, ADMIN_USERNAME, 1, "校区管理员", ADMIN_NODE, TENANT_ID, 0);
        insertUserRole(ADMIN_USER, ROLE_ORG_ADMIN, TENANT_ID);

        insertNode(STUDENT1_NODE, ADMIN_NODE, "0," + ROOT_NODE + "," + ADMIN_NODE,
                "学生一", 3, STUDENT1_USER, TENANT_ID);
        insertUser(STUDENT1_USER, STUDENT1_USERNAME, 3, "学生一", STUDENT1_NODE, TENANT_ID, 0);
        insertUserRole(STUDENT1_USER, ROLE_STUDENT, TENANT_ID);
        insertStudent(STUDENT1_NODE, STUDENT1_USER, 0);

        // 教师分支：停用 TEACHER 只停他自己，S2 照常登录（PRD F1-2 验收标准）
        insertNode(TEACHER_NODE, ROOT_NODE, "0," + ROOT_NODE, "教师", 2, TEACHER_USER, TENANT_ID);
        insertUser(TEACHER_USER, TEACHER_USERNAME, 2, "教师", TEACHER_NODE, TENANT_ID, 0);
        insertUserRole(TEACHER_USER, ROLE_TEACHER, TENANT_ID);

        insertNode(STUDENT2_NODE, TEACHER_NODE, "0," + ROOT_NODE + "," + TEACHER_NODE,
                "学生二", 3, STUDENT2_USER, TENANT_ID);
        insertUser(STUDENT2_USER, STUDENT2_USERNAME, 3, "学生二", STUDENT2_NODE, TENANT_ID, 0);
        insertUserRole(STUDENT2_USER, ROLE_STUDENT, TENANT_ID);
        insertStudent(STUDENT2_NODE, STUDENT2_USER, 0);

        // 到期机构的管理员（判据 1）
        insertNode(EXPIRED_ROOT_NODE, 0L, "0", "IT 已到期机构", 1, EXPIRED_ADMIN_USER, EXPIRED_TENANT_ID);
        insertUser(EXPIRED_ADMIN_USER, EXPIRED_USERNAME, 1, "到期机构管理员",
                EXPIRED_ROOT_NODE, EXPIRED_TENANT_ID, 0);
        insertUserRole(EXPIRED_ADMIN_USER, ROLE_ORG_ADMIN, EXPIRED_TENANT_ID);

        // 平台超管：挂在平台根节点（id = 0）上，tenant_id = 0（契约 §2.1 / §2.9）
        insertUser(SUPER_ADMIN_USER, SUPER_ADMIN_USERNAME, 0, "平台超管", 0L, 0L, 0);
        insertUserRole(SUPER_ADMIN_USER, ROLE_SUPER_ADMIN, 0L);
    }

    public void clean() {
        for (long userId : ALL_USERS) {
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
            jdbc.update("DELETE FROM sys_login_log WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE 'it\\_%'");
        for (long nodeId : ALL_NODES) {
            jdbc.update("DELETE FROM org_student WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node WHERE id = ?", nodeId);
        }
        jdbc.update("DELETE FROM sys_tenant WHERE id IN (?, ?)", TENANT_ID, EXPIRED_TENANT_ID);
    }

    // =====================================================================
    // 停用 / 启用 —— 顺序按契约 §2.3，由调用方（模块 06 的活）在测试里模拟
    // =====================================================================

    /** 只写 {@code org_node.status}，<b>绝不碰 {@code sys_user.status}</b>（契约 §2.3）。 */
    public void setNodeStatus(long nodeId, int status) {
        jdbc.update("UPDATE org_node SET status = ? WHERE id = ?", status, nodeId);
    }

    /** 断言某账号的 {@code sys_user.status} —— 判据 6 要求它全程未被改动。 */
    public Integer userStatus(long userId) {
        return jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    public Integer nodeStatus(long nodeId) {
        return jdbc.queryForObject("SELECT status FROM org_node WHERE id = ?", Integer.class, nodeId);
    }

    /** 某账号最近一条登录日志的 {@code status}（0 成功 1 失败）；无记录返回 null。 */
    public Integer latestLoginLogStatus(String username) {
        return jdbc.query(
                "SELECT status FROM sys_login_log WHERE username = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getInt(1) : null,
                username);
    }

    public String latestLoginLogMsg(String username) {
        return jdbc.query(
                "SELECT msg FROM sys_login_log WHERE username = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                username);
    }

    public int loginLogCount(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_login_log WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    // =====================================================================

    private void insertTenant(long id, long rootNodeId, String name, String expireTime) {
        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                        + "max_student_count, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 0, 500, NOW(), NOW(), 0)",
                id, rootNodeId, name, expireTime);
    }

    private void insertNode(long id, long parentId, String ancestors, String nodeName,
                            int nodeType, long refUserId, long tenantId) {
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, nodeName, nodeType, refUserId, tenantId);
    }

    private void insertUser(long id, String username, int userType, String realName,
                            long nodeId, long tenantId, int pwdResetFlag) {
        // phone 留 NULL：uk_tenant_phone(tenant_id, phone, deleted_at) 不约束 NULL，
        // 夹具因此不会与示例数据或彼此撞唯一键
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, phone, "
                        + "node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, ?, 0, ?, ?, NOW(), NOW(), 0)",
                id, username, encodedPassword, userType, realName, nodeId, pwdResetFlag, tenantId);
    }

    /**
     * 每个夹具账号只绑一个角色，故主键直接由 userId 偏移得出。
     *
     * <p><b>不要写成 {@code userId + roleId % 1000}</b>：角色 id 的末三位是 201~204，
     * 而账号 id 的末位相邻，两者相加会撞（{@code …112 + 204 == …113 + 203}）。
     */
    private void insertUserRole(long userId, long roleId, long tenantId) {
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                userId + 1000L, userId, roleId, tenantId);
    }

    private void insertStudent(long nodeId, long userId, int status) {
        jdbc.update("INSERT INTO org_student (id, node_id, user_id, status, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                nodeId + 500, nodeId, userId, status, TENANT_ID);
    }

    /** 学籍归档（{@code 10015} 的前置条件）。 */
    public void archiveStudent(long nodeId) {
        jdbc.update("UPDATE org_student SET status = 2, archive_time = NOW() WHERE node_id = ?", nodeId);
    }
}
