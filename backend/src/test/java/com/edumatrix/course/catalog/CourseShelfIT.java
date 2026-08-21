package com.edumatrix.course.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 课程上下架（03-03 §1.6，接口 6）—— <b>{@code 20003} 在本模块唯一的落点</b>。
 *
 * <p>与 {@link LessonIT} 合起来钉住三个码的分工：课时侧一律 {@code 20008}，
 * 上架侧才是 {@code 20003}（模块 08「禁止事项」逐字：不得用 {@code 20003} 表达
 * 「关联视频状态不可用」）。
 */
class CourseShelfIT extends CourseIntegrationTestBase {

    @Test
    @DisplayName("§1.6 规则 2 后半句：存在未转码完成的视频课时 → 20003，文案点名到具体课时")
    void transcodingVideoBlocksShelfWith20003() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);
        // 一个可见的图文课时保证「至少 1 个可见课时」这条前置通过
        courseFixtures.lesson(1968000000000009401L, CourseFixtures.C_ROOT, chapter,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);
        // 一个隐藏的视频课时，其视频还在转码 —— §1.6 的字面是「全部视频课时」，含隐藏
        courseFixtures.lesson(1968000000000009402L, CourseFixtures.C_ROOT, chapter,
                1, CourseFixtures.VIDEO_TRANSCODING, null, 0, 0, CourseFixtures.TENANT_ID);

        JsonNode rejected = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/shelf", token,
                "{\"targetStatus\":1}");
        assertEquals(20003, code(rejected));
        assertTrue(rejected.path("msg").asText().contains("课时["),
                "§1.6 的失败示例要求点名到具体课时：" + rejected.path("msg").asText());
        assertEquals(0, courseFixtures.statusOf(CourseFixtures.C_ROOT), "被拒后状态不变");
    }

    @Test
    @DisplayName("§1.6 规则 2 前半句：没有任何可见课时 → 20005（G 定案：复用，msg 写明）")
    void noVisibleLessonBlocksShelfWith20005() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);
        courseFixtures.lesson(1968000000000009403L, CourseFixtures.C_ROOT, chapter,
                2, null, null, 0, 0, CourseFixtures.TENANT_ID);

        JsonNode rejected = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/shelf", token,
                "{\"targetStatus\":1}");
        assertEquals(20005, code(rejected));
        assertTrue(rejected.path("msg").asText().contains("可见课时"),
                "复用 20005 时文案必须写明原因，否则与「重复上架」分不开："
                        + rejected.path("msg").asText());
    }

    @Test
    @DisplayName("§1.6 规则 3：0→1、1→2、2→1 合法；重复上架、草稿直接下架 → 20005")
    void statusTransitions() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token);
        courseFixtures.lesson(1968000000000009404L, CourseFixtures.C_ROOT, chapter,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);

        JsonNode draftToOff = client.putWithToken(
                "/api/v1/course/courses/" + CourseFixtures.C_ROOT + "/shelf", token,
                "{\"targetStatus\":2}");
        assertEquals(20005, code(draftToOff), "草稿不可直接下架");

        assertEquals(200, code(shelf(token, 1)));
        assertEquals(1, courseFixtures.statusOf(CourseFixtures.C_ROOT));

        assertEquals(20005, code(shelf(token, 1)), "重复上架");

        assertEquals(200, code(shelf(token, 2)));
        assertEquals(2, courseFixtures.statusOf(CourseFixtures.C_ROOT));

        assertEquals(200, code(shelf(token, 1)), "2→1 再上架合法");
    }

    @Test
    @DisplayName("§1.6 规则 1：targetStatus 只允许 1 / 2，传 0 被参数校验拦下（400）")
    void targetStatusZeroIsRejected() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode rejected = shelf(token, 0);
        assertEquals(400, code(rejected));
    }

    @Test
    @DisplayName("被授权者不可上下架 → 403（契约 §2.5 规则 8）")
    void grantedNodeCannotShelf() throws Exception {
        // 【被授权者换成管理员 A1】教师已无该写权限（V202608210200），
        // 继续用教师会让这条 403 【绿着退化】：判定从「可见但非 owner」
        // 变成「压根没这个权限」，而本条要证的正是前者。A1 有权限、只是不是 owner。

        // ⚠【F-114 再换一次演员】收窄之后 A1 会在【机构根闸】处 403，本条会绿着退化成
        //   「A1 碰不到这个端点」。换成机构根 ROOT + TA 拥有的资源：ROOT 过得了机构根闸、
        //   也有对应权限位，403 才真的来自归属判定。与 F-110 那轮从教师换到 A1 同一形状。
        courseFixtures.grantCourse(CourseFixtures.C_TA, CourseFixtures.ROOT, CourseFixtures.TENANT_ID);
        String token = loginAs(CourseFixtures.ROOT);
        assertEquals(403, code(shelf(token, CourseFixtures.C_TA, 1)),
                "演员是【机构根】ROOT，他过得了 F-114 的机构根闸、也有 course:course:status，"
                        + "403 只可能来自归属判定");
    }

    // =====================================================================

    private JsonNode shelf(String token, int targetStatus) throws Exception {
        return shelf(token, CourseFixtures.C_ROOT, targetStatus);
    }

    /** 指定课程的重载 —— 「非 owner 不可上下架」那条要拿 TA 的课程来验。 */
    private JsonNode shelf(String token, long courseId, int targetStatus) throws Exception {
        return client.putWithToken("/api/v1/course/courses/" + courseId + "/shelf",
                token, "{\"targetStatus\":" + targetStatus + "}");
    }

    /**
     * 走夹具而不是调章节接口：本类验的是<b>上架</b>，章节只是个容器。
     * 这样课程相关的提交不依赖章节接口是否已存在（每个提交独立可回滚）。
     */
    private long createChapter(String token) {
        long chapterId = 1968000000000008101L;
        courseFixtures.chapter(chapterId, CourseFixtures.C_ROOT, 0L, "第一章", 1,
                CourseFixtures.TENANT_ID);
        return chapterId;
    }
}
