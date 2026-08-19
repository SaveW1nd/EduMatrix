package com.edumatrix.question.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 模块 10 验收用的夹具。
 *
 * <h2>为什么另起一棵树，而不是复用模块 08 的</h2>
 * <p>与模块 08 另起一棵的理由同构：模块 08 的树被十几个类按形状断言着，
 * 而本模块要往上面挂题目、分类与<b>题目授权行</b>（{@code resource_type=2}），
 * 共用会让两边互相牵制。两棵树的租户 ID 不同，各自 {@code clean()} 各自的。
 *
 * <h2>树形</h2>
 * <pre>
 * 租户 T1：
 *   ROOT(1) 机构最高管理员      ← 多数用例的操作人；自有题 Q_SINGLE / Q_MULTI / ... / Q_MATERIAL
 *    ├─ A1(1) 华东大区（下级管理员）
 *    │   └─ TA(2) 教师王        ← 自有题 Q_TA：验「上级看不到下级教师自建的题」
 *    └─ TB(2) 教师李            ← 无自有题，验【被授权】路径（含材料题父题授权连带子题）
 * 租户 T2：
 *   ROOT2(1) 另一个机构         ← 自有题 Q_OTHER：验跨租户 404
 * </pre>
 *
 * <p><b>ROOT 与 TA 的关系是本模块最要紧的一组</b>：TA 在 ROOT 的子树内，
 * 但按 03-04 §0.1 的题目可见性（精确等于我的节点 ∪ 被显式授权给我的节点），
 * <b>ROOT 看不到 Q_TA</b>。这不是额外收紧，是「不回溯祖先链、无继承」的必然结果。
 */
public final class QuestionFixtures {

    public static final long TENANT_ID = 1969000000000000001L;
    public static final long TENANT2_ID = 1969000000000000002L;

    public static final long ROOT = TENANT_ID;
    public static final long A1 = 1969000000000000010L;
    public static final long TA = 1969000000000000020L;
    public static final long TB = 1969000000000000021L;
    public static final long ROOT2 = TENANT2_ID;

    // ---------------------------------------------------------------- 分类
    /** 顶级分类「数学」。 */
    public static final long CAT_MATH = 1969000000000001001L;
    /** 「数学」下的「代数」——用于「分类下有子分类不可删」。 */
    public static final long CAT_ALGEBRA = 1969000000000001002L;
    /** 空分类，可删。 */
    public static final long CAT_EMPTY = 1969000000000001003L;
    /** 租户 2 的分类 —— 验跨租户 404。 */
    public static final long CAT_OTHER = 1969000000000001004L;

    // ---------------------------------------------------------------- 题目
    /** ROOT 自有单选题（启用）。 */
    public static final long Q_SINGLE = 1969000000000002001L;
    /** TA 自有多选题 —— 验「ROOT 看不到下级教师自建的题」。 */
    public static final long Q_TA = 1969000000000002002L;
    /** 租户 2 的题 —— 验跨租户 404。 */
    public static final long Q_OTHER = 1969000000000002003L;
    /** ROOT 自有材料题父题。 */
    public static final long Q_MATERIAL = 1969000000000002010L;
    /** 材料题子题 1（单选）。 */
    public static final long Q_CHILD_1 = 1969000000000002011L;
    /** 材料题子题 2（简答）。 */
    public static final long Q_CHILD_2 = 1969000000000002012L;

    public static final long USER_OFFSET = 100000L;
    public static final String USERNAME_PREFIX = "it10_";
    public static final String PASSWORD = "Test@123456";

    private final JdbcTemplate jdbc;
    private final String encodedPassword;

