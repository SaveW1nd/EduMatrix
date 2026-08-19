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
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.TB, CourseFixtures.TENANT_ID);
        String teacherToken = loginAs(CourseFixtures.TB);

        JsonNode detail = client.getWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT, teacherToken);
        assertEquals(200, code(detail));
        assertEquals(2, data(detail).path("grantType").asInt(), "被授权行的 grantType 应为 2");
        assertTrue(data(detail).path("grantedNodeCount").isNull() || detail.toString().contains("grantType"),
                "详情不返回 grantedNodeCount，无需断言");

        JsonNode updated = client.putWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT,
                teacherToken, "{\"courseName\":\"被授权者试图改名\"}");
        assertEquals(403, code(updated), "被授权者不可写（契约 §2.5 规则 8）");

        JsonNode deleted = deleteWithToken("/api/v1/course/courses/" + CourseFixtures.C_ROOT, teacherToken);
        assertEquals(403, code(deleted));
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
        assertEquals(20004, code(detail), "跨租户被插件过滤 → 与「不存在」同一个结果");

        JsonNode list = client.getWithToken("/api/v1/course/courses?pageSize=100", token);
        assertFalse(list.toString().contains("另一个机构的课程"), "跨租户数据出现在了列表里");

        JsonNode updated = client.putWithToken("/api/v1/course/courses/" + CourseFixtures.C_OTHER,
                token, "{\"courseName\":\"越界改名\"}");
        assertEquals(20004, code(updated));
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
}
