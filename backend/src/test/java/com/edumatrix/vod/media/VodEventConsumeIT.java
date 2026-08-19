package com.edumatrix.vod.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestSupportConfiguration.FakeVodEventSource;
import com.edumatrix.support.TestSupportConfiguration.FakeVodMediaClient;
import com.edumatrix.vod.media.service.VodEventConsumeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转码事件消费链路（03-03 §7.2、契约 §2.8）。
 *
 * <p>喂进去的是<b>原始 JSON 文本</b>，走 {@code VodEventPayloadParser} ——
 * 与生产同一段解析，不是另一份手搓的。
 */
@IntegrationTest
class VodEventConsumeIT extends CourseIntegrationTestBase {

    private static final String FILE_ID_OK = "vod-it08-" + CourseFixtures.VIDEO_OK;
    private static final String FILE_ID_TRANSCODING = "vod-it08-" + CourseFixtures.VIDEO_TRANSCODING;

    @Autowired
    private VodEventConsumeService consumeService;
    @Autowired
    private FakeVodEventSource queue;
    @Autowired
    private FakeVodMediaClient cloud;

    @BeforeEach
    void resetCloudAndQueue() {
        queue.reset();
        cloud.reset();
    }

    private static String transcodeComplete(String fileId, String status) {
        return """
                {"Status":"%s","VideoId":"%s","EventType":"TranscodeComplete",\
                "EventTime":"2026-08-19T19:14:31Z","StreamInfos":[{"Status":"%s","IsAudio":false,\
                "Size":9486668,"Definition":"SD","Duration":52.233433,"Encrypt":true,\
                "FileUrl":"http://outin-x/y.m3u8","Format":"m3u8"}]}"""
                .formatted(status, fileId, status);
    }

    // =====================================================================
    // 【要求 2】Forbidden.IllegalStatus 与「返回成功但挑不到流」是两条路
    // =====================================================================

    /**
     * 云端未就绪 → <b>不删消息、不改状态</b>，下一轮重来。
     *
     * <p>事件到达与云端状态翻转之间有时间差；混进「挑不到流」那条的后果是实的：
     * 撞上一次就把一条<b>本来好好的视频永久标成转码失败</b>。
     */
    @Test
    @DisplayName("§7.2 云端未就绪(Forbidden.IllegalStatus)：消息【不删】、状态不动，下轮重来")
    void notReadyKeepsMessageAndStatus() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.notReady = true;
        queue.offer("r-notready", transcodeComplete(FILE_ID_TRANSCODING, "success"));

        consumeService.consumeOnce();

