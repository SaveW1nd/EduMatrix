package com.edumatrix.org.grant.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.edumatrix.auth.support.AuthFixtures;

/**
 * 模块 11 集成测试夹具 —— 一棵<b>四级授权链</b>加两类平级分支，外加三类受管资源各若干。
 *
 * <pre>
 * ROOT(管理员, 机构根)                       ← PRD FR-4 验收标准里的「甲」
 *  ├─ A1(下级管理员)                         ← 「乙」
 *  │   └─ T1(教师)                           ← 「丙」
 *  │       └─ S1..S8(学生 8 名)              ← 「8 名学员」
 *  └─ A2(下级管理员，与 A1 平级)
 *      └─ T2(教师)
 *          └─ S9(学生)
 * ROOT2(另一个租户)
 * </pre>
 *
 * <p>这棵树是照 <b>PRD FR-2 的四级下发链</b>（N0→N1→N2→N3）与
 * <b>FR-4 的级联验收标准</b>（甲撤销乙 → 乙/丙/8 名学员共 10 行同事务撤销）搭的，
 * 不是随便挑的形状 —— 那两条验收标准要能<b>原样复现</b>。
 * A2 分支用于「平级不可互授」（{@code 10302}）与「上级拥有但未授予下级」的探测用例。
 *
 * <h2>⚠ 主键值域（{@code check_backend_conventions.sh} 检查⑧）</h2>
 * <p>租户前缀 <b>1971</b>，全库未被占用。派生规则与 Course / Member / Question 三家相同
 *（{@code userIdOf(nodeId) + 500000L}）—— <b>偏移量撞车无所谓，租户前缀撞车才致命</b>
 *（那段注释就在脚本里）。本夹具占用：
 * <pre>
 *   org_node        1971000000000000001 .. 1971000000000000038
 *   sys_user        1971000000000100001 .. 1971000000000100038
 *   sys_user_role   1971000000000600001 .. 1971000000000600038
 * </pre>
 * 与已登记的 1960 / 1962 / 1967 / 1968 / 1969 五家两两相距 ≥ 1e15，而最大偏移量只有 6e5。
 */
public final class GrantFixtures {

    /** 测试租户 1（契约 §2.1：机构根节点 id 即 tenant_id）。 */
    public static final long TENANT_ID = 1971000000000000001L;
    /** 测试租户 2 —— 跨租户隔离用。 */
    public static final long TENANT2_ID = 1971000000000000002L;

    /** 甲：机构根管理员。 */
    public static final long ROOT = TENANT_ID;
    /** 乙：下级管理员。 */
    public static final long A1 = 1971000000000000010L;
    /** 与 A1 平级的下级管理员 —— 「平级不可互授」用它。 */
    public static final long A2 = 1971000000000000011L;
    /** 丙：A1 名下的教师。 */
    public static final long T1 = 1971000000000000020L;
    /** A2 名下的教师。 */
    public static final long T2 = 1971000000000000021L;
    /** T1 名下的 8 名学员（FR-4 验收标准逐字）。 */
    public static final long[] S = {
            1971000000000000030L, 1971000000000000031L, 1971000000000000032L,
            1971000000000000033L, 1971000000000000034L, 1971000000000000035L,
            1971000000000000036L, 1971000000000000037L};
    /** T2 名下的学员。 */
    public static final long S9 = 1971000000000000038L;
    public static final long ROOT2 = TENANT2_ID;

    public static final long[] ALL_NODES = {
            ROOT, A1, A2, T1, T2, S[0], S[1], S[2], S[3], S[4], S[5], S[6], S[7], S9, ROOT2};

    /** 课程：ROOT 自有 —— 四级下发链的主角。 */
    public static final long C1 = 1971000000000001001L;
    /** 课程：A1 自有（下级自建，ROOT 看不到）。 */
    public static final long C2 = 1971000000000001002L;
    /** 课程：ROOT 自有但<b>从未授出</b> —— 「未授予的资源按不存在处理」用它。 */
    public static final long C3 = 1971000000000001003L;
    /** 课程：另一个租户。 */
    public static final long C_OTHER = 1971000000000001009L;

    /** 题目：ROOT 自有（{@code resource_type = 2}）。 */
    public static final long Q1 = 1971000000000002001L;
    /** 题库分类。 */
    public static final long CAT = 1971000000000002101L;

    /** 视频：ROOT 自有（{@code resource_type = 3}）。 */
    public static final long V1 = 1971000000000003001L;

    public static final long USER_OFFSET = 100000L;
    public static final String USERNAME_PREFIX = "it11_";
    public static final String PASSWORD = "Test@123456";

    /** 授权行主键的起始值 —— 仍在 1971 前缀内，与节点/资源号段不重叠。 */
    private static final long GRANT_ID_BASE = 1971000000000900001L;

