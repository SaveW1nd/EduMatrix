package com.edumatrix.org.support;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.edumatrix.auth.support.AuthFixtures;

/**
 * 模块 06 验收用的组织树夹具。
 *
 * <h2>为什么另起一个租户，而不是复用模块 02 的那棵树</h2>
 * <ol>
 *   <li>本模块要<b>移动节点</b>，而 {@code AuthFixtures} 的树被模块 02/03/04 的十几个用例
 *       按形状断言着 —— 动一下那批全红；
 *   <li>模块 01 的 {@code SubtreeScopeHelperIT} 对基线示例租户的子树用了
 *       {@code containsExactlyInAnyOrder}，<b>往那棵树上加任何一个节点，那批测试立刻红</b>。
 * </ol>
 * 所以本夹具建在一个独立的测试租户下，用例前后先删后插。
 *
 * <h2>树形（判据全部落在这一棵树上）</h2>
 * <pre>
 * ROOT(1,机构最高管理员 = 操作人)   depth 1
 *  ├─ A1(1) 华东大区               depth 2
 *  │   ├─ T1(2) 王丽 ── S1 S2 S3   depth 3 / 4   ← 【移动它：3 个后代，affectedNodeCount = 4】
 *  │   ├─ T2(2) 李强 ── S4 S5      depth 3 / 4
 *  │   └─ T3(2) 赵敏 ── S6 S7 S8   depth 3 / 4
 *  └─ A2(1) 华南大区               depth 2
 *      └─ TX(2) 周伟               depth 3       ← 学员搬到这里，验原上级立即失去访问权
 * </pre>
 *
 * <h2>⚠ 这棵树 F-114 之后<b>改过形</b>，原来的形状现在是非法的</h2>
 * <p>原形是 {@code ROOT → A1 → P(1) → A3(1) → T1(2) → S1(3)}，<b>三层管理员、深 6</b>，
 * 用来满足边界 B4「覆盖多层嵌套的移动用例」（移动 P，12 个后代，{@code affectedNodeCount = 13}）。
 * F-114 定案二之后：
 * <ul>
 *   <li>「机构下只允许一层管理员」—— {@code P} 与 {@code A3} 这两个嵌套管理员建不出来了；</li>
 *   <li>{@code MAX_DEPTH} 由 50 收到 4 —— 原来的 {@code S1}（depth 6）超限。</li>
 * </ul>
 * <p><b>B4 因此缩水了，而且是缩到不能再小</b>：合法树里<b>最深的可移动子树就是「教师 + 学员」两层</b>
 * （管理员的合法父节点只剩机构根一个，等于不可移动；学员是叶子）。
 * 「逐层断言 ancestors」还在，只是层数从 4 变成 2 —— <b>这不是把用例改弱了，是合法形状本身只剩这么高</b>。
 * 常量 {@code P} / {@code A3} 已删；{@link #LEGACY_NODES} 只用来把历史遗留行删干净。
 *
 * <h2>冗余计数按真实值播种，不是全 0</h2>
 * <p>{@code student_count} / {@code child_count} 播成移动前的正确值，
 * 移动后的断言才是「增量维护对不对」，而不是「从 0 加了几」。
 */
public final class OrgFixtures {

    /** 测试租户（契约 §2.1：机构根节点 id 即 tenant_id）。 */
    public static final long TENANT_ID = 1962000000000000001L;

    public static final long ROOT = TENANT_ID;
    public static final long A1 = 1962000000000000010L;
    public static final long A2 = 1962000000000000011L;
    public static final long T1 = 1962000000000000040L;
    public static final long T2 = 1962000000000000041L;
    public static final long T3 = 1962000000000000042L;
    public static final long TX = 1962000000000000043L;
    public static final long S1 = 1962000000000000050L;
    public static final long S2 = 1962000000000000051L;
    public static final long S3 = 1962000000000000052L;
    public static final long S4 = 1962000000000000053L;
    public static final long S5 = 1962000000000000054L;
    public static final long S6 = 1962000000000000055L;
    public static final long S7 = 1962000000000000056L;
    public static final long S8 = 1962000000000000057L;

    /** 全部节点，按建树顺序（父在子之前）。清理时倒序删。 */
    public static final long[] ALL_NODES = {
            ROOT, A1, A2, T1, T2, T3, TX, S1, S2, S3, S4, S5, S6, S7, S8};

