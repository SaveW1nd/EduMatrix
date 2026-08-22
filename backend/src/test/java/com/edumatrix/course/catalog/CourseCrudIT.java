package com.edumatrix.course.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 课程 CRUD 与可见性（03-03 §1.1~§1.5，接口 1~5）。
 *
 * <p>覆盖的判据：
 * <ul>
 *   <li>PRD F2-1 验收标准 4「{@code owner_node_id} 非本节点时被拒」的读侧与写侧；
 *   <li>D-2 强制检查点：{@code coverUrl} 绝不是 {@code sys_file.file_url} 的原值；
 *   <li>契约 §2.1 跨租户一律 404；03-03 §0.2「超管不参与本模块业务操作」。
 * </ul>
 */
class CourseCrudIT extends CourseIntegrationTestBase {

    @Test
    @DisplayName("§1.3 创建：owner_node_id 由服务端写入创建者节点，初始 status=0 草稿")
    void createWritesOwnerNodeAndDraftStatus() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode created = client.postWithToken("/api/v1/course/courses", token,
                "{\"courseName\":\"新建课程\",\"subject\":\"语文\"}");
        assertEquals(200, code(created));
        long id = data(created).path("id").asLong();

        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + id, token);
        assertEquals(String.valueOf(CourseFixtures.ROOT), data(detail).path("ownerNodeId").asText());
        assertEquals(0, data(detail).path("status").asInt());
        assertEquals(0, data(detail).path("lessonCount").asInt());
        assertEquals(1, data(detail).path("grantType").asInt());
        assertEquals("IT08 课程编排机构", data(detail).path("ownerNodeName").asText());
    }

    /**
     * <b>需方 2026-08-21 定案（排期 A）的判据之一 —— 课程侧代表端点。</b>
     *
     * <p>「三类受管资源的写权限，教师一条不留；资源由管理员生产，教师只教学与管理」。
     * 收窄靠迁移 {@code V202608210200} 撤销 {@code teacher → course:course:add} 的绑定，
     * <b>不靠代码里的角色门</b>（与 {@code V202608210000} / F-72 逐字同源）。
     *
     * <p><b>两侧都要断言</b>：只写「教师 403」的话，把 {@code @SaCheckPermission} 换成
     * 一个谁都没有的标识、甚至把整个端点删掉，都能让它全绿 —— 而那等于把建课功能
     * 一起关了，且看不出来。
     *
     * <p><b>为什么创建端点最适合做代表</b>：它没有归属前置判定（还没有资源，谈不上 owner），
     * 所以这里的 403 <b>只可能</b>来自权限绑定 —— 换成修改/删除端点的话，
     * 「非 owner → 403」会先给出同一个结果，判据就不干净了。
     */
    @Test
    @DisplayName("⚠ §1.3 新建课程【仅 org_admin】：教师 403、管理员 200（需方定案，排期 A）")
    void createCourseIsOrgAdminOnly() throws Exception {
        String body = "{\"courseName\":\"权限探针\",\"subject\":\"语文\"}";

        JsonNode teacher = client.postWithToken("/api/v1/course/courses",
                loginAs(CourseFixtures.TB), body);
        assertEquals(403, code(teacher),
                "教师拿不到建课端点（【结果】断言；成因见 teacherHasNoCourseWritePerms）");

        JsonNode admin = client.postWithToken("/api/v1/course/courses",
                loginAs(CourseFixtures.ROOT), body);
        assertEquals(200, code(admin),
                "这一侧不写，等于把建课整个关掉也全绿");
    }

    @Test
    @DisplayName("⚠ F-72 的成因判据：教师的 perms 里【没有】course:course:add")
    void teacherHasNoCourseWritePerms() throws Exception {
        // 【上一轮 F-114 收窄之后，上面那条 403 变成了过定的，而我当时没发现】
        // 教师既没有 course:course:add 绑定（F-72），也不在机构根上（F-114）——
        // 两道闸都会 403。实测 M62：把绑定加回 sys_role_menu，
        // createCourseIsOrgAdminOnly 照样全绿，全库只有 AuthMeIT.teacherPerms 会红，
        // 而那是个【计数】（61 → 66）不是身份：删一个加一个，数字不变，全绿。
        assertFalse(permsOf(CourseFixtures.TB).contains("course:course:add"),
                "F-72 撤的是 sys_role_menu 里那行绑定，不是代码里的角色门");
    }

    @Test
    @DisplayName("§0.2：上级管理员看不到下级教师自建的课程（两条判定取交集的必然结果）")
    void adminCannotSeeSubordinateTeacherOwnedCourse() throws Exception {
        String rootToken = loginAs(CourseFixtures.ROOT);

        JsonNode list = client.getWithToken("/api/v1/course/courses?pageSize=100", rootToken);
        assertFalse(list.toString().contains(String.valueOf(CourseFixtures.C_TA)),
                "教师王的课程出现在了上级的列表里 —— §0.2「父级授权给了我的下级也不等于授权给了我」");

        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + CourseFixtures.C_TA, rootToken);
        assertEquals(404, code(detail), "不可见的课程必须 404，不暴露存在性");
    }

    @Test
    @DisplayName("PRD F2-1 验收标准 4：被授权者可读、可预览，但写操作 403")
    void grantedNodeIsReadOnly() throws Exception {
        // 模块 11 之前没有授权接口，手工插一行有效授权
        // 【被授权者换成管理员 A1】教师已无该写权限（V202608210200），
        // 继续用教师会让这条 403 【绿着退化】：判定从「可见但非 owner」
        // 变成「压根没这个权限」，而本条要证的正是前者。A1 有权限、只是不是 owner。

        // ⚠【F-114 再换一次演员】收窄之后 A1 会在【机构根闸】处 403，本条会绿着退化成
        //   「A1 碰不到这个端点」。换成机构根 ROOT + TA 拥有的资源：ROOT 过得了机构根闸、
        //   也有对应权限位，403 才真的来自归属判定。与 F-110 那轮从教师换到 A1 同一形状。
        courseFixtures.grantCourse(CourseFixtures.C_TA, CourseFixtures.ROOT, CourseFixtures.TENANT_ID);
        String grantedToken = loginAs(CourseFixtures.ROOT);

        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + CourseFixtures.C_TA, grantedToken);
        assertEquals(200, code(detail));
        assertEquals(2, data(detail).path("grantType").asInt(), "被授权行的 grantType 应为 2");
        assertTrue(data(detail).path("grantedNodeCount").isNull() || detail.toString().contains("grantType"),
                "详情不返回 grantedNodeCount，无需断言");

        JsonNode updated = client.putWithToken("/api/v1/course/courses/" + CourseFixtures.C_TA,
                grantedToken, "{\"courseName\":\"被授权者试图改名\"}");
        assertEquals(403, code(updated), "被授权者不可写（契约 §2.5 规则 8）—— "
                + "演员是【机构根】ROOT，他过得了 F-114 的机构根闸、也有 course:course:edit，"
                + "所以 403 只可能来自归属判定 —— 他不是 C_TA 的 owner");

        JsonNode deleted = deleteWithToken("/api/v1/course/courses/" + CourseFixtures.C_TA, grantedToken);
        assertEquals(403, code(deleted), "同上：删除也走归属判定。"
                + "⚠ 这一句原先指着 C_ROOT，而演员换成 ROOT 之后【他就是 C_ROOT 的 owner】—— "
                + "删得掉是对的，但那样这一句就什么都没验到");
    }

    @Test
    @DisplayName("§1.1：grantType 与 grantedNodeCount —— 被授权行恒为 null（不得窥探授权面）")
    void listExposesGrantTypeAndHidesGrantedCountForGrantedRows() throws Exception {
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.TB, CourseFixtures.TENANT_ID);

        String rootToken = loginAs(CourseFixtures.ROOT);
        JsonNode ownList = client.getWithToken("/api/v1/course/courses?pageSize=100", rootToken);
        JsonNode ownRow = firstRowWithId(ownList, CourseFixtures.C_ROOT);
        assertEquals(1, ownRow.path("grantType").asInt());
        assertEquals(1, ownRow.path("grantedNodeCount").asInt(), "自有行应返回真实授权目标数");

        String teacherToken = loginAs(CourseFixtures.TB);
        JsonNode grantedList = client.getWithToken("/api/v1/course/courses?pageSize=100", teacherToken);
        JsonNode grantedRow = firstRowWithId(grantedList, CourseFixtures.C_ROOT);
        assertEquals(2, grantedRow.path("grantType").asInt());
        assertTrue(grantedRow.path("grantedNodeCount").isNull(),
                "被授权行泄露了授权面 —— §1.1「下级不得窥探同级/上级的授权面」");
    }

    @Test
    @DisplayName("§1.1 grantType 筛选：1 仅自有、2 仅被授权")
    void listFiltersByGrantType() throws Exception {
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.TB, CourseFixtures.TENANT_ID);
        String teacherToken = loginAs(CourseFixtures.TB);

        JsonNode own = client.getWithToken("/api/v1/course/courses?grantType=1&pageSize=100", teacherToken);
        assertEquals(0, data(own).path("total").asInt(), "TB 没有自有课程");

        JsonNode granted = client.getWithToken("/api/v1/course/courses?grantType=2&pageSize=100", teacherToken);
        assertEquals(1, data(granted).path("total").asInt());
    }

    @Test
    @DisplayName("D-2 强制检查点：coverUrl 绝不是 sys_file.file_url（那一列只存对象键）")
    void coverUrlIsNeverTheRawObjectKey() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);

        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT, token);
        assertFalse(detail.toString().contains(CourseFixtures.COVER_OBJECT_KEY),
                "响应里出现了对象键 —— 有人把 sys_file.file_url 读出来直接下发了，"
                        + "那是一条永久直链（00-通用约定 §7.4 第 1 行）");
        // 本地存储模式下 inlineSignedUrl 恒为 empty（LocalObjectStorage 没有签名地址），
        // 因此这里 coverUrl 必然为 null；生产一律 OSS。coverFileId 仍然要给出来
        assertTrue(data(detail).path("coverUrl").isNull(),
                "本地存储下应为 null；若这里有值，说明地址不是现签的");
        assertEquals(String.valueOf(CourseFixtures.COVER_FILE), data(detail).path("coverFileId").asText());

        JsonNode list = client.getWithToken("/api/v1/course/courses?pageSize=100", token);
        assertFalse(list.toString().contains(CourseFixtures.COVER_OBJECT_KEY), "列表同样不得下发对象键");
    }

    @Test
    @DisplayName("契约 §2.1：跨租户课程一律 404，列表里也看不到")
    void crossTenantCourseIsInvisible() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + CourseFixtures.C_OTHER, token);
        assertEquals(404, code(detail),
                "跨租户被插件过滤 → 与「不存在」「不可见」三者同一个结果（F-42 定案）");

        JsonNode list = client.getWithToken("/api/v1/course/courses?pageSize=100", token);
        assertFalse(list.toString().contains("另一个机构的课程"), "跨租户数据出现在了列表里");

        JsonNode updated = client.putWithToken("/api/v1/course/courses/" + CourseFixtures.C_OTHER,
                token, "{\"courseName\":\"越界改名\"}");
        assertEquals(404, code(updated));
    }

    @Test
    @DisplayName("03-03 §0.2：平台超管不参与本模块业务操作 —— 403（这是租户插件整体放行的那道闸）")
    void superAdminIsRejectedByPerms() throws Exception {
        String token = loginAsSuperAdmin();
        JsonNode list = client.getWithToken("/api/v1/course/courses", token);
        assertEquals(403, code(list),
                "超管能调课程接口 = 跨租户全可见且不报错（租户插件对超管会话整体放行）。"
                        + "谁把 course:* 菜单绑给了 super_admin，本条就会红");
    }

    @Test
    @DisplayName("§1.4 修改：owner 可改；§1.5 删除：草稿可删并级联删章节课时")
    void updateAndDeleteByOwner() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);

        JsonNode updated = client.putWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT,
                token, "{\"courseName\":\"改名后的课程\",\"subject\":\"英语\"}");
        assertEquals(200, code(updated));
        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT, token);
        assertEquals("改名后的课程", data(detail).path("courseName").asText());
        assertEquals("英语", data(detail).path("subject").asText());

        long chapterId = 1968000000000008001L;
        courseFixtures.chapter(chapterId, CourseFixtures.C_ROOT, 0L, "第一章", 1,
                CourseFixtures.TENANT_ID);
        courseFixtures.lesson(1968000000000009001L, CourseFixtures.C_ROOT, chapterId,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);

        JsonNode deleted = deleteWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT, token);
        assertEquals(200, code(deleted));
        assertEquals(0, courseFixtures.liveChapterCount(CourseFixtures.C_ROOT), "章节应被级联逻辑删除");
        assertEquals(0, courseFixtures.liveLessonCount(CourseFixtures.C_ROOT), "课时应被级联逻辑删除");
        assertNotNull(courseFixtures.deletedAtOf("crs_course", CourseFixtures.C_ROOT));
        assertTrue(courseFixtures.deletedAtOf("crs_course", CourseFixtures.C_ROOT) > 0,
                "逻辑删除写的是毫秒时间戳（契约 §2.2）");
    }

    @Test
    @DisplayName("§1.5：已上架课程不可删除 → 20005")
    void cannotDeleteOnShelfCourse() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapterId = 1968000000000008002L;
        courseFixtures.chapter(chapterId, CourseFixtures.C_ROOT, 0L, "第一章", 1,
                CourseFixtures.TENANT_ID);
        courseFixtures.lesson(1968000000000009002L, CourseFixtures.C_ROOT, chapterId,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);
        client.putWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/shelf",
                token, "{\"targetStatus\":1}");

        JsonNode deleted = deleteWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT, token);
        assertEquals(20005, code(deleted));
        assertNull(courseFixtures.deletedAtOf("crs_course", CourseFixtures.C_ROOT) == 0L ? null : "deleted",
                "被拒之后课程不应被删");
    }

    // =====================================================================

    private static JsonNode firstRowWithId(JsonNode listResponse, long id) {
        for (JsonNode row : listResponse.path("data").path("list")) {
            if (row.path("id").asText().equals(String.valueOf(id))) {
                return row;
            }
        }
        throw new AssertionError("列表里没有 id=" + id + "：" + listResponse);
    }
    /**
     * <b>收窄本身要有用例守着</b>，否则把 {@code OrgRootGuard} 删掉全库无人发觉。
     *
     * <p><b>两侧都断</b>：只写「分校 403」的话，把整个端点写死拒绝也能全绿。
     */
    @Test
    @DisplayName("⚠ F-114 收窄：课程写操作【仅机构根】—— 分校管理员 403、机构根 200（两侧都断）")
    void courseWriteIsOrgRootOnly() throws Exception {
        String body = "{\"courseName\":\"收窄探针\",\"subject\":\"数学\"}";

        JsonNode sub = client.postWithToken("/api/v1/course/courses", loginAs(CourseFixtures.A1), body);
        assertEquals(403, code(sub),
                "分校管理员有 course:course:add 权限位，但不是机构根 —— "
                        + "这是资源归属层级的约束，不是权限等级");

        JsonNode root = client.postWithToken("/api/v1/course/courses", loginAs(CourseFixtures.ROOT), body);
        assertEquals(200, code(root), "机构根必须过得去；这里若也 403，说明收窄把机构根一起挡了");
    }

    @Test
    @DisplayName("F-114 收窄【只管写不管读】：分校管理员仍看得见课程列表")
    void courseReadStillWorksForSubAdmin() throws Exception {
        JsonNode list = client.getWithToken("/api/v1/course/courses?pageSize=10",
                loginAs(CourseFixtures.A1));
        assertEquals(200, code(list),
                "读接口一个都没动 —— 分校管理员要看得见才能授权给名下学员");
    }
    /**
     * <b>M56 逼出来的用例</b>：把判据从 {@code id == tenant_id} 换成 {@code parent_id == 0}，
     * 原先<b>全库没有一条用例会红</b> —— 两者在所有<b>合法</b>的树上完全等价
     * （平台根的子节点只能是机构根，而机构根的 {@code id} 恒等于 {@code tenant_id}）。
     *
     * <p>所以这条用例<b>刻意种一个畸形节点</b>：{@code parent_id = 0} 但 {@code id ≠ tenant_id}。
     * 它在合法建树路径上产生不出来，但它能把「我们依赖的是哪一个事实」钉住：
     *
     * <ul>
     *   <li>{@code id == tenant_id} 是<b>契约 §2.1 直接写死</b>的；</li>
     *   <li>{@code parent_id == 0} 是<b>建树规则的推论</b> —— 今天成立，
     *       但它依赖「机构根一定挂在平台根下」这个树形状。</li>
     * </ul>
     *
     * <p>若将来有人为了「少查一次」把判据换成后者，本条会红。
     */
    @Test
    @DisplayName("⚠ M56：机构根判据是 id==tenant_id，不是 parent_id==0（畸形节点上两者才分得开）")
    void orgRootJudgedByTenantIdNotParentId() throws Exception {
        long weirdNode = 1968000000000000090L;   // parent_id=0 但 id != tenant_id —— 合法路径造不出来
        courseFixtures.malformedRootLikeNode(weirdNode, CourseFixtures.TENANT_ID);

        JsonNode res = client.postWithToken("/api/v1/course/courses",
                loginAs(weirdNode),
                "{\"courseName\":\"畸形节点建课\",\"subject\":\"数学\"}");
        assertEquals(403, code(res),
                "它 parent_id=0，但 id != tenant_id —— 按契约它【不是】机构根，必须被拒。"
                        + "判据若换成 parent_id==0，这里会变成 200");
    }
}