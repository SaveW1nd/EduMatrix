package com.edumatrix.course.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 课时管理（03-03 §3.1~§3.5，接口 12~16）—— <b>三个错误码的分工是本类的主线</b>。
 *
 * <table border="1">
 *   <caption>被钉住的分工</caption>
 *   <tr><th>码</th><th>场景</th><th>用例</th></tr>
 *   <tr><td>{@code 20019}</td><td>该传的没传（参数形状）</td><td>{@link #missingResourceIdIs20019}</td></tr>
 *   <tr><td>{@code 20008}</td><td>关联视频不存在 / 状态不可用</td>
 *       <td>{@link #visibleLessonRequiresTranscodedVideo}</td></tr>
 *   <tr><td>{@code 20009}</td><td>关联图文资料不存在</td><td>{@link #missingMaterialIs20009}</td></tr>
 *   <tr><td>{@code 20007}</td><td>章节不属于该课程（G 定案的新码）</td>
 *       <td>{@link #chapterFromAnotherCourseIs20007}</td></tr>
 *   <tr><td>{@code 20003}</td><td><b>不出现在本类</b> —— 它只用于上架与发凭证</td>
 *       <td>{@link #never20003OnLessonWrite}</td></tr>
 * </table>
 */
class LessonIT extends CourseIntegrationTestBase {

    @Test
    @DisplayName("PRD F2-1 验收标准 2：视频 status=1 转码中时置课时可见 → 20008（不是 20003）")
    void visibleLessonRequiresTranscodedVideo() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");

        JsonNode rejected = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_TRANSCODING, null, 1));
        assertEquals(20008, code(rejected),
                "必须是 20008；20003 只用于上架与发放播放凭证（模块 08「禁止事项」）");
        assertTrue(rejected.path("msg").asText().contains("status=1"),
                "文案要说清视频当前状态：" + rejected.path("msg").asText());
    }

    @Test
    @DisplayName("B 定案：目标 status=0 隐藏时不校验视频状态；再置为可见才被拦 → 20008")
    void hiddenLessonMayReferenceTranscodingVideo() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");

        JsonNode created = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_TRANSCODING, null, 0));
        assertEquals(200, code(created),
                "B 定案：转码中也允许先建隐藏课时（PRD F2-1 规则 4 的『才允许置为可见』）");
        long lessonId = data(created).path("id").asLong();

        JsonNode toVisible = client.putWithToken("/api/v1/course/lessons/" + lessonId, token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_TRANSCODING, null, 1));
        assertEquals(20008, code(toVisible), "置为可见这一步必须被拦住 —— 这才是那条验收标准");
    }

    @Test
    @DisplayName("§3.3 规则 2 / 3：该传的没传 → 20019（参数形状，不是资源本身的问题）")
    void missingResourceIdIs20019() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");

        JsonNode noVideo = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, null, null, 1));
        assertEquals(20019, code(noVideo));

        JsonNode noMaterial = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 2, null, null, 1));
        assertEquals(20019, code(noMaterial));
    }

    @Test
    @DisplayName("§3.3 规则 2：媒资已逻辑删除 → 20008（与「状态不可用」同一个码）")
    void deletedVideoIs20008() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        JsonNode rejected = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_DELETED, null, 1));
        assertEquals(20008, code(rejected));
    }

    @Test
    @DisplayName("§3.3 规则 3：materialId 指向不存在的资料 → 20009")
    void missingMaterialIs20009() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        JsonNode rejected = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 2, null, 1968000000000099999L, 1));
        assertEquals(20009, code(rejected));
    }

    @Test
    @DisplayName("§3.3 规则 1：chapterId 不属于目标课程 → 20007（G 定案启用的预留号位）")
    void chapterFromAnotherCourseIs20007() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");

        // 另建一门课与它自己的章
        JsonNode other = client.postWithToken("/api/v1/course/courses", token,
                "{\"courseName\":\"另一门课\"}");
        long otherCourse = data(other).path("id").asLong();
        JsonNode otherChapterResp = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + otherCourse + "\",\"parentId\":\"0\",\"chapterName\":\"别的章\"}");
        long otherChapter = data(otherChapterResp).path("id").asLong();

        JsonNode created = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        long lessonId = data(created).path("id").asLong();

        JsonNode moved = client.putWithToken("/api/v1/course/lessons/" + lessonId, token,
                lessonBody(otherChapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        assertEquals(20007, code(moved), "跨课程移动课时必须被拒");

        JsonNode ghost = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(1968000000000099998L, 1, CourseFixtures.VIDEO_OK, null, 1));
        assertEquals(20007, code(ghost), "章节不存在同样是 20007");
    }

    @Test
    @DisplayName("课时写操作全程不出现 20003 —— 那个码只属于上架与发凭证")
    void never20003OnLessonWrite() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        for (String body : new String[]{
                lessonBody(chapter, 1, CourseFixtures.VIDEO_TRANSCODING, null, 1),
                lessonBody(chapter, 1, CourseFixtures.VIDEO_DELETED, null, 1),
                lessonBody(chapter, 1, null, null, 1)}) {
            JsonNode resp = client.postWithToken("/api/v1/course/lessons", token, body);
            org.junit.jupiter.api.Assertions.assertNotEquals(20003, code(resp),
                    "课时侧用了 20003：" + resp);
        }
    }

    @Test
    @DisplayName("§3.3 规则 2：duration 冗余自 vod_video，请求体不接受；图文课时 duration=0")
    void durationIsCopiedFromVideoAndZeroForMaterial() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        long materialId = createMaterial(token);

        JsonNode videoLesson = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        long videoLessonId = data(videoLesson).path("id").asLong();
        assertEquals(CourseFixtures.VIDEO_OK_DURATION, courseFixtures.lessonDurationOf(videoLessonId));

        JsonNode materialLesson = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 2, null, materialId, 1));
        assertEquals(0, courseFixtures.lessonDurationOf(data(materialLesson).path("id").asLong()));
    }

    @Test
    @DisplayName("§3.4 换类型：video_id 与 content_id 不会同时挂着")
    void switchingTypeClearsTheOtherForeignKey() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        long materialId = createMaterial(token);

        JsonNode created = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        long lessonId = data(created).path("id").asLong();

        JsonNode switched = client.putWithToken("/api/v1/course/lessons/" + lessonId, token,
                lessonBody(chapter, 2, null, materialId, 1));
        assertEquals(200, code(switched));

        JsonNode detail = client.getWithToken("/api/v1/course/lessons/" + lessonId, token);
        assertTrue(data(detail).path("videoId").isNull(), "换成图文后 videoId 应被清空");
        assertEquals(String.valueOf(materialId), data(detail).path("materialId").asText());
        assertEquals(0, data(detail).path("duration").asInt());
        assertTrue(data(detail).path("videoStatus").isNull());
    }

    @Test
    @DisplayName("§3.1 / §3.2：列表带 videoStatus 与 chapterName；课时不存在 → 20014")
    void listAndDetail() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        JsonNode created = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        long lessonId = data(created).path("id").asLong();

        JsonNode list = client.getWithToken(
                "/api/v1/course/lessons?courseId=" + CourseFixtures.C_ROOT, token);
        assertEquals(1, data(list).path("total").asInt());
        JsonNode row = data(list).path("list").get(0);
        assertEquals(2, row.path("videoStatus").asInt(), "已转码媒资的 status=2");
        assertEquals("第一章", row.path("chapterName").asText());

        JsonNode detail = client.getWithToken("/api/v1/course/lessons/" + lessonId, token);
        assertEquals("已转码视频", data(detail).path("videoName").asText());

        JsonNode missing = client.getWithToken("/api/v1/course/lessons/1968000000000099999", token);
        assertEquals(20014, code(missing));
    }

    @Test
    @DisplayName("PRD F2-1 验收标准 4：非 owner 新增课时被拒（403）")
    void nonOwnerCannotAddLesson() throws Exception {
        String ownerToken = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(ownerToken, "第一章");
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.TB, CourseFixtures.TENANT_ID);

        String grantedToken = loginAs(CourseFixtures.TB);
        JsonNode rejected = client.postWithToken("/api/v1/course/lessons", grantedToken,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        assertEquals(403, code(rejected), "被授权方只能用不能改（PRD F2-1 规则 8）");
    }

    @Test
    @DisplayName("§3.5 删除课时：逻辑删除，进度数据不在本模块清理")
    void deleteLesson() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long chapter = createChapter(token, "第一章");
        JsonNode created = client.postWithToken("/api/v1/course/lessons", token,
                lessonBody(chapter, 1, CourseFixtures.VIDEO_OK, null, 1));
        long lessonId = data(created).path("id").asLong();

        assertEquals(200, code(deleteWithToken("/api/v1/course/lessons/" + lessonId, token)));
        assertEquals(0, courseFixtures.liveLessonCount(CourseFixtures.C_ROOT));
        assertTrue(courseFixtures.deletedAtOf("crs_lesson", lessonId) > 0);
        assertNull(courseFixtures.lessonDurationOf(lessonId) == null ? null
                : null, "占位：duration 列仍在，不做物理删除");
    }

    // =====================================================================

    private long createChapter(String token, String name) throws Exception {
        JsonNode created = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + CourseFixtures.C_ROOT + "\",\"parentId\":\"0\","
                        + "\"chapterName\":\"" + name + "\"}");
        assertEquals(200, code(created), created.toString());
        return data(created).path("id").asLong();
    }

    /** 走夹具而不是调资料接口：本类验的是课时，资料只是个被引用对象。 */
    private long createMaterial(String token) {
        long materialId = 1968000000000008201L;
        courseFixtures.material(materialId, "讲义", "<p>正文</p>",
                CourseFixtures.ROOT, CourseFixtures.TENANT_ID);
        return materialId;
    }

    private static String lessonBody(long chapterId, int lessonType, Long videoId,
                                     Long materialId, int status) {
        return "{\"chapterId\":\"" + chapterId + "\",\"lessonName\":\"课时甲\","
                + "\"lessonType\":" + lessonType
                + (videoId == null ? "" : ",\"videoId\":\"" + videoId + "\"")
                + (materialId == null ? "" : ",\"materialId\":\"" + materialId + "\"")
                + ",\"status\":" + status + "}";
    }
}