    /**
     * F-114 改形前存在、现在不再播种的节点 id（原 {@code P} 苏州中心、{@code A3} 教学一组）。
     *
     * <p>它们只出现在 {@link #clean()} 里：开发库上跑过旧夹具的机器仍留着这两行，
     * 不删的话 {@code auditTreeConsistency()} 会把它们当成掉在树外的节点报出来。
     * <b>不要往树里加回去</b> —— 它们是两层嵌套管理员，F-114 之后建不出来。
     */
    public static final long[] LEGACY_NODES = {1962000000000000020L, 1962000000000000030L};

    /** {@code sys_user.id} 由节点 id 偏移得到，与任何节点 id 都不相等。 */
    public static final long USER_OFFSET = 100000L;

    public static final String USERNAME_PREFIX = "it06_";
    /** 全部夹具账号的明文密码（8~20 位、含字母与数字）。 */
    public static final String PASSWORD = "Test@123456";

    /** 机构最高管理员的用户名 —— 绝大多数用例的操作人。 */
    public static final String ROOT_USERNAME = USERNAME_PREFIX + ROOT;
    /** 教师 T3 的用户名 —— 验「学员被移走后原上级立即失去访问权」。 */
    public static final String T3_USERNAME = USERNAME_PREFIX + T3;
    /** 下级管理员 A2 的用户名 —— 验越界与「不能把自己搬走」。 */
    public static final String A2_USERNAME = USERNAME_PREFIX + A2;

    private final JdbcTemplate jdbc;
    private final String encodedPassword;

