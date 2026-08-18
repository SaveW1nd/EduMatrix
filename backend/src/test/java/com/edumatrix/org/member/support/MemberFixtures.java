package com.edumatrix.org.member.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 模块 07 验收用的夹具。
 *
 * <h2>为什么另起一个租户，而不是复用模块 06 的 {@code OrgFixtures}</h2>
 * <p>与模块 06 当初另起一棵树的理由同构，只是这次更硬：本模块<b>建人和删人</b>，
 * 而 {@code OrgFixtures} 的那棵树被 {@code NodeMoveIT} 等七个类按形状断言着
 * （{@code affectedNodeCount} 必须是 13、{@code childCount} 必须是几）——
 * <b>往那棵树上建一个人，那批测试立刻红</b>。
 *
 * <h2>树形</h2>
 * <pre>
 * ROOT(1,机构最高管理员 = 绝大多数用例的操作人)
 *  ├─ A1(1) 华东大区
 *  │   ├─ T1(2) 王丽 ── S01..S12(3)   ← 【PRD F1-4 判据：12 名学员，调岗后 13 个节点重算】
 *  │   └─ T2(2) 李强 ── （空，调岗/分配的落点）
 *  └─ A2(1) 华南大区                  ← 转交管理员的落点；也验跨子树
 * </pre>
 * <p><b>T1 的 12 名学员不是凑数</b>：PRD F1-4 的验收标准逐字写着「教师带 <b>12</b> 名学员调岗后
 * <b>13</b> 个节点 {@code ancestors} 全部重算、原上级立即查不到、<b>只新增 1 条
 * {@code change_type=4}</b>」。少一个都测不出「12 + 1」这个数。
 *
 * <h2>冗余计数按真实值播种</h2>
 * <p>{@code student_count} / {@code child_count} / {@code org_teacher.student_count}
 * 播成动作前的正确值，断言的才是「增量维护对不对」，而不是「从 0 加了几」。
 */
public final class MemberFixtures {

    /** 测试租户（契约 §2.1：机构根节点 id 即 tenant_id）。 */
    public static final long TENANT_ID = 1967000000000000001L;

    public static final long ROOT = TENANT_ID;
    public static final long A1 = 1967000000000000010L;
    public static final long A2 = 1967000000000000011L;
    public static final long T1 = 1967000000000000020L;
    public static final long T2 = 1967000000000000021L;

    /** T1 名下 12 名学员的节点 id 起点。 */
    public static final long S_BASE = 1967000000000000100L;
    /** PRD F1-4 的判据数字：12 名学员 + 教师本人 = 13 个节点。 */
    public static final int STUDENT_COUNT = 12;

    public static final long[] STUDENTS = new long[STUDENT_COUNT];

    static {
        for (int i = 0; i < STUDENT_COUNT; i++) {
            STUDENTS[i] = S_BASE + i;
        }
    }

    public static final long USER_OFFSET = 100000L;
    public static final String USERNAME_PREFIX = "it07_";
    public static final String PASSWORD = "Test@123456";

    public static final String ROOT_USERNAME = USERNAME_PREFIX + ROOT;
    public static final String A1_USERNAME = USERNAME_PREFIX + A1;
    public static final String A2_USERNAME = USERNAME_PREFIX + A2;
    public static final String T1_USERNAME = USERNAME_PREFIX + T1;

    private final JdbcTemplate jdbc;
    private final String encodedPassword;

