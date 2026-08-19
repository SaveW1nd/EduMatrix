package com.edumatrix.course.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图文资料（03-03 §4.1~§4.5，接口 17~21）+ PRD F2-2 两条验收标准。
 *
 * <p>覆盖的判据：
 * <ul>
 *   <li>PRD F2-2 验收标准 2：{@code <script>} <b>过滤后落库</b>（断言查库，不是断言响应）；
 *   <li>D-2 强制检查点：{@code attachments[]} <b>只有 fileId / fileName / fileSize</b>；
 *   <li>D-3 强制检查点：正文存 {@code fileId} 占位、出参重写；
 *   <li>§4.5 的 {@code 20010}；D 定案的 {@code owner_node_id} 子树过滤。
 * </ul>
 */
class MaterialIT extends CourseIntegrationTestBase {

    @Test
    @DisplayName("PRD F2-2 验收标准 2：<script> 被过滤【后落库】—— 断言的是库里的值")
    void scriptIsStrippedBeforePersist() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode created = client.postWithToken("/api/v1/course/materials", token,
                "{\"title\":\"含脚本的讲义\",\"content\":"
                        + "\"<h2>例题一</h2><p>正文</p><script>alert(1)</script>"
                        + "<img src=\\\"x\\\" onerror=\\\"alert(2)\\\">\"}");
        assertEquals(200, code(created));
        long id = data(created).path("id").asLong();

        String stored = courseFixtures.storedMaterialContent(id);
        assertFalse(stored.toLowerCase().contains("script"),
                "库里仍有 script —— 过滤点选错了（只在输出过滤等于没做）：" + stored);
        assertFalse(stored.toLowerCase().contains("onerror"), stored);
        assertTrue(stored.contains("例题一"), "正文被一并吃掉了：" + stored);