    public OrgFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 与生产同一个 cost（PRD §7.3：≥ 10）
        this.encodedPassword = new BCryptPasswordEncoder(10).encode(PASSWORD);
    }

    /**
     * 先删后插，保证可重复运行。
     *
     * <p>用 {@link JdbcTemplate} 直接写：它绕过 MyBatis，因此不受租户插件影响。
     * <b>业务代码永远不该这么干</b>（模块 01 的 {@code PlatformRowVisibilityIT} 已立此先例）。
     */
    public void seed() {
        clean();

        jdbc.update("INSERT INTO sys_tenant (id, root_node_id, name, expire_time, status, "
                        + "max_student_count, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, 'IT06 组织树机构', NULL, 0, 500, NOW(), NOW(), 0)",
                TENANT_ID, ROOT);

        String rootAnc = "0";
        String lvl2 = "0," + ROOT;
        String underA1 = lvl2 + "," + A1;
        String underA2 = lvl2 + "," + A2;

        // 节点：id, parent, ancestors, 名称, 类型, child_count, student_count
        node(ROOT, 0L, rootAnc, "IT06 组织树机构", 1, 2, 8);
        node(A1, ROOT, lvl2, "华东大区", 1, 3, 8);
        node(A2, ROOT, lvl2, "华南大区", 1, 1, 0);
        node(T1, A1, underA1, "王丽", 2, 3, 3);
        node(T2, A1, underA1, "李强", 2, 2, 2);
        node(T3, A1, underA1, "赵敏", 2, 3, 3);
        node(TX, A2, underA2, "周伟", 2, 0, 0);

        student(S1, T1, underA1 + "," + T1, "学生一");
        student(S2, T1, underA1 + "," + T1, "学生二");
        student(S3, T1, underA1 + "," + T1, "学生三");
        student(S4, T2, underA1 + "," + T2, "学生四");
        student(S5, T2, underA1 + "," + T2, "学生五");
        student(S6, T3, underA1 + "," + T3, "学生六");
        student(S7, T3, underA1 + "," + T3, "学生七");
        student(S8, T3, underA1 + "," + T3, "学生八");

        // 教师档案：student_count 与 org_node.student_count 同源同步（DDL 列注释）
        teacher(T1, 3);
        teacher(T2, 2);
        teacher(T3, 3);
        teacher(TX, 0);
    }

    public void clean() {
        for (long nodeId : LEGACY_NODES) {
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userIdOf(nodeId));
            jdbc.update("DELETE FROM sys_user WHERE id = ?", userIdOf(nodeId));
            jdbc.update("DELETE FROM org_node_change_log WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_resource_grant WHERE target_node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node WHERE id = ?", nodeId);
        }
        for (long nodeId : ALL_NODES) {
            long userId = userIdOf(nodeId);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
            jdbc.update("DELETE FROM org_student WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_teacher WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_resource_grant WHERE target_node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node_change_log WHERE node_id = ?", nodeId);
            jdbc.update("DELETE FROM org_node WHERE id = ?", nodeId);
        }
        jdbc.update("DELETE FROM crs_course WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM sys_login_log WHERE username LIKE 'it06\\_%'");
        jdbc.update("DELETE FROM sys_tenant WHERE id = ?", TENANT_ID);
    }

    public static long userIdOf(long nodeId) {
        return nodeId + USER_OFFSET;
    }

    public static String usernameOf(long nodeId) {
        return USERNAME_PREFIX + nodeId;
    }

    // =====================================================================
    // 断言用的读取
    // =====================================================================

    public String ancestorsOf(long nodeId) {
        return jdbc.queryForObject("SELECT ancestors FROM org_node WHERE id = ?", String.class, nodeId);
    }

    public Long parentOf(long nodeId) {
        return jdbc.queryForObject("SELECT parent_id FROM org_node WHERE id = ?", Long.class, nodeId);
    }

    public int childCountOf(long nodeId) {
        Integer v = jdbc.queryForObject(
                "SELECT child_count FROM org_node WHERE id = ?", Integer.class, nodeId);
        return v == null ? 0 : v;
    }

    public int studentCountOf(long nodeId) {
        Integer v = jdbc.queryForObject(
                "SELECT student_count FROM org_node WHERE id = ?", Integer.class, nodeId);
        return v == null ? 0 : v;
    }

    public int teacherStudentCountOf(long nodeId) {
        Integer v = jdbc.queryForObject(
                "SELECT student_count FROM org_teacher WHERE node_id = ? AND deleted_at = 0",
                Integer.class, nodeId);
        return v == null ? 0 : v;
    }

    public int changeLogCount(long nodeId) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(1) FROM org_node_change_log WHERE node_id = ?", Integer.class, nodeId);
        return v == null ? 0 : v;
    }

    public Integer latestChangeType(long nodeId) {
        return jdbc.query("SELECT change_type FROM org_node_change_log WHERE node_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getInt(1) : null, nodeId);
    }

    public Integer nodeStatusOf(long nodeId) {
        return jdbc.queryForObject("SELECT status FROM org_node WHERE id = ?", Integer.class, nodeId);
    }

    public Integer userStatusOf(long nodeId) {
        return jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?",
                Integer.class, userIdOf(nodeId));
    }

    public Integer pwdResetFlagOf(long nodeId) {
        return jdbc.queryForObject("SELECT pwd_reset_flag FROM sys_user WHERE id = ?",
                Integer.class, userIdOf(nodeId));
    }

    public String passwordHashOf(long nodeId) {
        return jdbc.queryForObject("SELECT password FROM sys_user WHERE id = ?",
                String.class, userIdOf(nodeId));
    }

    public String realNameOf(long nodeId) {
        return jdbc.queryForObject("SELECT real_name FROM sys_user WHERE id = ?",
                String.class, userIdOf(nodeId));
    }

    /** 学籍状态改写（校验 10 的前置条件）。 */
    public void setStudentStatus(long nodeId, int status) {
        jdbc.update("UPDATE org_student SET status = ? WHERE node_id = ?", status, nodeId);
    }

    /** 授权一行资源给某节点，授权人取 {@code granterNodeId} 对应的账号。 */
    /**
     * 插一门<b>真实</b>课程（{@code crs_course}）。
     *
     * <h2>为什么模块 11 落地后必须有真课程</h2>
     * <p>契约 §2.5 规则 9 的完整判据是「授权行的 {@code target_node_id} 当前祖先链
     * 不再包含该资源 {@code owner_node_id} <b>或其有效授权链</b>」—— 它要<b>读 owner</b>。
     * 本夹具原先只插授权行、资源 ID 是编的，模块 06 那时的判据只看
     * 「授权人所在节点还在不在祖先链上」，读不读得到资源无所谓。
     * 判据补齐之后，读不到资源的行一律判为「不可再下发」，于是<b>对照组也会进清单</b>。
     */
    public void course(long id, String name, long ownerNodeId) {
        jdbc.update("INSERT INTO crs_course (id, course_name, owner_node_id, cover_file_id, "
                        + "subject, description, status, lesson_count, total_duration, tenant_id, "
                        + "create_by, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, NULL, '数学', '简介', 1, 0, 0, ?, ?, NOW(), NOW(), 0)",
                id, name, ownerNodeId, TENANT_ID, userIdOf(ROOT));
    }

    public void grantResource(long grantId, int resourceType, long resourceId,
                              long targetNodeId, long granterNodeId) {
        jdbc.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, "
                        + "target_node_id, grant_source, grant_by, grant_time, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 1, ?, NOW(), ?, NOW(), NOW(), 0)",
                grantId, resourceType, resourceId, targetNodeId, userIdOf(granterNodeId), TENANT_ID);
    }

    /**
     * 02-数据库设计 §3.1.1 的<b>递归 CTE 巡检</b>：用 CTE 独立推导一遍真实祖级路径，
     * 与 {@code ancestors} 冗余列比对，返回<b>不一致的行</b>。
     *
     * <p>并发判据就靠它：跑完后全树 {@code ancestors} 与 {@code parent_id} 必须自洽。
     * <b>遇环会被 {@code cte_max_recursion_depth} 截断报错</b> ——
     * 那本身就是一个成环探测器（§3.1.4 末尾）。
     *
     * <p>起点取<b>本租户根节点</b>而不是平台根：平台根下挂着别的租户，
     * 从那里展开会把整个库都拉进来，而本次要巡检的只有这一棵树。
     *
     * <p><b>{@code CAST(... AS CHAR(1000))} 不可省</b>：MySQL 的递归 CTE 用
     * <b>非递归那一支</b>推断列类型，锚点若直接写 {@code '0'}，{@code calc_anc} 就是
     * {@code CHAR(1)}，递归第一轮拼上一个 19 位的 id 就 {@code Data too long}。
     * 1000 与 {@code org_node.ancestors} 的 {@code VARCHAR(1000)} 对齐 ——
     * <b>真出现环时它仍会溢出报错</b>，那正是我们要的成环探测器。
     */
    public List<String> auditTreeConsistency() {
        return jdbc.queryForList("""
                WITH RECURSIVE t (id, parent_id, calc_anc) AS (
                    SELECT id, parent_id, CAST('0' AS CHAR(1000)) FROM org_node WHERE id = ?
                    UNION ALL
                    SELECT n.id, n.parent_id, CONCAT(t.calc_anc, ',', t.id)
                      FROM org_node n JOIN t ON n.parent_id = t.id
                     WHERE n.deleted_at = 0
                )
                SELECT CONCAT(n.id, ': stored=', n.ancestors, ' expected=', t.calc_anc)
                  FROM org_node n JOIN t ON t.id = n.id
                 WHERE n.deleted_at = 0 AND n.ancestors <> t.calc_anc
                """, String.class, ROOT);
    }

    /** 本租户树上的节点总数（巡检时确认没有节点从树上掉下来）。 */
    public int reachableNodeCount() {
        Integer v = jdbc.queryForObject("""
                WITH RECURSIVE t (id) AS (
                    SELECT id FROM org_node WHERE id = ?
                    UNION ALL
                    SELECT n.id FROM org_node n JOIN t ON n.parent_id = t.id
                     WHERE n.deleted_at = 0
                )
                SELECT COUNT(1) FROM t
                """, Integer.class, ROOT);
        return v == null ? 0 : v;
    }

    // =====================================================================
    // 建行
    // =====================================================================

    private void node(long id, long parentId, String ancestors, String name, int nodeType,
                      int childCount, int studentCount) {
        long userId = userIdOf(id);
        jdbc.update("INSERT INTO org_node (id, parent_id, ancestors, node_name, node_type, "
                        + "ref_user_id, sort, status, child_count, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, NOW(), NOW(), 0)",
                id, parentId, ancestors, name, nodeType, userId, childCount, studentCount, TENANT_ID);
        // phone 留 NULL：uk_tenant_phone(tenant_id, phone, deleted_at) 不约束 NULL，
        // 夹具因此不会与示例数据或彼此撞唯一键
        jdbc.update("INSERT INTO sys_user (id, username, password, user_type, real_name, phone, "
                        + "node_id, status, pwd_reset_flag, tenant_id, create_time, update_time, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, ?, 0, 0, ?, NOW(), NOW(), 0)",
                userId, usernameOf(id), encodedPassword, nodeType, name, id, TENANT_ID);
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, NOW(), NOW(), 0)",
                userId + 7L, userId, roleOf(nodeType), TENANT_ID);
    }

    private void student(long id, long parentId, String ancestors, String name) {
        node(id, parentId, ancestors, name, 3, 0, 0);
        jdbc.update("INSERT INTO org_student (id, node_id, user_id, status, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, 0, ?, NOW(), NOW(), 0)",
                id + 500, id, userIdOf(id), TENANT_ID);
    }

    private void teacher(long nodeId, int studentCount) {
        jdbc.update("INSERT INTO org_teacher (id, node_id, user_id, student_count, tenant_id, "
                        + "create_time, update_time, deleted_at) VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 0)",
                nodeId + 700, nodeId, userIdOf(nodeId), studentCount, TENANT_ID);
    }

    private static long roleOf(int nodeType) {
        return switch (nodeType) {
            case 2 -> AuthFixtures.ROLE_TEACHER;
            case 3 -> AuthFixtures.ROLE_STUDENT;
            default -> AuthFixtures.ROLE_ORG_ADMIN;
        };
    }
}
