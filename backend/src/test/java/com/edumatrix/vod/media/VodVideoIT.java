package com.edumatrix.vod.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestSupportConfiguration.FakeVodMediaClient;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块 09 五个接口的判定顺序（03-03 §7.1 / §7.3 / §7.4 / §7.5 / §7.6）。
 *
 * <p>复用模块 08 的夹具：它已经种了三条 {@code vod_video}（正常 / 转码中 / 已删除）
 * 与引用它们的课时，正好覆盖 {@code 20016} 与状态机的多数分支。
 * <b>不另起一套夹具</b> —— 那会变成第五套往共享表插固定主键的派生规则（检查⑧ 会红）。
 */
@IntegrationTest
class VodVideoIT extends CourseIntegrationTestBase {

    private static final String VIDEOS = "/api/v1/vod/videos";

    @Autowired
    private FakeVodMediaClient vodClient;

    /** {@code CourseFixtures.seed()} 只种媒资与课程，<b>不种课时</b>——要引用计数的用例自己建。 */
    private static final long LESSON_REF_VIDEO_OK = 1968000000000004001L;

    @BeforeEach
    void resetCloud() {
        vodClient.reset();
    }

    /** 建一条引用 {@code VIDEO_OK} 的可见视频课时（{@code lesson_type=1}）。 */
    private void referenceVideoOk() {
        courseFixtures.lesson(LESSON_REF_VIDEO_OK, CourseFixtures.C_ROOT, 0L, 1,
                CourseFixtures.VIDEO_OK, null, CourseFixtures.VIDEO_OK_DURATION, 1,
                CourseFixtures.TENANT_ID);
    }

    // =====================================================================
    // 接口 25 §7.1 上传凭证
    // =====================================================================