    private final JdbcTemplate jdbc;
    private final String encodedPassword;
    private long grantSeq;

    public GrantFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.encodedPassword = new BCryptPasswordEncoder(10).encode(PASSWORD);
    }

    public void seed() {
        clean();

        tenant(TENANT_ID, ROOT, "IT11 授权引擎机构");
        tenant(TENANT2_ID, ROOT2, "IT11 另一个机构");

        String rootAnc = "0";
        String lvl2 = "0," + ROOT;
        String underA1 = lvl2 + "," + A1;
        String underA2 = lvl2 + "," + A2;
        String underT1 = underA1 + "," + T1;
        String underT2 = underA2 + "," + T2;

        node(ROOT, 0L, rootAnc, "IT11 授权引擎机构", 1, TENANT_ID);
        node(A1, ROOT, lvl2, "华东大区", 1, TENANT_ID);
        node(A2, ROOT, lvl2, "华南大区", 1, TENANT_ID);
        node(T1, A1, underA1, "教师王", 2, TENANT_ID);
        node(T2, A2, underA2, "教师李", 2, TENANT_ID);
        for (int i = 0; i < S.length; i++) {
            student(S[i], T1, underT1, "学员" + (i + 1));
        }
        student(S9, T2, underT2, "学员九");
        node(ROOT2, 0L, rootAnc, "IT11 另一个机构", 1, TENANT2_ID);

        teacher(T1, S.length);
        teacher(T2, 1);

        course(C1, "高三数学·函数与导数", ROOT, TENANT_ID);
        course(C2, "华东自研·立体几何", A1, TENANT_ID);
        course(C3, "从未授出的课程", ROOT, TENANT_ID);
        course(C_OTHER, "另一个机构的课程", ROOT2, TENANT2_ID);

        category(CAT, "数学", TENANT_ID);
        question(Q1, ROOT, CAT, TENANT_ID);

        video(V1, "函数与导数·第一讲", ROOT, TENANT_ID);
    }

    public void clean() {
        for (long tenant : new long[]{TENANT_ID, TENANT2_ID}) {
            jdbc.update("DELETE FROM org_resource_grant WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM crs_course WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM qb_question WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM qb_category WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM vod_video WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM org_perm_template WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM org_tag WHERE tenant_id = ?", tenant);
        }
        for (long nodeId : ALL_NODES) {
            long userId = userIdOf(nodeId);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
            jdbc.update("DELETE FROM org_student WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_teacher WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node_change_log WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node WHERE id = ?", nodeId);
        }
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE 'it11\\_%'");
        jdbc.update("DELETE FROM sys_oper_log WHERE tenant_id IN (?, ?)", TENANT_ID, TENANT2_ID);
        jdbc.update("DELETE FROM sys_tenant WHERE id IN (?, ?)", TENANT_ID, TENANT2_ID);
    }

    // ================================================================ 造数

    /** 插一条有效授权行（永久有效）。 */
    public void grant(int resourceType, long resourceId, long targetNodeId, long granterNodeId) {
        grant(resourceType, resourceId, targetNodeId, granterNodeId, null, null);
    }

    /** 插一条授权行，有效期由调用方给（{@code null} = 不限）。 */
    public void grant(int resourceType, long resourceId, long targetNodeId, long granterNodeId,
                      String validStart, String validEnd) {
        jdbc.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, target_node_id, "
                        + "valid_start, valid_end, grant_source, source_ref_id, grant_by, grant_time, "
                        + "tenant_id, create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, NULL, ?, NOW(), ?, ?, NOW(), NOW(), 0)",
                nextGrantId(), resourceType, resourceId,
                targetNodeId, validStart, validEnd, userIdOf(granterNodeId), TENANT_ID,
                userIdOf(granterNodeId));
    }

    /**
     * 授权行主键：<b>单调发号</b>，不从业务键推导。
     *
     * <p>「{@code resourceId + targetNodeId % 1000 * 1000 + type}」这类推导看着方便，
     * 但它是<b>哈希不是唯一键</b>：只要两个资源 ID 之差恰好等于两个节点项之差就撞，
     * 而撞了的表现是插入时 {@code Duplicate entry} ——
     * <b>只在特定夹具组合下才出现</b>，单跑复现不了。用例不需要知道授权行的 ID
     *（要断言「某行在不在」用 {@link #activeGrantCount(int, long, long)}），
     * 所以没有任何理由为了可推导而冒这个险。
     */
    private long nextGrantId() {
        return GRANT_ID_BASE + grantSeq++;
    }

    public int activeGrantCount(int resourceType, long resourceId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM org_resource_grant "
                        + "WHERE resource_type = ? AND resource_id = ? AND deleted_at = 0",
                Integer.class, resourceType, resourceId);
        return n == null ? 0 : n;
    }

    public int activeGrantCount(int resourceType, long resourceId, long targetNodeId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM org_resource_grant "
                        + "WHERE resource_type = ? AND resource_id = ? AND target_node_id = ? "
                        + "AND deleted_at = 0",
                Integer.class, resourceType, resourceId, targetNodeId);
        return n == null ? 0 : n;
    }

    public static long userIdOf(long nodeId) {
        return nodeId + USER_OFFSET;
    }

    public static String usernameOf(long nodeId) {
        return USERNAME_PREFIX + nodeId;
    }

    // ================================================================ 私有

    private void tenant(long tenantId, long rootNodeId, String name) {
        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                        + "max_student_count, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, 0, 500, NOW(), NOW(), 0)",
                tenantId, rootNodeId, name);
    }

    private void node(long id, long parentId, String ancestors, String name, int nodeType,
                      long tenantId) {
        long userId = userIdOf(id);
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, name, nodeType, userId, tenantId);
        // phone 留 NULL：uk_tenant_phone 不约束 NULL，夹具因此不会与示例数据撞唯一键
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, phone, "
                        + "node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, "
                        + "deleted_at) VALUES (?, ?, ?, ?, ?, NULL, ?, 0, 0, ?, NOW(), NOW(), 0)",
                userId, usernameOf(id), encodedPassword, nodeType, name, id, tenantId);
        // 【偏移量写字面量 500000L，且注释必须待在这一行【之前】】
        // check_backend_conventions.sh 检查⑧ 是 `grep -A 4 "INSERT INTO sys_user_role"`
        // 再从中抠 `+ 数字L`：① 抽成具名常量它解析不出来；② 把注释塞进 INSERT 与参数之间，
        // 会把那一行挤出 4 行窗口 —— 两种都只会让它报「未能解析出偏移量」。
        // 守卫的用途正是把这个数字摆在明处，任何让它看不见的写法都恰好抵消了它。
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                userId + 500000L, userId, roleOf(nodeType), tenantId);
    }

    private void student(long id, long parentId, String parentAncestors, String name) {
        node(id, parentId, parentAncestors + "," + parentId, name, 3, TENANT_ID);
        jdbc.update("INSERT INTO org_student (id, node_id, user_id, status, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, 0, ?, NOW(), NOW(), 0)",
                id + 500, id, userIdOf(id), TENANT_ID);
    }

    private void teacher(long nodeId, int studentCount) {
        jdbc.update("INSERT INTO org_teacher (id, node_id, user_id, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                nodeId + 700, nodeId, userIdOf(nodeId), studentCount, TENANT_ID);
    }

    private void course(long id, String name, long ownerNodeId, long tenantId) {
        jdbc.update("INSERT INTO crs_course (id, course_name, owner_node_id, cover_file_id, subject, "
                        + "description, status, lesson_count, total_duration, tenant_id, create_by, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, '数学', '简介', 1, 0, 0, ?, ?, NOW(), NOW(), 0)",
                id, name, ownerNodeId, tenantId, userIdOf(ownerNodeId));
    }

    private void category(long id, String name, long tenantId) {
        jdbc.update("INSERT INTO qb_category (id, parent_id, category_name, sort, tenant_id, "
                        + "create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, 0, ?, 1, ?, ?, NOW(), NOW(), 0)",
                id, name, tenantId, userIdOf(ROOT));
    }

    private void question(long id, long ownerNodeId, long categoryId, long tenantId) {
        jdbc.update("INSERT INTO qb_question (id, owner_node_id, category_id, question_type, "
                        + "parent_id, difficulty, current_version, stem_preview, status, tenant_id, "
                        + "create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, 1, 0, 3, 1, ?, 1, ?, ?, NOW(), NOW(), 0)",
                id, ownerNodeId, categoryId, "函数题 " + id, tenantId, userIdOf(ownerNodeId));
    }

    private void video(long id, String name, long ownerNodeId, long tenantId) {
        jdbc.update("INSERT INTO vod_video (id, owner_node_id, provider, encrypt_type, vod_file_id, "
                        + "video_name, duration, status, upload_user_id, tenant_id, create_time, "
                        + "update_time, deleted_at) "
                        + "VALUES (?, ?, 2, 1, ?, ?, 600, 2, ?, ?, NOW(), NOW(), 0)",
                id, ownerNodeId, "vod-it11-" + id, name, userIdOf(ownerNodeId), tenantId);
    }

    private static long roleOf(int nodeType) {
        return switch (nodeType) {
            case 2 -> AuthFixtures.ROLE_TEACHER;
            case 3 -> AuthFixtures.ROLE_STUDENT;
            default -> AuthFixtures.ROLE_ORG_ADMIN;
        };
    }
}
