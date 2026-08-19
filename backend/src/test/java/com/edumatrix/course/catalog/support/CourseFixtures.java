package com.edumatrix.course.catalog.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 模块 08 验收用的夹具。
 *
 * <h2>为什么另起两个租户，而不是复用模块 06 / 07 的树</h2>
 * <p>与模块 07 另起一棵树的理由同构：那两棵树被十几个类按形状断言着
 * （{@code affectedNodeCount} 必须是 13、{@code childCount} 必须是几），
 * 往上面挂课程虽然不改节点，但本模块会<b>建人以外的东西</b>并按子树数条数，
 * 共用会让两边互相牵制。
 *
 * <h2>树形</h2>
 * <pre>
 * 租户 T1：
 *   ROOT(1) 机构最高管理员          ← 绝大多数用例的操作人，自有课程 C_ROOT
 *    ├─ A1(1) 华东大区（下级管理员）
 *    │   └─ TA(2) 教师王            ← 自有课程 C_TA：验「上级看不到下级教师自建的课」
 *    └─ TB(2) 教师李                ← 无课程，验被授权路径
 * 租户 T2：
 *   ROOT2(1) 另一个机构             ← 自有课程 C_OTHER：验跨租户 404
 * </pre>
 *
 * <p><b>ROOT 与 TA 的关系是本模块最要紧的一组</b>：TA 在 ROOT 的子树内，
 * 但按 03-03 §0.2 的资源可见性（精确等于我的节点 ∪ 被显式授权给我的节点），
 * <b>ROOT 看不到 C_TA</b>。这条不是本模块的额外收紧，是那两条判定取交集的必然结果。
 *
 * <h2>{@code sys_file} 行的 {@code file_url} 是对象键，不是地址</h2>
 * <p>播种时刻意用一个<b>可辨识的键</b>（{@link #COVER_OBJECT_KEY}），
 * 于是「有没有人把这一列读出来直接下发」可以用一句字符串断言查出来。
 */
public final class CourseFixtures {

    /** 测试租户 1（契约 §2.1：机构根节点 id 即 tenant_id）。 */
    public static final long TENANT_ID = 1968000000000000001L;
    /** 测试租户 2 —— 跨租户隔离用。 */
    public static final long TENANT2_ID = 1968000000000000002L;

    public static final long ROOT = TENANT_ID;
    public static final long A1 = 1968000000000000010L;
    public static final long TA = 1968000000000000020L;
    public static final long TB = 1968000000000000021L;
    public static final long ROOT2 = TENANT2_ID;

    /** ROOT 自有课程（草稿）。 */
    public static final long C_ROOT = 1968000000000001001L;
    /** TA 自有课程 —— 验「ROOT 看不到下级教师自建的课」。 */
    public static final long C_TA = 1968000000000001002L;
    /** 租户 2 的课程 —— 验跨租户 404。 */
    public static final long C_OTHER = 1968000000000001003L;

    /** 课程封面文件（{@code biz_type = course_cover}）。 */
    public static final long COVER_FILE = 1968000000000002001L;
    /** 图文资料附件（{@code biz_type = material_attach}）。 */
    public static final long ATTACH_FILE = 1968000000000002002L;
    /** 图文正文内嵌图片（{@code biz_type = material_image}）。 */
    public static final long IMAGE_FILE = 1968000000000002003L;

    /**
     * 播种进 {@code sys_file.file_url} 的<b>对象键</b>。
     *
     * <p>它<b>不是</b>可访问地址（{@code SysFile} 类注释：该列只存对象键）。
     * 断言「响应里不含这个字符串」就是在证明没有任何接口把这一列读出来直接下发 ——
     * 那正是 {@code 00-通用约定} §7.4 第 1 行禁止的「长期有效的公开直链」。
     */
    public static final String COVER_OBJECT_KEY = "course_cover/2026/08/19/it08-cover-key.png";

    /** 媒资：转码完成，可挂课时、可上架。 */
    public static final long VIDEO_OK = 1968000000000003001L;
    /** 媒资：<b>转码中</b>（{@code status=1}）—— PRD F2-1 验收标准点名的那一个。 */
    public static final long VIDEO_TRANSCODING = 1968000000000003002L;
    /** 媒资：已逻辑删除。 */
    public static final long VIDEO_DELETED = 1968000000000003003L;

    public static final int VIDEO_OK_DURATION = 600;

    public static final long USER_OFFSET = 100000L;
    public static final String USERNAME_PREFIX = "it08_";
    public static final String PASSWORD = "Test@123456";

    private final JdbcTemplate jdbc;
    private final String encodedPassword;

    public CourseFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.encodedPassword = new BCryptPasswordEncoder(10).encode(PASSWORD);
    }

    public void seed() {
        clean();

        tenant(TENANT_ID, ROOT, "IT08 课程编排机构");
        tenant(TENANT2_ID, ROOT2, "IT08 另一个机构");

        String rootAnc = "0";
        node(ROOT, 0L, rootAnc, "IT08 课程编排机构", 1, TENANT_ID);
        node(A1, ROOT, "0," + ROOT, "华东大区", 1, TENANT_ID);
        node(TA, A1, "0," + ROOT + "," + A1, "教师王", 2, TENANT_ID);
        node(TB, ROOT, "0," + ROOT, "教师李", 2, TENANT_ID);
        node(ROOT2, 0L, rootAnc, "IT08 另一个机构", 1, TENANT2_ID);

        file(COVER_FILE, "封面.png", COVER_OBJECT_KEY, 2048L, "png", "course_cover", TENANT_ID);
        file(ATTACH_FILE, "讲义.pdf", "material_attach/2026/08/19/it08-attach.pdf",
                1048576L, "pdf", "material_attach", TENANT_ID);
        file(IMAGE_FILE, "插图.png", "material_image/2026/08/19/it08-image.png",
                4096L, "png", "material_image", TENANT_ID);

        video(VIDEO_OK, "已转码视频", 2, VIDEO_OK_DURATION, TENANT_ID, 0L);
        video(VIDEO_TRANSCODING, "转码中视频", 1, 0, TENANT_ID, 0L);
        video(VIDEO_DELETED, "已删除视频", 2, 100, TENANT_ID, 1755000000000L);

        course(C_ROOT, "ROOT 的课程", ROOT, TENANT_ID, 0);
        course(C_TA, "教师王的课程", TA, TENANT_ID, 0);
        course(C_OTHER, "另一个机构的课程", ROOT2, TENANT2_ID, 0);
    }

    public void clean() {
        for (long tenant : new long[]{TENANT_ID, TENANT2_ID}) {
            jdbc.update("DELETE FROM crs_lesson WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM crs_chapter WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM crs_material WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM crs_course WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM org_resource_grant WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM vod_video WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_file WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_oper_log WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_user_role WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM org_node WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM sys_tenant WHERE id = ?", tenant);
        }
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE 'it08\\_%'");
    }

    // =====================================================================
    // 播种
    // =====================================================================

    private void tenant(long id, long rootNodeId, String name) {
        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                        + "max_student_count, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, 0, 500, NOW(), NOW(), 0)",
                id, rootNodeId, name);
    }

    private void node(long id, Long parentId, String ancestors, String name, int nodeType, long tenantId) {
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, name, nodeType, userIdOf(id), tenantId);
        account(id, name, nodeType, tenantId);
    }

    private void account(long nodeId, String realName, int userType, long tenantId) {
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, phone, "
                        + "node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, ?, NOW(), NOW(), 0)",
                userIdOf(nodeId), usernameOf(nodeId), encodedPassword, userType, realName,
                phoneOf(nodeId), nodeId, tenantId);
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                userIdOf(nodeId) + 500000L, userIdOf(nodeId), roleIdOf(userType), tenantId);
    }

    private void file(long id, String fileName, String objectKey, long size, String type,
                      String bizType, long tenantId) {
        jdbc.update("INSERT INTO sys_file (id, file_name, file_url, file_size, file_type, storage, "
                        + "biz_type, tenant_id, create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, NOW(), NOW(), 0)",
                id, fileName, objectKey, size, type, bizType, tenantId, userIdOf(ROOT));
    }

    private void video(long id, String name, int status, int duration, long tenantId, long deletedAt) {
        jdbc.update("INSERT INTO vod_video (id, owner_node_id, provider, encrypt_type, vod_file_id, "
                        + "video_name, duration, status, upload_user_id, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, 2, 1, ?, ?, ?, ?, ?, ?, NOW(), NOW(), ?)",
                id, ROOT, "vod-it08-" + id, name, duration, status, userIdOf(ROOT), tenantId, deletedAt);
    }

    public void course(long id, String name, long ownerNodeId, long tenantId, int status) {
        jdbc.update("INSERT INTO crs_course (id, course_name, owner_node_id, cover_file_id, subject, "
                        + "description, status, lesson_count, total_duration, tenant_id, create_by, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, '数学', '简介', ?, 0, 0, ?, ?, NOW(), NOW(), 0)",
                id, name, ownerNodeId, tenantId == TENANT_ID ? COVER_FILE : null,
                status, tenantId, userIdOf(ownerNodeId));
    }

    /** 手动插一条有效授权行 —— 模块 11 之前没有授权接口，被授权路径只能这么造。 */
    public void grantCourse(long courseId, long targetNodeId, long tenantId) {
        jdbc.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, target_node_id, "
                        + "valid_start, valid_end, grant_source, grant_by, grant_time, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, 1, ?, ?, NULL, NULL, 1, ?, NOW(), ?, NOW(), NOW(), 0)",
                courseId + targetNodeId, courseId, targetNodeId, userIdOf(ROOT), tenantId);
    }

    /**
     * 直接插章节 —— 给<b>不测章节接口</b>的用例当容器用。
     *
     * <p>走 JDBC 而不是调接口，是为了让每个提交<b>独立可回滚</b>：课程用例
     * （接口 1~6）不该因为章节接口还没提交就跑不起来。测章节本身的
     * {@code ChapterIT} 一律走真实接口。
     */
    public void chapter(long id, long courseId, long parentId, String name, int sort, long tenantId) {
        jdbc.update("INSERT INTO crs_chapter (id, course_id, parent_id, chapter_name, sort, "
                        + "tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                id, courseId, parentId, name, sort, tenantId);
    }

    /** 直接插图文资料 —— 同上，给不测资料接口的用例当被引用对象。 */
    public void material(long id, String title, String content, long ownerNodeId, long tenantId) {
        jdbc.update("INSERT INTO crs_material (id, owner_node_id, title, content, "
                        + "attachment_file_ids, tenant_id, create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, NULL, ?, ?, NOW(), NOW(), 0)",
                id, ownerNodeId, title, content, tenantId, userIdOf(ownerNodeId));
    }

    /** 直接插课时 —— 用于「不经接口也要能造出待测状态」的用例。 */
    public void lesson(long id, long courseId, long chapterId, int lessonType, Long videoId,
                       Long contentId, int duration, int status, long tenantId) {
        jdbc.update("INSERT INTO crs_lesson (id, course_id, chapter_id, lesson_name, lesson_type, "
                        + "video_id, content_id, duration, sort, is_free_preview, status, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, NOW(), NOW(), 0)",
                id, courseId, chapterId, "课时" + id, lessonType, videoId, contentId,
                duration, status, tenantId);
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

    /** 手机号须 11 位且本租户内唯一 —— 用节点 id 末 8 位拼。 */
    public static String phoneOf(long nodeId) {
        return "171" + String.format("%08d", nodeId % 100000000L);
    }

    // =====================================================================
    // 断言用的读取
    // =====================================================================

    public Integer lessonCountOf(long courseId) {
        return jdbc.queryForObject("SELECT lesson_count FROM crs_course WHERE id = ?",
                Integer.class, courseId);
    }

    public Integer totalDurationOf(long courseId) {
        return jdbc.queryForObject("SELECT total_duration FROM crs_course WHERE id = ?",
                Integer.class, courseId);
    }

    public Integer statusOf(long courseId) {
        return jdbc.queryForObject("SELECT status FROM crs_course WHERE id = ?",
                Integer.class, courseId);
    }

    public Long deletedAtOf(String table, long id) {
        return jdbc.queryForObject("SELECT deleted_at FROM " + table + " WHERE id = ?",
                Long.class, id);
    }

    public String storedMaterialContent(long materialId) {
        return jdbc.queryForObject("SELECT content FROM crs_material WHERE id = ?",
                String.class, materialId);
    }

    public int liveLessonCount(long courseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crs_lesson WHERE course_id = ? AND deleted_at = 0",
                Integer.class, courseId);
        return n == null ? 0 : n;
    }

    public int liveChapterCount(long courseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crs_chapter WHERE course_id = ? AND deleted_at = 0",
                Integer.class, courseId);
        return n == null ? 0 : n;
    }

    public Integer lessonDurationOf(long lessonId) {
        return jdbc.queryForObject("SELECT duration FROM crs_lesson WHERE id = ?",
                Integer.class, lessonId);
    }
}
