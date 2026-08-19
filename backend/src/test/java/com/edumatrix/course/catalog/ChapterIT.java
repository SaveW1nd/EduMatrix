package com.edumatrix.course.catalog;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节管理（03-03 §2.1~§2.5，接口 7~11）。
 *
 * <p>覆盖的判据：
 * <ul>
 *   <li>模块 08 自检项「在『节』下再建子章节被拒并提示两级上限」（{@code 20006}）；
 *   <li>PRD F2-1 规则 6 级联逻辑删除与影响课时数；
 *   <li>§2.5 规则 2 的 {@code 20018}（并发编辑）与规则 3 的 {@code 20006}。
 * </ul>
 */
class ChapterIT extends CourseIntegrationTestBase {

    @Test
    @DisplayName("模块 08 自检：在「节」下再建子章节被拒 → 20006，且提示两级上限")
    void cannotCreateThirdLevel() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, 0L, "第一章");
        long section = createChapter(token, chapter, "1.1 小节");

        JsonNode rejected = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + CourseFixtures.C_ROOT + "\",\"parentId\":\"" + section
                        + "\",\"chapterName\":\"1.1.1 三级\"}");
        assertEquals(20006, code(rejected));
        assertTrue(rejected.path("msg").asText().contains("两级"),
                "提示里要说清「最多两级」，否则用户不知道该怎么改：" + rejected.path("msg").asText());
    }

    @Test
    @DisplayName("§2.1 章节树：章在顶层、节挂 children，lessonCount 为该节点直挂课时数")
    void treeIsTwoLevelWithDirectLessonCount() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, 0L, "第一章");
        long section = createChapter(token, chapter, "1.1 小节");
        courseFixtures.lesson(1968000000000009101L, CourseFixtures.C_ROOT, section,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);
        courseFixtures.lesson(1968000000000009102L, CourseFixtures.C_ROOT, section,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);

        JsonNode tree = client.getWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters", token);
        assertEquals(200, code(tree));
        JsonNode roots = data(tree);
        assertEquals(1, roots.size());
        assertEquals(String.valueOf(chapter), roots.get(0).path("id").asText());
        assertEquals(0, roots.get(0).path("lessonCount").asInt(), "章下没有直挂课时");
        JsonNode children = roots.get(0).path("children");
        assertEquals(1, children.size());
        assertEquals(2, children.get(0).path("lessonCount").asInt(), "节的直挂课时数应为 2");
        assertEquals(0, children.get(0).path("children").size(), "两级树：节没有 children");
    }

    @Test
    @DisplayName("§2.1 是读接口：被授权者可看章节树（订正前的权限栏要求 owner，那是自相矛盾的）")
    void grantedNodeCanReadChapterTree() throws Exception {
        String ownerToken = loginAs(CourseFixtures.ROOT);
        createChapter(ownerToken, 0L, "第一章");
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.TB, CourseFixtures.TENANT_ID);

        String grantedToken = loginAs(CourseFixtures.TB);
        JsonNode tree = client.getWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters", grantedToken);
        assertEquals(200, code(tree));
        assertEquals(1, data(tree).size());

        JsonNode created = client.postWithToken("/api/v1/course/chapters", grantedToken,
                "{\"courseId\":\"" + CourseFixtures.C_ROOT + "\",\"parentId\":\"0\","
                        + "\"chapterName\":\"被授权者试图加章\"}");
        assertEquals(403, code(created), "写操作仍要求 owner");
    }

    @Test
    @DisplayName("PRD F2-1 规则 6：删除章 → 其下节与课时一并逻辑删除，响应回显实际删除数")
    void deleteChapterCascades() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, 0L, "第一章");
        long s1 = createChapter(token, chapter, "1.1");
        long s2 = createChapter(token, chapter, "1.2");
        courseFixtures.lesson(1968000000000009201L, CourseFixtures.C_ROOT, s1,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);
        courseFixtures.lesson(1968000000000009202L, CourseFixtures.C_ROOT, s2,
                1, CourseFixtures.VIDEO_OK, null, CourseFixtures.VIDEO_OK_DURATION, 1,
                CourseFixtures.TENANT_ID);
        courseFixtures.lesson(1968000000000009203L, CourseFixtures.C_ROOT, chapter,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);

        JsonNode deleted = deleteWithToken("/api/v1/course/chapters/" + chapter, token);
        assertEquals(200, code(deleted));
        assertEquals(3, data(deleted).path("deletedChapterCount").asInt(), "自身 + 两个节");
        assertEquals(3, data(deleted).path("deletedLessonCount").asInt());
        assertEquals(0, courseFixtures.liveChapterCount(CourseFixtures.C_ROOT));
        assertEquals(0, courseFixtures.liveLessonCount(CourseFixtures.C_ROOT));
        assertEquals(0, courseFixtures.lessonCountOf(CourseFixtures.C_ROOT), "冗余计数同事务重算");
        assertEquals(0, courseFixtures.totalDurationOf(CourseFixtures.C_ROOT));
    }

    @Test
    @DisplayName("删除节：只删自己与其下课时，兄弟节不受影响")
    void deleteSectionOnlyRemovesItsOwnLessons() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, 0L, "第一章");
        long s1 = createChapter(token, chapter, "1.1");
        long s2 = createChapter(token, chapter, "1.2");
        courseFixtures.lesson(1968000000000009301L, CourseFixtures.C_ROOT, s1,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);
        courseFixtures.lesson(1968000000000009302L, CourseFixtures.C_ROOT, s2,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);

        JsonNode deleted = deleteWithToken("/api/v1/course/chapters/" + s1, token);
        assertEquals(1, data(deleted).path("deletedChapterCount").asInt());
        assertEquals(1, data(deleted).path("deletedLessonCount").asInt());
        assertEquals(2, courseFixtures.liveChapterCount(CourseFixtures.C_ROOT), "章与 1.2 仍在");
        assertEquals(1, courseFixtures.liveLessonCount(CourseFixtures.C_ROOT));
    }

    @Test
    @DisplayName("§2.5 规则 2：提交的 id 集合与库里不一致 → 20018（并发编辑的真实形态）")
    void staleSortSubmissionIsRejected() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long c1 = createChapter(token, 0L, "第一章");
        long c2 = createChapter(token, 0L, "第二章");

        // 前端拿到的是「两个章」的快照；此刻另一个人删掉了第二章
        deleteWithToken("/api/v1/course/chapters/" + c2, token);

        JsonNode rejected = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters/sort", token,
                sortBody(List.of(new long[]{c1, 0L, 1}, new long[]{c2, 0L, 2})));
        assertEquals(20018, code(rejected));

        // 少提交一个同样不一致
        long c3 = createChapter(token, 0L, "第三章");
        JsonNode missing = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters/sort", token,
                sortBody(List.of(new long[]{c1, 0L, 1})));
        assertEquals(20018, code(missing), "少一个也是不一致，c3=" + c3);

        // 重复提交同一个 id：集合大小对不上
        JsonNode duplicated = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters/sort", token,
                sortBody(List.of(new long[]{c1, 0L, 1}, new long[]{c1, 0L, 2})));
        assertEquals(20018, code(duplicated));
    }

    @Test
    @DisplayName("§2.5 规则 3：排序里把节挂到另一个节下 → 20006（会形成三级）")
    void sortCannotProduceThreeLevels() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, 0L, "第一章");
        long s1 = createChapter(token, chapter, "1.1");
        long s2 = createChapter(token, chapter, "1.2");

        JsonNode rejected = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters/sort", token,
                sortBody(List.of(new long[]{chapter, 0L, 1},
                        new long[]{s1, chapter, 1},
                        new long[]{s2, s1, 1})));
        assertEquals(20006, code(rejected));
    }

    @Test
    @DisplayName("§2.5：合法的整棵结构提交成功，节可以在章之间搬、也可以升级为章")
    void sortAppliesNewStructure() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long c1 = createChapter(token, 0L, "第一章");
        long c2 = createChapter(token, 0L, "第二章");
        long s1 = createChapter(token, c1, "1.1");

        JsonNode ok = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters/sort", token,
                sortBody(List.of(new long[]{c2, 0L, 1},
                        new long[]{c1, 0L, 2},
                        new long[]{s1, c2, 1})));
        assertEquals(200, code(ok));

        JsonNode tree = client.getWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/chapters", token);
        assertEquals(String.valueOf(c2), data(tree).get(0).path("id").asText(), "第二章排到了首位");
        assertEquals(String.valueOf(s1), data(tree).get(0).path("children").get(0).path("id").asText(),
                "1.1 被搬到了第二章下");
    }

    @Test
    @DisplayName("§2.3：只改名称；章节不存在返回 HTTP 404（不是 20014）")
    void updateChapterNameAndNotFound() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, 0L, "第一章");

        JsonNode ok = client.putWithToken("/api/v1/course/chapters/" + chapter, token,
                "{\"chapterName\":\"第一章（修订）\"}");
        assertEquals(200, code(ok));

        JsonNode missing = client.putWithToken("/api/v1/course/chapters/1968000000000099999", token,
                "{\"chapterName\":\"不存在\"}");
        assertEquals(404, code(missing));
    }

    // =====================================================================

    private long createChapter(String token, long parentId, String name) throws Exception {
        JsonNode created = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + CourseFixtures.C_ROOT + "\",\"parentId\":\"" + parentId
                        + "\",\"chapterName\":\"" + name + "\"}");
        assertEquals(200, code(created), created.toString());
        return data(created).path("id").asLong();
    }

    /** {@code [id, parentId, sort]} 三元组列表 → 请求体。 */
    private static String sortBody(List<long[]> items) {
        List<String> parts = new ArrayList<>();
        for (long[] item : items) {
            parts.add("{\"id\":\"" + item[0] + "\",\"parentId\":\"" + item[1]
                    + "\",\"sort\":" + item[2] + "}");
        }
        return "{\"chapters\":[" + String.join(",", parts) + "]}";
    }
}