    public QuestionFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.encodedPassword = new BCryptPasswordEncoder(10).encode(PASSWORD);
    }

    public void seed() {
        clean();

        tenant(TENANT_ID, ROOT, "IT10 题库机构");
        tenant(TENANT2_ID, ROOT2, "IT10 另一个机构");

        node(ROOT, 0L, "0", "IT10 题库机构", 1, TENANT_ID);
        node(A1, ROOT, "0," + ROOT, "华东大区", 1, TENANT_ID);
        node(TA, A1, "0," + ROOT + "," + A1, "教师王", 2, TENANT_ID);
        node(TB, ROOT, "0," + ROOT, "教师李", 2, TENANT_ID);
        node(ROOT2, 0L, "0", "IT10 另一个机构", 1, TENANT2_ID);

        category(CAT_MATH, 0L, "数学", 1, TENANT_ID);
        category(CAT_ALGEBRA, CAT_MATH, "代数", 1, TENANT_ID);
        category(CAT_EMPTY, 0L, "空分类", 2, TENANT_ID);
        category(CAT_OTHER, 0L, "另一个机构的分类", 1, TENANT2_ID);

        question(Q_SINGLE, ROOT, CAT_ALGEBRA, 1, 0L, 1, TENANT_ID);
        version(Q_SINGLE, 1, singleContent("方程 x²-3x+2=0 的根是（　）"),
                "{\"answer\":\"A\"}", "因式分解", "5.00", TENANT_ID);

        question(Q_TA, TA, CAT_ALGEBRA, 2, 0L, 1, TENANT_ID);
        version(Q_TA, 1, multiContent("下列说法正确的有（　）"),
                "{\"answer\":[\"A\",\"B\"]}", null, "6.00", TENANT_ID);

        question(Q_OTHER, ROOT2, CAT_OTHER, 1, 0L, 1, TENANT2_ID);
        version(Q_OTHER, 1, singleContent("另一个机构的题"), "{\"answer\":\"A\"}",
                null, "5.00", TENANT2_ID);

        question(Q_MATERIAL, ROOT, CAT_MATH, 6, 0L, 1, TENANT_ID);
        version(Q_MATERIAL, 1,
                "{\"stem\":\"阅读材料，回答问题\",\"childOrder\":[\"" + Q_CHILD_1 + "\",\""
                        + Q_CHILD_2 + "\"]}",
                null, null, "10.00", TENANT_ID);
        question(Q_CHILD_1, ROOT, CAT_MATH, 1, Q_MATERIAL, 1, TENANT_ID);
        version(Q_CHILD_1, 1, singleContent("子题一（　）"), "{\"answer\":\"B\"}",
                null, "4.00", TENANT_ID);
        question(Q_CHILD_2, ROOT, CAT_MATH, 5, Q_MATERIAL, 1, TENANT_ID);
        version(Q_CHILD_2, 1, "{\"stem\":\"子题二，简答\"}", "{\"text\":\"参考答案\"}",
                null, "6.00", TENANT_ID);
    }

    public void clean() {
        for (long tenant : new long[]{TENANT_ID, TENANT2_ID}) {
            jdbc.update("DELETE FROM hw_homework_question WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM hw_homework WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM qb_question_version WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM qb_question WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM qb_category WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM org_resource_grant WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_oper_log WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_user_role WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM org_node WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id = ?", tenant);
        }
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE 'it10\\_%'");
    }

    // ================================================================ 播种

    private void tenant(long id, long rootNodeId, String name) {
        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                        + "max_student_count, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, 0, 500, NOW(), NOW(), 0)",
                id, rootNodeId, name);
    }

    private void node(long id, Long parentId, String ancestors, String name, int nodeType,
                      long tenantId) {
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, name, nodeType, userIdOf(id), tenantId);
        account(id, name, nodeType, tenantId);
    }

    private void account(long nodeId, String realName, int userType, long tenantId) {
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, phone, "
                        + "node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, "
                        + "deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(), NOW(), 0)",
                userIdOf(nodeId), usernameOf(nodeId), encodedPassword, userType, realName,
                phoneOf(nodeId), nodeId, tenantId);
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                userIdOf(nodeId) + 500000L, userIdOf(nodeId), roleIdOf(userType), tenantId);
    }

    public void category(long id, long parentId, String name, int sort, long tenantId) {
        jdbc.update("INSERT INTO qb_category (id, parent_id, category_name, sort, tenant_id, "
                        + "create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                id, parentId, name, sort, tenantId, userIdOf(ROOT));
    }

    public void question(long id, long ownerNodeId, long categoryId, int questionType,
                         long parentId, int status, long tenantId) {
        jdbc.update("INSERT INTO qb_question (id, owner_node_id, category_id, question_type, "
                        + "parent_id, difficulty, current_version, stem_preview, status, tenant_id, "
                        + "create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, 3, 1, ?, ?, ?, ?, NOW(), NOW(), 0)",
                id, ownerNodeId, categoryId, questionType, parentId, "题目 " + id, status,
                tenantId, userIdOf(ownerNodeId));
    }

    public void version(long questionId, int version, String content, String correctAnswer,
                        String analysis, String scoreDefault, long tenantId) {
        jdbc.update("INSERT INTO qb_question_version (id, question_id, version, content, "
                        + "correct_answer, analysis, score_default, tenant_id, create_by, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                questionId * 10 + version, questionId, version, content, correctAnswer,
                analysis, scoreDefault, tenantId, userIdOf(ROOT));
    }

    /**
     * 手动插一条题目授权行（{@code resource_type=2}）。
     *
     * <p>模块 11 之前没有授权接口，「被授权」这条路径只能这么造 ——
     * 而它恰恰是本模块可见性判定的<b>另一半</b>，不造就等于只测了一半。
     */
    public void grantQuestion(long questionId, long targetNodeId, long tenantId) {
        jdbc.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, target_node_id, "
                        + "valid_start, valid_end, grant_source, grant_by, grant_time, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, 2, ?, ?, NULL, NULL, 1, ?, NOW(), ?, NOW(), NOW(), 0)",
                questionId + targetNodeId, questionId, targetNodeId, userIdOf(ROOT), tenantId);
    }

    /** 已过期的授权行 —— 验「授权过期等同未授权」。 */
    public void grantQuestionExpired(long questionId, long targetNodeId, long tenantId) {
        jdbc.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, target_node_id, "
                        + "valid_start, valid_end, grant_source, grant_by, grant_time, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, 2, ?, ?, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), 1, ?, NOW(), ?, "
                        + "NOW(), NOW(), 0)",
                questionId + targetNodeId + 1, questionId, targetNodeId, userIdOf(ROOT), tenantId);
    }

    /** 作业 + 选题行 —— 造出「题目已被作业引用」（30001 / 30005）的前置状态。 */
    public void homeworkReferencing(long homeworkId, long questionId, long ownerNodeId,
                                    long tenantId, long homeworkDeletedAt, long refDeletedAt) {
        jdbc.update("INSERT INTO hw_homework (id, homework_name, homework_type, owner_node_id, "
                        + "course_id, total_score, question_count, deadline, publish_time, "
                        + "allow_late_submit, answer_visible_type, status, tenant_id, create_by, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, 1, ?, NULL, 10.00, 1, NULL, NULL, 0, 1, 1, ?, ?, "
                        + "NOW(), NOW(), ?)",
                homeworkId, "IT10 作业" + homeworkId, ownerNodeId, tenantId, userIdOf(ROOT),
                homeworkDeletedAt);
        jdbc.update("INSERT INTO hw_homework_question (id, homework_id, question_id, "
                        + "question_version, score, sort, tenant_id, create_by, create_time, "
                        + "update_time, deleted_at) VALUES (?, ?, ?, 1, 10.00, 1, ?, ?, NOW(), NOW(), ?)",
                homeworkId + questionId, homeworkId, questionId, tenantId, userIdOf(ROOT),
                refDeletedAt);
    }

    private static String singleContent(String stem) {
        return "{\"stem\":\"" + stem + "\",\"options\":["
                + "{\"key\":\"A\",\"text\":\"选项A\"},{\"key\":\"B\",\"text\":\"选项B\"},"
                + "{\"key\":\"C\",\"text\":\"选项C\"},{\"key\":\"D\",\"text\":\"选项D\"}]}";
    }

    private static String multiContent(String stem) {
        return singleContent(stem);
    }

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

    /** 手机号须 11 位且本租户内唯一。 */
    public static String phoneOf(long nodeId) {
        return "172" + String.format("%08d", nodeId % 100000000L);
    }
}
