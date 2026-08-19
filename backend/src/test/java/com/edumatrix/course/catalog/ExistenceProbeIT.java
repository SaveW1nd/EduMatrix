package com.edumatrix.course.catalog;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>F-42 的验收：按 id 访问路径上的资源时，探测不出存在性。</b>
 *
 * <h2>要比的是【两次响应本身】</h2>
 * <p>每条用例拿两个 id ——「<b>不存在</b>」与「<b>存在但不在我的可见范围内</b>」——
 * 用<b>同一个账号</b>请求<b>同一个端点</b>，断言两次的
 * {@code (HTTP 状态码, 响应体业务码)} <b>完全相等</b>。
 * 分别断言「都是 404」是不够的：那种写法在两边同时退化时也能挂住，
 * 而真正要防的是<b>两边不一样</b>。
 *
 * <p><b>两个断言都留着，不是冗余</b>：只比相等，两边同时变成 500 也会绿；
 * 只钉 404，就回到了「分别断言」。所以先比相等（这条是 F-42 的定义），
 * 再把共同值钉在 404（这条挡住整体退化）。
 *
 * <h2>为什么统一到 404 而不是统一到业务码</h2>
 * <p>契约 §2.4 三分法第 1 行「访问<b>路径上的资源</b>而该资源不在我的子树内 →
 * <b>404</b>，不暴露存在性」是上位文档，动不得；能动的只有「不存在」那一侧。
 *
 * <h2>三种资源一起改，因为它们是同一个形状</h2>
 * <table border="1">
 *   <caption>改动前后</caption>
 *   <tr><th>资源</th><th>改前</th><th>改后</th></tr>
 *   <tr><td>课程 {@code /courses/{id}}</td><td>不存在 {@code 20004} / 不可见 404</td><td>都是 404</td></tr>
 *   <tr><td>课时 {@code /lessons/{id}}</td><td>不存在 {@code 20014} / 不可见 404</td><td>都是 404</td></tr>
 *   <tr><td>资料 {@code /materials/{id}}</td><td>不存在 {@code 20009} / 不可见 404</td><td>都是 404</td></tr>
 *   <tr><td>章节 {@code /chapters/{id}}</td><td colspan="2"><b>本来就都是 404</b> —— 本轮的参照物，不动</td></tr>
 * </table>
 * <p>只改一种会让同一个模块里三种资源三种口径，而模块 12 / 14 照抄时不知道抄哪个。
 *
 * <h2>三个业务码都没有退役</h2>
 * <p>它们改为<b>只</b>用于「请求体 / 查询参数里显式指定的 id」——
 * 那一类<b>必须</b>保留业务码：用户主动选了一个对象，选错了要明确告诉他，
 * 返 404 会让他以为端点写错了（契约 §2.4 三分法第 3 行同一条理由）。
 * {@link #paramAddressedIdsKeepTheirBusinessCode} 把这条边界一并钉住 ——
 * 少了它，「一律改成 404」这种过度修正不会被任何测试拦下。
 */
class ExistenceProbeIT extends CourseIntegrationTestBase {

    /** 本租户内不存在的 id（雪花号段内，形状与真 id 一致）。 */
    private static final long GHOST = 1968000000000077777L;

    @Test
    @DisplayName("课程：不存在与不可见，六个端点上两两完全一致")
    void courseExistenceIsNotProbeable() throws Exception {
        // ROOT 看不见 C_TA（教师王自建）—— §0.2 两条判定取交集的必然结果
        String token = loginAs(CourseFixtures.ROOT);
        long invisible = CourseFixtures.C_TA;

        List<String[]> endpoints = List.of(
                new String[]{"GET", "/api/v1/course/courses/%d", null},
                new String[]{"PUT", "/api/v1/course/courses/%d", "{\"courseName\":\"改名\"}"},
                new String[]{"DELETE", "/api/v1/course/courses/%d", null},
                new String[]{"PUT", "/api/v1/course/courses/%d/shelf", "{\"targetStatus\":1}"},
                new String[]{"GET", "/api/v1/course/courses/%d/chapters", null},
                new String[]{"PUT", "/api/v1/course/courses/%d/chapters/sort",
                        "{\"chapters\":[{\"id\":\"1\",\"parentId\":\"0\",\"sort\":1}]}"});

        for (String[] endpoint : endpoints) {
            assertIndistinguishable(endpoint[0], endpoint[1], endpoint[2], token, GHOST, invisible);
        }
    }

    @Test
    @DisplayName("课时：不存在与「所属课程不可见」，三个端点上两两完全一致")
    void lessonExistenceIsNotProbeable() throws Exception {
        // 课时挂在教师王的课程下：课时行本身查得到（同租户），但所属课程对 ROOT 不可见
        long invisible = 1968000000000009601L;
        courseFixtures.chapter(1968000000000008601L, CourseFixtures.C_TA, 0L, "教师王的章", 1,
                CourseFixtures.TENANT_ID);
        courseFixtures.lesson(invisible, CourseFixtures.C_TA, 1968000000000008601L,
                2, null, null, 0, 1, CourseFixtures.TENANT_ID);

        String token = loginAs(CourseFixtures.ROOT);
        String body = "{\"lessonName\":\"改名\",\"lessonType\":1,\"videoId\":\""
                + CourseFixtures.VIDEO_OK + "\"}";

        assertIndistinguishable("GET", "/api/v1/course/lessons/%d", null, token, GHOST, invisible);
        assertIndistinguishable("PUT", "/api/v1/course/lessons/%d", body, token, GHOST, invisible);
        assertIndistinguishable("DELETE", "/api/v1/course/lessons/%d", null, token, GHOST, invisible);
    }

    @Test
    @DisplayName("图文资料：不存在与「不在我子树内」，三个端点上两两完全一致")
    void materialExistenceIsNotProbeable() throws Exception {
        // 教师王建的资料；用同级教师李去探（互不在对方子树内）
        String ownerToken = loginAs(CourseFixtures.TA);
        JsonNode created = client.postWithToken("/api/v1/course/materials", ownerToken,
                "{\"title\":\"教师王的讲义\",\"content\":\"<p>x</p>\"}");
        assertEquals(200, code(created), created.toString());
        long invisible = data(created).path("id").asLong();

        String token = loginAs(CourseFixtures.TB);
        String body = "{\"title\":\"改名\",\"content\":\"<p>y</p>\"}";

        assertIndistinguishable("GET", "/api/v1/course/materials/%d", null, token, GHOST, invisible);
        assertIndistinguishable("PUT", "/api/v1/course/materials/%d", body, token, GHOST, invisible);
        assertIndistinguishable("DELETE", "/api/v1/course/materials/%d", null, token, GHOST, invisible);
    }

    @Test
    @DisplayName("边界：请求体 / 查询参数里的 id 仍返业务码 —— 「一律改成 404」是过度修正")
    void paramAddressedIdsKeepTheirBusinessCode() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);

        // §2.2 创建章节：body 里的 courseId → 20004（用户请求的资源是「章节」）
        JsonNode chapter = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + GHOST + "\",\"parentId\":\"0\",\"chapterName\":\"章\"}");
        assertEquals(20004, code(chapter), "body 里的 courseId 返 404 会指代不清");

        // §3.1 课时列表：query 里的 courseId → 20004（用户请求的资源是「课时列表」）
        JsonNode lessons = client.getWithToken("/api/v1/course/lessons?courseId=" + GHOST, token);
        assertEquals(20004, code(lessons));

        // §3.3 创建课时：body 里的 materialId → 20009（用户请求的资源是「课时」）
        long chapterId = 1968000000000008701L;
        courseFixtures.chapter(chapterId, CourseFixtures.C_ROOT, 0L, "第一章", 1,
                CourseFixtures.TENANT_ID);
        JsonNode lesson = client.postWithToken("/api/v1/course/lessons", token,
                "{\"chapterId\":\"" + chapterId + "\",\"lessonName\":\"图文课时\","
                        + "\"lessonType\":2,\"materialId\":\"" + GHOST + "\",\"status\":1}");
        assertEquals(20009, code(lesson));
    }

    // =====================================================================

    /**
     * 同一账号、同一端点，两个 id 的响应必须<b>完全一致</b>。
     *
     * @param pathTemplate 含一个 {@code %d} 占位的路径
     * @param ghostId      不存在的 id
     * @param invisibleId  存在但不可见的 id
     */
    private void assertIndistinguishable(String method, String pathTemplate, String body,
                                         String token, long ghostId, long invisibleId)
            throws Exception {
        HttpOutcome ghost = outcome(method, String.format(pathTemplate, ghostId), token, body);
        HttpOutcome invisible = outcome(method, String.format(pathTemplate, invisibleId), token, body);

        assertEquals(ghost, invisible,
                method + " " + pathTemplate + "：不存在的 id 与「存在但不可见」的 id 响应不一致 —— "
                        + "拿到不同结果就能逐个 id 探出「哪些存在」（03-01 §7.2 论证过雪花 ID "
                        + "同租户内时间相邻、可近邻枚举）");
        assertEquals(new HttpOutcome(404, 404), ghost,
                method + " " + pathTemplate + "：两次一致但都不是 404 —— "
                        + "只比相等的话，两边同时退化（比如都变成 500 或都变成 200）也会绿");
    }
}