    public MemberFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 与生产同一个 cost（PRD §7.3：≥ 10）
        this.encodedPassword = new BCryptPasswordEncoder(10).encode(PASSWORD);
    }

    public void seed() {
        clean();

        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                        + "max_student_count, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, 'IT07 人员与学籍机构', NULL, 0, 500, NOW(), NOW(), 0)",
                TENANT_ID, ROOT);

        String rootAnc = "0";
        String lvl2 = "0," + ROOT;
        String underA1 = lvl2 + "," + A1;

        node(ROOT, 0L, rootAnc, "IT07 人员与学籍机构", 1, 2, STUDENT_COUNT);
        node(A1, ROOT, lvl2, "华东大区", 1, 2, STUDENT_COUNT);
        node(A2, ROOT, lvl2, "华南大区", 1, 0, 0);
        node(T1, A1, underA1, "王丽", 2, STUDENT_COUNT, STUDENT_COUNT);
        node(T2, A1, underA1, "李强", 2, 0, 0);

        String underT1 = underA1 + "," + T1;
        for (int i = 0; i < STUDENT_COUNT; i++) {
            student(STUDENTS[i], T1, underT1, "学员" + (i + 1), "S07" + String.format("%03d", i + 1));
        }

        teacher(T1, "T07001", STUDENT_COUNT);
        teacher(T2, "T07002", 0);
    }

    public void clean() {
        for (long nodeId : allNodes()) {
            long userId = userIdOf(nodeId);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
            jdbc.update("DELETE FROM org_student WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_teacher WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node_change_log WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node WHERE id = ?", nodeId);
        }
        // 本模块会【建人】，那些节点的 id 是雪花生成的、不在上面的清单里 ——
        // 按租户整体清一遍。这是测试专用的粗暴写法，业务代码永远不该这么干
        jdbc.update("DELETE FROM org_node_change_log WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM org_student WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM org_teacher WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM sys_user_role WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM org_node WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM sys_oper_log WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE 'it07\\_%'");
        jdbc.update("DELETE FROM sys_tenant WHERE id = ?", TENANT_ID);
    }

    private long[] allNodes() {
        long[] all = new long[5 + STUDENT_COUNT];
        all[0] = ROOT;
        all[1] = A1;
        all[2] = A2;
        all[3] = T1;
        all[4] = T2;
        System.arraycopy(STUDENTS, 0, all, 5, STUDENT_COUNT);
        return all;
    }

    // =====================================================================
    // 播种
    // =====================================================================

    private void node(long id, Long parentId, String ancestors, String name, int nodeType,
                      int childCount, int studentCount) {
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, name, nodeType, userIdOf(id),
                childCount, studentCount, TENANT_ID);
        account(id, name, nodeType);
    }

    private void student(long id, long parentId, String parentSelfPrefix, String name, String no) {
        node(id, parentId, parentSelfPrefix, name, 3, 0, 0);
        jdbc.update("INSERT INTO org_student (id, node_id, user_id, student_no, guardian_name, "
                        + "guardian_phone, status, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, NOW(), NOW(), 0)",
                id + 1000000L, id, userIdOf(id), no, "家长" + name,
                phoneOf(id), TENANT_ID);
    }

    private void teacher(long nodeId, String teacherNo, int studentCount) {
        jdbc.update("INSERT INTO org_teacher (id, node_id, user_id, teacher_no, subject, title, "
                        + "entry_date, student_count, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, '数学', '一级教师', '2025-01-01', ?, ?, NOW(), NOW(), 0)",
                nodeId + 1000000L, nodeId, userIdOf(nodeId), teacherNo, studentCount, TENANT_ID);
    }

    private void account(long nodeId, String realName, int userType) {
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, phone, "
                        + "node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(), NOW(), 0)",
                userIdOf(nodeId), usernameOf(nodeId), encodedPassword, userType, realName,
                phoneOf(nodeId), nodeId, TENANT_ID);
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                userIdOf(nodeId) + 500000L, userIdOf(nodeId), roleIdOf(userType), TENANT_ID);
    }

    /** 四个内置角色的 id（Flyway 基线，{@code tenant_id = 0} 平台级行）。 */
    private static long roleIdOf(int userType) {
        return switch (userType) {
            case 1 -> 1953827104412590202L;  // org_admin
            case 2 -> 1953827104412590203L;  // teacher
            case 3 -> 1953827104412590204L;  // student
            default -> 1953827104412590201L; // super_admin
        };
    }

    public static long userIdOf(long nodeId) {
        return nodeId + USER_OFFSET;
    }

    public static String usernameOf(long nodeId) {
        return USERNAME_PREFIX + nodeId;
    }

    /** 手机号必须 11 位且本租户内唯一 —— 用节点 id 末 7 位拼。 */
    public static String phoneOf(long nodeId) {
        return "170" + String.format("%08d", nodeId % 100000000L);
    }

    // =====================================================================
    // 断言用的读取
    // =====================================================================

    public String ancestorsOf(long nodeId) {
        return jdbc.queryForObject("SELECT ancestors FROM org_node WHERE id = ?",
                String.class, nodeId);
    }

    public Long parentOf(long nodeId) {
        return jdbc.queryForObject("SELECT parent_id FROM org_node WHERE id = ?",
                Long.class, nodeId);
    }

    public int childCountOf(long nodeId) {
        return intOr0("SELECT child_count FROM org_node WHERE id = ?", nodeId);
    }

    public int studentCountOf(long nodeId) {
        return intOr0("SELECT student_count FROM org_node WHERE id = ?", nodeId);
    }

    public int teacherStudentCountOf(long nodeId) {
        return intOr0("SELECT student_count FROM org_teacher WHERE node_id = ? AND deleted_at = 0",
                nodeId);
    }

    public int changeLogCount(long nodeId, int changeType) {
        return intOr0("SELECT COUNT(1) FROM org_node_change_log WHERE node_id = ? "
                + "AND change_type = ? AND deleted_at = 0", nodeId, changeType);
    }

    public int changeLogCount(long nodeId) {
        return intOr0("SELECT COUNT(1) FROM org_node_change_log WHERE node_id = ? "
                + "AND deleted_at = 0", nodeId);
    }

    public int nodeCountInTenant() {
        return intOr0("SELECT COUNT(1) FROM org_node WHERE tenant_id = ? AND deleted_at = 0",
                TENANT_ID);
    }

    public int userCountInTenant() {
        return intOr0("SELECT COUNT(1) FROM sys_user WHERE tenant_id = ? AND deleted_at = 0",
                TENANT_ID);
    }

    public int studentProfileCountInTenant() {
        return intOr0("SELECT COUNT(1) FROM org_student WHERE tenant_id = ? AND deleted_at = 0",
                TENANT_ID);
    }

    public int operLogCount(String action) {
        return intOr0("SELECT COUNT(1) FROM sys_oper_log WHERE tenant_id = ? AND action = ?",
                TENANT_ID, action);
    }

    public int operLogTotal() {
        return intOr0("SELECT COUNT(1) FROM sys_oper_log WHERE tenant_id = ?", TENANT_ID);
    }

    public int loginLogTotal() {
        return intOr0("SELECT COUNT(1) FROM sys_login_log WHERE username LIKE 'it07\\_%'");
    }

    /** 直接造一条登录日志，用来验「脱敏不碰两张日志表」。 */
    public void seedLoginLog(long nodeId) {
        jdbc.update("INSERT INTO sys_login_log (id, user_id, username, ip, login_time, status, "
                        + "msg, tenant_id, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, '127.0.0.1', NOW(), 0, '登录成功', ?, NOW(), 0)",
                nodeId + 9000000L, userIdOf(nodeId), usernameOf(nodeId), TENANT_ID);
    }

    public String studentGuardianName(long studentId) {
        return jdbc.queryForObject("SELECT guardian_name FROM org_student WHERE id = ?",
                String.class, studentId);
    }

    public String studentGuardianPhone(long studentId) {
        return jdbc.queryForObject("SELECT guardian_phone FROM org_student WHERE id = ?",
                String.class, studentId);
    }

    public String studentAnonymizedAt(long studentId) {
        return jdbc.query("SELECT anonymized_at FROM org_student WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, studentId);
    }

    public Integer studentStatus(long studentId) {
        return jdbc.queryForObject("SELECT status FROM org_student WHERE id = ?",
                Integer.class, studentId);
    }

    public Integer studentArchiveReason(long studentId) {
        return jdbc.query("SELECT archive_reason FROM org_student WHERE id = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, studentId);
    }

    public String userRealName(long nodeId) {
        return jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id = ?",
                String.class, userIdOf(nodeId));
    }

    public String userPhone(long nodeId) {
        return jdbc.queryForObject("SELECT phone FROM sys_user WHERE id = ?",
                String.class, userIdOf(nodeId));
    }

    /** 档案 id：本夹具里恒为 {@code nodeId + 1000000}。 */
    public static long profileIdOf(long nodeId) {
        return nodeId + 1000000L;
    }

    /** 把某学员改成「归档满 N 日」，用于脱敏任务的两条对照路。 */
    public void archiveDaysAgo(long studentProfileId, int archiveReason, int daysAgo) {
        jdbc.update("UPDATE org_student SET status = 2, archive_reason = ?, "
                        + "archive_time = DATE_SUB(NOW(), INTERVAL ? DAY) WHERE id = ?",
                archiveReason, daysAgo, studentProfileId);
    }

    /**
     * 批量播 {@code count} 名在读学员挂在 {@code teacherNodeId} 下，返回它们的<b>档案 id</b>。
     *
     * <p>只服务 {@code BatchAssignCostIT} 的 500 人实测 —— 用接口逐个建 500 个人
     * 要跑几十秒，而本用例要测的是<b>批量分配</b>的代价，不是建人的代价。
     *
     * <p>{@code batchUpdate} 一次提交，不逐行往返。
     */
    public java.util.List<Long> seedStudents(long teacherNodeId, int count) {
        String parentAncestors = ancestorsOf(teacherNodeId) + "," + teacherNodeId;
        java.util.List<Long> profileIds = new java.util.ArrayList<>(count);
        java.util.List<Object[]> nodes = new java.util.ArrayList<>(count);
        java.util.List<Object[]> users = new java.util.ArrayList<>(count);
        java.util.List<Object[]> profiles = new java.util.ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            long nodeId = S_BASE + 1000L + i;
            long userId = userIdOf(nodeId);
            long profileId = profileIdOf(nodeId);
            profileIds.add(profileId);
            nodes.add(new Object[]{nodeId, teacherNodeId, parentAncestors,
                    "压测学员" + i, 3, userId, TENANT_ID});
            users.add(new Object[]{userId, usernameOf(nodeId), encodedPassword, 3,
                    "压测学员" + i, phoneOf(nodeId), nodeId, TENANT_ID});
            profiles.add(new Object[]{profileId, nodeId, userId,
                    "SB" + String.format("%05d", i), TENANT_ID});
        }

        jdbc.batchUpdate("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                + "create_time, update_time, deleted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)", nodes);
        jdbc.batchUpdate("INSERT INTO sys_user (id, username, password, user_type, real_name, "
                + "phone, node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, "
                + "deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(), NOW(), 0)", users);
        jdbc.batchUpdate("INSERT INTO org_student (id, node_id, user_id, student_no, status, "
                + "tenant_id, create_time, update_time, deleted_at) "
                + "VALUES (?, ?, ?, ?, 0, ?, NOW(), NOW(), 0)", profiles);

        // 冗余计数按真实值播种：断言的才是「增量维护对不对」，不是「从 0 加了几」
        jdbc.update("UPDATE org_node SET child_count = child_count + ?, "
                + "student_count = student_count + ? WHERE id = ?", count, count, teacherNodeId);
        jdbc.update("UPDATE org_node SET student_count = student_count + ? WHERE id IN (?, ?)",
                count, A1, ROOT);
        jdbc.update("UPDATE org_teacher SET student_count = student_count + ? WHERE node_id = ?",
                count, teacherNodeId);
        return profileIds;
    }

    private int intOr0(String sql, Object... args) {
        Integer v = jdbc.queryForObject(sql, Integer.class, args);
        return v == null ? 0 : v;
    }
}