        assertEquals(1, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "未就绪不是失败 —— 置 3 会把一条好视频永久标成转码失败");
        assertFalse(queue.deletedContains("r-notready"),
                "未就绪时【不能删消息】，否则这条转码完成事件永久消失");
    }

    /** 同一入口、同一事件，只把云端换成「返回成功但挑不到流」→ 走另一条路：置 3。 */
    @Test
    @DisplayName("§7.2 返回成功但挑不到 m3u8+加密 → 置 status=3 并删消息（与未就绪【不同分支】）")
    void unpickableStreamsFailTranscode() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.streams = java.util.List.of();   // 调用成功，但一路都挑不出
        queue.offer("r-empty", transcodeComplete(FILE_ID_TRANSCODING, "success"));

        consumeService.consumeOnce();

        assertEquals(3, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "契约 §1 第 3 条：挑不到必须置 3 并告警，【绝不可置 2】");
        assertNull(hlsUrlOf(CourseFixtures.VIDEO_TRANSCODING),
                "hls_url 必须仍为 NULL —— status=2 而 hls_url 空是「看起来成功了」的失败");
        assertTrue(queue.deletedContains("r-empty"));
    }

    // =====================================================================
    // 成功链路 + 幂等（CAS）
    // =====================================================================

    @Test
    @DisplayName("§7.2 成功：status→2、hls_url/duration/size_bytes 回填，cover_url【不写】")
    void successFillsPlaybackFieldsButNotCoverUrl() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.oneEncryptedHls("52.233433");
        queue.offer("r-ok", transcodeComplete(FILE_ID_TRANSCODING, "success"));

        consumeService.consumeOnce();

        assertEquals(2, statusOf(CourseFixtures.VIDEO_TRANSCODING));
        assertEquals("https://vod.example.cn/x.m3u8", hlsUrlOf(CourseFixtures.VIDEO_TRANSCODING));
        assertEquals(53, durationOf(CourseFixtures.VIDEO_TRANSCODING), "52.233433 秒 ceil 取整");
        assertNull(coverUrlOf(CourseFixtures.VIDEO_TRANSCODING),
                "GetPlayInfo 的 CoverURL 是 http:// 且带 Expires 签名 —— 存进去既是混合内容"
                        + "又迟早过期。按 D-2 先例不落库，口径待需方定（F 清单已登记）");
    }

    /**
     * 重复投递：第二次<b>不产生第二次 {@code GetPlayInfo} 调用</b>。
     *
     * <p>靠的是反查已经读到的 {@code status} 做前置检查 —— <b>不依赖任何外部存储</b>（F-52）。
     */
    @Test
    @DisplayName("§7.2 同一条消息投两次：第二次跳过，且【不产生第二次 GetPlayInfo】")
    void duplicateEventDoesNotCallGetPlayInfoTwice() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.oneEncryptedHls("52.2");

        queue.offer("r-1", transcodeComplete(FILE_ID_TRANSCODING, "success"));
        consumeService.consumeOnce();
        queue.offer("r-2", transcodeComplete(FILE_ID_TRANSCODING, "success"));
        consumeService.consumeOnce();

        assertEquals(2, statusOf(CourseFixtures.VIDEO_TRANSCODING));
        assertEquals(1, cloud.playInfoCalls(),
                "第二次必须在调云【之前】就被前置检查挡掉（04「做完什么算做完」逐字）");
        assertTrue(queue.deletedContains("r-2"));
    }

    /** 契约 §2.8 点名的场景：先失败、后成功，第二条<b>不能</b>被误判为重复。 */
    @Test
    @DisplayName("§2.8 先 fail 后 success：第二条被处理（Status 参与判定，前置集含 3）")
    void failureThenSuccessIsNotTreatedAsDuplicate() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        queue.offer("r-fail", transcodeComplete(FILE_ID_TRANSCODING, "fail"));
        consumeService.consumeOnce();
        assertEquals(3, statusOf(CourseFixtures.VIDEO_TRANSCODING));

        cloud.oneEncryptedHls("52.2");
        queue.offer("r-succ", transcodeComplete(FILE_ID_TRANSCODING, "success"));
        consumeService.consumeOnce();

        assertEquals(2, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "只用 vod_file_id+EventType 会把这条误判成重复而丢弃（契约 §2.8 逐字）");
    }

    // =====================================================================
    // 【要求 3】CAS 落 0 行：A 真幂等 与 B 前置集之外，必须分开
    // =====================================================================

    /**
     * <b>B 档</b>：行处在前置集之外（这里是人工禁用 9）。
     * <b>WARN + 指标</b>，绝不能与 A 一样静悄悄地当成「已处理」——
     * B 恰恰是「转码成功了但状态没推进」唯一的信号。
     */
    @Test
    @DisplayName("§2.8 前置集之外(status=9)：计入 orphan{reason=unexpected_status} + 写 sys_oper_log")
    void unexpectedStatusIsReportedNotSilentlySkipped() {
        setStatus(CourseFixtures.VIDEO_OK, 9);
        long before = operLogCount("转码事件状态异常");

        queue.offer("r-b", transcodeComplete(FILE_ID_OK, "success"));
        consumeService.consumeOnce();

        assertEquals(9, statusOf(CourseFixtures.VIDEO_OK), "不改状态");
        assertEquals(before + 1, operLogCount("转码事件状态异常"),
                "B 档必须有人看得见 —— 与 A（真幂等，只 INFO）不是一回事");
        assertTrue(queue.deletedContains("r-b"), "仍要删消息，留着会无限重投");
        assertEquals(0, cloud.playInfoCalls(), "前置检查在调云之前");
    }

    /** <b>A 档</b>：行已在目标状态 = 重复投递，只 INFO，<b>不</b>写 sys_oper_log。 */
    @Test
    @DisplayName("§2.8 已在目标状态(2)：判为真幂等，只删消息，【不】写 sys_oper_log")
    void alreadyAtTargetIsPlainIdempotent() {
        setStatus(CourseFixtures.VIDEO_OK, 2);
        long before = operLogCount("转码事件状态异常");

        queue.offer("r-a", transcodeComplete(FILE_ID_OK, "success"));
        consumeService.consumeOnce();

        assertEquals(before, operLogCount("转码事件状态异常"),
                "A 档不该报警 —— 报了就等于把正常的重复投递变成噪声，最后没人看 B");
        assertTrue(queue.deletedContains("r-a"));
    }

    // =====================================================================
    // 孤儿：两种成因在指标上可分
    // =====================================================================

    @Test
    @DisplayName("§2.8 反查不到媒资：写 sys_oper_log(tenant_id=0) + 删消息")
    void orphanEventIsRecordedAndDeleted() {
        long before = operLogCount("转码事件孤儿");
        queue.offer("r-orphan", transcodeComplete("no-such-file-id", "success"));

        consumeService.consumeOnce();

        assertEquals(before + 1, operLogCount("转画事件孤儿".replace("画", "码")));
        assertTrue(queue.deletedContains("r-orphan"), "不删会被无限重投");
        assertTrue(platformTenantOperLogExists("转码事件孤儿"),
                "孤儿事件不属于任何租户 —— tenant_id=0，只有超管读得到，而处置它的本来也只有平台运维");
    }

    @Test
    @DisplayName("§7.2 StreamTranscodeComplete：状态一动不动，消息被删（不得用于状态跃迁）")
    void streamTranscodeCompleteNeverAdvancesStatus() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        queue.offer("r-stream", """
                {"EventType":"StreamTranscodeComplete","Status":"success","VideoId":"%s"}"""
                .formatted(FILE_ID_TRANSCODING));

        consumeService.consumeOnce();

        assertEquals(1, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "低清先转完就置 2 的话，高清地址永远写不进去");
        assertTrue(queue.deletedContains("r-stream"));
    }

    @Test
    @DisplayName("§7.2 媒资已被人工删除：删消息 + INFO，【不计孤儿】（否则每删一个就是一次假警报）")
    void deletedVideoEventIsNotCountedAsOrphan() {
        long before = operLogCount("转码事件孤儿");
        queue.offer("r-del", transcodeComplete("vod-it08-" + CourseFixtures.VIDEO_DELETED, "success"));

        consumeService.consumeOnce();

        assertEquals(before, operLogCount("转码事件孤儿"));
        assertTrue(queue.deletedContains("r-del"));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private int statusOf(long videoId) {
        Integer s = jdbcTemplate.queryForObject(
                "SELECT status FROM vod_video WHERE id = ?", Integer.class, videoId);
        return s == null ? -1 : s;
    }

    private String hlsUrlOf(long videoId) {
        return jdbcTemplate.queryForObject(
                "SELECT hls_url FROM vod_video WHERE id = ?", String.class, videoId);
    }

    private String coverUrlOf(long videoId) {
        return jdbcTemplate.queryForObject(
                "SELECT cover_url FROM vod_video WHERE id = ?", String.class, videoId);
    }

    private int durationOf(long videoId) {
        Integer d = jdbcTemplate.queryForObject(
                "SELECT duration FROM vod_video WHERE id = ?", Integer.class, videoId);
        return d == null ? -1 : d;
    }

    private void setStatus(long videoId, int status) {
        jdbcTemplate.update("UPDATE vod_video SET status = ?, hls_url = NULL, cover_url = NULL "
                + "WHERE id = ?", status, videoId);
    }

    private long operLogCount(String action) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_oper_log WHERE action = ?", Long.class, action);
        return n == null ? 0 : n;
    }

    private boolean platformTenantOperLogExists(String action) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_oper_log WHERE action = ? AND tenant_id = 0",
                Long.class, action);
        return n != null && n > 0;
    }
}