    @Test
    @DisplayName("§7.1 新建：先调云再落库，vod_file_id 发凭证时就写入（契约 §2.8 规则 1 的前提）")
    void createWritesVodFileIdAtCredentialTime() throws Exception {
        String token = loginAs(CourseFixtures.TA);
        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"新课录像","fileName":"a.mp4","fileSize":1048576}""");

        assertEquals(200, code(res));
        String videoId = data(res).path("videoId").asText();
        assertEquals(2, data(res).path("provider").asInt(), "契约 §1：阿里云是本期唯一实现");
        assertNotNull(data(res).path("credential").path("cloudVideoId").asText(null));
        assertTrue(vodClient.calls.stream().anyMatch(c -> c.startsWith("createUploadVideo")));

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM vod_video WHERE id = ?", Integer.class, Long.valueOf(videoId));
        String fileId = jdbcTemplate.queryForObject(
                "SELECT vod_file_id FROM vod_video WHERE id = ?", String.class, Long.valueOf(videoId));
        Integer encryptType = jdbcTemplate.queryForObject(
                "SELECT encrypt_type FROM vod_video WHERE id = ?", Integer.class, Long.valueOf(videoId));
        assertEquals(0, status, "新建即 0 上传中");
        assertNotNull(fileId, "阿里云路径下 vod_file_id 不得为 NULL —— 事件反查链路靠它闭合");
        assertEquals(2, encryptType,
                "R1a 定案是阿里云私有加密；DDL 默认值 1 与事实不符，必须由 Service 显式写 2");
    }

    @Test
    @DisplayName("§7.1 云调失败则一行都不落（不留 vod_file_id 为 NULL 的僵尸行）")
    void cloudFailureLeavesNoRow() throws Exception {
        String token = loginAs(CourseFixtures.TA);
        long before = countVideos();
        vodClient.failNext = true;

        client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"注定失败","fileName":"a.mp4","fileSize":1048576}""");

        assertEquals(before, countVideos(), "云调失败却落了行 —— 那是一条永远等不到事件的僵尸媒资");
    }

    @Test
    @DisplayName("§7.1 请求体里的 videoId 查不到 → 20015（param-addressed，不是 404）")
    void unknownVideoIdInBodyIs20015() throws Exception {
        String token = loginAs(CourseFixtures.TA);
        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"续签","fileName":"a.mp4","fileSize":1024,"videoId":"1968000000000009999"}""");

        assertEquals(20015, code(res),
                "F-42 的边界：用户主动选的对象选错了要明确提示，返 404 会让他以为端点写错了");
    }

    @Test
    @DisplayName("§7.1 status=2 的媒资不能续签 → 20015（仅 {0,3} 可用）")
    void refreshRejectsNormalStatus() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"x","fileName":"a.mp4","fileSize":1024,"videoId":"%d"}"""
                .formatted(CourseFixtures.VIDEO_OK));

        assertEquals(20015, code(res));
    }

    /**
     * <b>F-51</b>：重传是 {@code 3 → 1}，<b>不经过 0</b>。
     * 依据是 03-03 §9 状态机速查（第 2182 行）逐字「3 →（接口 33 / <b>接口 25 重传源文件</b>）→ 1」，
     * 与 §7.1 那句「重传成功走事件消费重新流转 0→1→2/3」冲突，取状态机速查。
     */
    @Test
    @DisplayName("§7.1+§9 重传：status 3 → 1，不经过 0（F-51）")
    void reuploadMovesFailedToTranscoding() throws Exception {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 3);
        setRemark(CourseFixtures.VIDEO_TRANSCODING, "上次失败原因");
        String token = loginAs(CourseFixtures.ROOT);

        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"x","fileName":"a.mp4","fileSize":1024,"videoId":"%d"}"""
                .formatted(CourseFixtures.VIDEO_TRANSCODING));

        assertEquals(200, code(res));
        assertEquals(1, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "重传后必须是 1 —— 置 0 的话 FileUploadComplete 的 CAS(0→1) 会命中 0 行，永远停在 3");
        assertNull(remarkOf(CourseFixtures.VIDEO_TRANSCODING),
                "remark 要清掉，否则列表同时显示「转码中」与上次的失败文案");
    }

    /**
     * <b>F-65</b>：F-51 落地后的死角 —— 置 1 之后放弃上传，两个接口都进不去。
     * <b>本轮按分册实现、不开口子</b>，用本条把行为钉住：将来若定案开口子，它会先红。
     */
    @Test
    @DisplayName("§7.1/§7.5 死角：重传后放弃上传，续签与重转【都进不去】，媒资永久停在转码中（F-65）")
    void abandonedReuploadIsStuckAtTranscoding() throws Exception {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        String token = loginAs(CourseFixtures.ROOT);

        JsonNode refresh = client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"x","fileName":"a.mp4","fileSize":1024,"videoId":"%d"}"""
                .formatted(CourseFixtures.VIDEO_TRANSCODING));
        JsonNode retry = client.postWithToken(
                VIDEOS + "/" + CourseFixtures.VIDEO_TRANSCODING + "/retranscode", token, "");

        assertEquals(20015, code(refresh), "§7.1 要 status ∈ {0,3}");
        assertEquals(20015, code(retry), "§7.5 要 status = 3");
        assertEquals(1, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "两条路都进不去，且「转码失败」待办计数已经 -1 —— 这条媒资不出现在任何待处理列表里");
    }

    // =====================================================================
    // 接口 26 §7.3 列表
    // =====================================================================

    @Test
    @DisplayName("§7.3 列表：只见自有 ∪ 被授权，不含 hlsUrl，带 refLessonCount 与 grantType")
    void listShowsOwnedOnlyAndHidesHlsUrl() throws Exception {
        referenceVideoOk();
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode res = client.getWithToken(VIDEOS + "?pageSize=100", token);

        assertEquals(200, code(res));
        JsonNode list = data(res).path("list");
        assertTrue(list.size() >= 2, "ROOT 自有的两条未删除媒资应当在");
        for (JsonNode row : list) {
            assertTrue(row.path("hlsUrl").isMissingNode(),
                    "§7.3 说明逐字：列表不返回 hls_url —— 加密地址必须经播放凭证签名");
            assertEquals(1, row.path("grantType").asInt(), "都是 ROOT 自有的");
        }
        JsonNode ok = findById(list, CourseFixtures.VIDEO_OK);
        assertEquals(1, ok.path("refLessonCount").asInt(), "VIDEO_OK 被一个未删除课时引用");
    }

    @Test
    @DisplayName("§7.3 已逻辑删除的媒资不出现在列表里")
    void listExcludesDeleted() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode list = data(client.getWithToken(VIDEOS + "?pageSize=100", token)).path("list");
        assertTrue(findById(list, CourseFixtures.VIDEO_DELETED).isMissingNode());
    }

    // =====================================================================
    // 接口 27 §7.4 删除
    // =====================================================================

    @Test
    @DisplayName("§7.4 被未删除课时引用 → 20016")
    void deleteReferencedVideoIs20016() throws Exception {
        referenceVideoOk();
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode res = deleteWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK, token);
        assertEquals(20016, code(res));
    }

    @Test
    @DisplayName("§7.4 无引用可删；云端源文件不随删清理（平台级异步策略，默认保留 30 天）")
    void deleteUnreferencedVideoSucceeds() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode res = deleteWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_TRANSCODING, token);

        assertEquals(200, code(res));
        Long deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM vod_video WHERE id = ?", Long.class,
                CourseFixtures.VIDEO_TRANSCODING);
        assertTrue(deletedAt != null && deletedAt > 0, "逻辑删除写毫秒时间戳，不是物理删");
    }

    // =====================================================================
    // 接口 34 §7.6 禁用/启用
    // =====================================================================

    @Test
    @DisplayName("§7.6 targetStatus 非 2/9 → 400（在查库【之前】拦下）")
    void illegalTargetStatusIs400() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        JsonNode res = client.putWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK + "/status", token,
                """
                {"targetStatus":3}""");
        assertEquals(400, code(res), "§7.6 逐字「其余值返回 400」——这是参数校验不是业务判定");
    }

    @Test
    @DisplayName("§7.6 2 ↔ 9 可切；转码中(1) 不允许人工改 → 20015（转码态一律由事件驱动）")
    void statusSwitchesOnlyBetweenTwoAndNine() throws Exception {
        referenceVideoOk();
        String token = loginAs(CourseFixtures.ROOT);

        JsonNode disable = client.putWithToken(
                VIDEOS + "/" + CourseFixtures.VIDEO_OK + "/status", token, """
                {"targetStatus":9,"remark":"版权争议临时下架"}""");
        assertEquals(200, code(disable));
        assertEquals(9, statusOf(CourseFixtures.VIDEO_OK));
        assertEquals(1, data(disable).path("refLessonCount").asInt(), "供前端提示影响面");

        JsonNode again = client.putWithToken(
                VIDEOS + "/" + CourseFixtures.VIDEO_OK + "/status", token, """
                {"targetStatus":9}""");
        assertEquals(20015, code(again), "9 → 9 不构成合法切换");

        JsonNode enable = client.putWithToken(
                VIDEOS + "/" + CourseFixtures.VIDEO_OK + "/status", token, """
                {"targetStatus":2}""");
        assertEquals(200, code(enable));

        JsonNode transcoding = client.putWithToken(
                VIDEOS + "/" + CourseFixtures.VIDEO_TRANSCODING + "/status", token, """
                {"targetStatus":9}""");
        assertEquals(20015, code(transcoding), "§7.6：0/1/3 一律 20015，转码态只由事件消费驱动");
    }

    // =====================================================================
    // 路径三分法（F-49）：不存在 / 不可见 / 非 owner
    // =====================================================================

    /**
     * <b>F-49</b>：路径上的媒资，「不存在」与「不可见」必须给出<b>完全一致</b>的响应，
     * 否则逐个 id 试一遍就能分出「哪些存在」。比的是<b>两次响应本身</b>，不是各自像不像 404。
     */
    @Test
    @DisplayName("F-49 路径上的媒资：不存在 与 存在但不可见，两次响应完全一致（都是 404）")
    void pathAddressedExistenceIsNotProbeable() throws Exception {
        String token = loginAs(CourseFixtures.TB);   // TB 与 ROOT 的媒资无授权关系

        // 用 DELETE 而不是 POST：两条都走 VodVideoAccessGuard#loadOwnedByPath 这同一个入口，
        // 而 outcome() 只支持 GET/PUT/DELETE（模块 08 建的，本条不为一个探针去改它）
        HttpOutcome missing = outcome("DELETE", VIDEOS + "/1968000000000009999", token, null);
        HttpOutcome invisible = outcome("DELETE", VIDEOS + "/" + CourseFixtures.VIDEO_OK, token, null);

        assertEquals(missing, invisible,
                "两次响应必须逐字相同 —— 不同则可拿来探测存在性（契约 §2.4 三分法第 1 行、F-42 同形状）");
        assertEquals(404, missing.httpStatus());
    }

    @Test
    @DisplayName("F-49 被授权者：可见但非 owner，写操作 403（不再收敛成 404 —— 他已知道它存在）")
    void grantedButNotOwnerGets403() throws Exception {
        grantVideoTo(CourseFixtures.VIDEO_OK, CourseFixtures.TB);
        String token = loginAs(CourseFixtures.TB);

        JsonNode res = deleteWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK, token);
        assertEquals(403, code(res), "§7.4/§7.5/§7.6 逐字：仅被授权者只读，写操作返回 403");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private long countVideos() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM vod_video WHERE tenant_id = ?", Long.class,
                CourseFixtures.TENANT_ID);
        return n == null ? 0 : n;
    }

    private int statusOf(long videoId) {
        Integer s = jdbcTemplate.queryForObject(
                "SELECT status FROM vod_video WHERE id = ?", Integer.class, videoId);
        return s == null ? -1 : s;
    }

    private String remarkOf(long videoId) {
        return jdbcTemplate.queryForObject(
                "SELECT remark FROM vod_video WHERE id = ?", String.class, videoId);
    }

    private void setStatus(long videoId, int status) {
        jdbcTemplate.update("UPDATE vod_video SET status = ? WHERE id = ?", status, videoId);
    }

    private void setRemark(long videoId, String remark) {
        jdbcTemplate.update("UPDATE vod_video SET remark = ? WHERE id = ?", remark, videoId);
    }

    /** 显式授权一条媒资给某节点（{@code resource_type=3}，契约 §2.5）。 */
    private void grantVideoTo(long videoId, long targetNodeId) {
        jdbcTemplate.update("INSERT INTO org_resource_grant (id, resource_type, resource_id, "
                        + "target_node_id, grant_source, grant_time, tenant_id, "
                        + "create_time, update_time, deleted_at) "
                        + "VALUES (?, 3, ?, ?, 1, NOW(), ?, NOW(), NOW(), 0)",
                videoId + 900000L, videoId, targetNodeId, CourseFixtures.TENANT_ID);
    }

    private static JsonNode findById(JsonNode list, long id) {
        for (JsonNode row : list) {
            if (row.path("id").asText().equals(String.valueOf(id))) {
                return row;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