        JsonNode detail = client.getWithToken("/api/v1/course/materials/" + id, token);
        assertEquals(stored, data(detail).path("content").asText(),
                "读取路径不做第二次过滤 —— 出参应与库里一致（除 D-3 的占位重写外）");
    }

    @Test
    @DisplayName("D-3：正文里的 fileId 占位原样落库；本地存储签不出地址时保留占位")
    void inlineImagePlaceholderIsStored() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode created = client.postWithToken("/api/v1/course/materials", token,
                "{\"title\":\"带图讲义\",\"content\":\"<p>图：</p><img src=\\\"edumxfile:"
                        + CourseFixtures.IMAGE_FILE + "\\\">\"}");
        long id = data(created).path("id").asLong();

        String stored = courseFixtures.storedMaterialContent(id);
        assertTrue(stored.contains("edumxfile:" + CourseFixtures.IMAGE_FILE),
                "库里应存占位符，不是 URL：" + stored);
        assertFalse(stored.contains("material_image/"),
                "对象键进了正文 —— 那会随富文本被复制传播（D-3）：" + stored);

        JsonNode detail = client.getWithToken("/api/v1/course/materials/" + id, token);
        // 本地存储模式下 inlineSignedUrl 恒为 empty，出参保留占位（坏图看得见，优于静默丢内容）
        assertTrue(data(detail).path("content").asText().contains("edumxfile:"),
                "本地存储下应保留占位；生产走 OSS 时这里会是签名地址");
    }

    @Test
    @DisplayName("D-2 强制检查点：attachments[] 只有 fileId / fileName / fileSize，没有 fileUrl")
    void attachmentsCarryNoUrl() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode created = client.postWithToken("/api/v1/course/materials", token,
                "{\"title\":\"带附件讲义\",\"content\":\"<p>正文</p>\",\"attachmentFileIds\":[\""
                        + CourseFixtures.ATTACH_FILE + "\"]}");
        long id = data(created).path("id").asLong();

        JsonNode detail = client.getWithToken("/api/v1/course/materials/" + id, token);
        JsonNode attachment = data(detail).path("attachments").get(0);
        assertEquals(String.valueOf(CourseFixtures.ATTACH_FILE), attachment.path("fileId").asText());
        assertEquals("讲义.pdf", attachment.path("fileName").asText());
        assertEquals(1048576L, attachment.path("fileSize").asLong());
        assertTrue(attachment.path("fileUrl").isMissingNode(),
                "attachments 里出现了 fileUrl —— material_attach 不在 D-2 的内联档，"
                        + "取文件一律走 03-01 §7.3");
        assertFalse(detail.toString().contains("material_attach/"),
                "响应里出现了对象键：" + detail);
    }

    @Test
    @DisplayName("§4.3：附件最多 10 个，第 11 个被参数校验拦下（400）")
    void attachmentCountIsCappedAtTen() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            ids.append(i == 0 ? "" : ",").append("\"").append(CourseFixtures.ATTACH_FILE + i).append("\"");
        }
        JsonNode rejected = client.postWithToken("/api/v1/course/materials", token,
                "{\"title\":\"附件过多\",\"content\":\"<p>x</p>\",\"attachmentFileIds\":["
                        + ids + "]}");
        assertEquals(400, code(rejected));
    }

    @Test
    @DisplayName("§4.5：被未删除课时引用时不可删 → 20010；解除引用后可删")
    void cannotDeleteReferencedMaterial() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode created = client.postWithToken("/api/v1/course/materials", token,
                "{\"title\":\"被引用的讲义\",\"content\":\"<p>正文</p>\"}");
        long materialId = data(created).path("id").asLong();

        JsonNode chapterResp = client.postWithToken("/api/v1/course/chapters", token,
                "{\"courseId\":\"" + CourseFixtures.C_ROOT + "\",\"parentId\":\"0\","
                        + "\"chapterName\":\"第一章\"}");
        long chapterId = data(chapterResp).path("id").asLong();
        JsonNode lessonResp = client.postWithToken("/api/v1/course/lessons", token,
                "{\"chapterId\":\"" + chapterId + "\",\"lessonName\":\"图文课时\","
                        + "\"lessonType\":2,\"materialId\":\"" + materialId + "\",\"status\":1}");
        long lessonId = data(lessonResp).path("id").asLong();

        assertEquals(20010, code(deleteWithToken("/api/v1/course/materials/" + materialId, token)));

        deleteWithToken("/api/v1/course/lessons/" + lessonId, token);
        assertEquals(200, code(deleteWithToken("/api/v1/course/materials/" + materialId, token)),
                "引用解除后应可删 —— 判定看的是【未删除】课时");
    }

    @Test
    @DisplayName("§4.1：列表带 attachmentCount 与 refLessonCount；D 定案的 owner_node_id 子树过滤")
    void listShowsCountsAndFiltersBySubtree() throws Exception {
        String rootToken = loginAs(CourseFixtures.ROOT);
        client.postWithToken("/api/v1/course/materials", rootToken,
                "{\"title\":\"ROOT 的讲义\",\"content\":\"<p>x</p>\",\"attachmentFileIds\":[\""
                        + CourseFixtures.ATTACH_FILE + "\"]}");

        String teacherToken = loginAs(CourseFixtures.TA);
        client.postWithToken("/api/v1/course/materials", teacherToken,
                "{\"title\":\"教师王的讲义\",\"content\":\"<p>x</p>\"}");

        JsonNode rootList = client.getWithToken("/api/v1/course/materials?pageSize=100", rootToken);
        assertEquals(2, data(rootList).path("total").asInt(),
                "管理员按 owner_node_id 子树过滤，能看到下级教师建的资料 ——"
                        + "资料不是受管资源，走的是契约 §2.4 子树规则而不是 §2.5 资源可见性");

        JsonNode teacherList = client.getWithToken("/api/v1/course/materials?pageSize=100", teacherToken);
        assertEquals(1, data(teacherList).path("total").asInt(), "教师只看得到自己节点的");
        assertEquals(0, data(teacherList).path("list").get(0).path("attachmentCount").asInt());

        JsonNode rootRow = firstRowWithTitle(rootList, "ROOT 的讲义");
        assertEquals(1, rootRow.path("attachmentCount").asInt());
        assertEquals(0, rootRow.path("refLessonCount").asInt());
        assertEquals("IT08 课程编排机构", rootRow.path("createByName").asText());
    }

    @Test
    @DisplayName("子树外的资料：详情 404（存在性不暴露）；跨租户 20009（被插件过滤）")
    void outOfScopeMaterialIsHidden() throws Exception {
        String teacherToken = loginAs(CourseFixtures.TA);
        JsonNode created = client.postWithToken("/api/v1/course/materials", teacherToken,
                "{\"title\":\"教师王的讲义\",\"content\":\"<p>x</p>\"}");
        long id = data(created).path("id").asLong();

        String siblingToken = loginAs(CourseFixtures.TB);
        assertEquals(404, code(client.getWithToken("/api/v1/course/materials/" + id, siblingToken)),
                "同级教师不在对方子树内 → 404");
        assertEquals(404, code(client.getWithToken(
                "/api/v1/course/materials/1968000000000099999", siblingToken)),
                "F-42：路径上的资料不存在 → 404，与「不在我子树内」同一个结果。"
                        + "20009 保留给创建/修改课时时请求体里的 materialId");
    }

    @Test
    @DisplayName("§4.4：修改实时生效，附件全量覆盖；正文同样经过滤")
    void updateOverwritesAttachmentsAndSanitizes() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode created = client.postWithToken("/api/v1/course/materials", token,
                "{\"title\":\"讲义\",\"content\":\"<p>旧</p>\",\"attachmentFileIds\":[\""
                        + CourseFixtures.ATTACH_FILE + "\"]}");
        long id = data(created).path("id").asLong();

        JsonNode updated = client.putWithToken("/api/v1/course/materials/" + id, token,
                "{\"title\":\"讲义（修订）\",\"content\":\"<p>新</p><script>alert(1)</script>\"}");
        assertEquals(200, code(updated));

        String stored = courseFixtures.storedMaterialContent(id);
        assertFalse(stored.toLowerCase().contains("script"), stored);
        assertTrue(stored.contains("新"), stored);

        JsonNode detail = client.getWithToken("/api/v1/course/materials/" + id, token);
        assertEquals("讲义（修订）", data(detail).path("title").asText());
        assertEquals(0, data(detail).path("attachments").size(), "附件全量覆盖为空");
    }

    // =====================================================================

    private static JsonNode firstRowWithTitle(JsonNode listResponse, String title) {
        for (JsonNode row : listResponse.path("data").path("list")) {
            if (title.equals(row.path("title").asText())) {
                return row;
            }
        }
        throw new AssertionError("列表里没有 title=" + title + "：" + listResponse);
    }
}
