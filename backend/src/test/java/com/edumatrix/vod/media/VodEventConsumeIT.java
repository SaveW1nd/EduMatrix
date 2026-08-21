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
        return transcodeComplete(fileId, status, true);
    }

    /**
     * @param encrypted 事件里那一路的 {@code Encrypt}。
     *                  <b>F-114 第二半起它不再是准入条件</b> —— 加不加密都收，
     *                  真值由 {@code GetPlayInfo} 侧观测后落库
     */
    private static String transcodeComplete(String fileId, String status, boolean encrypted) {
        return """
                {"Status":"%s","VideoId":"%s","EventType":"TranscodeComplete",\
                "EventTime":"2026-08-19T19:14:31Z","StreamInfos":[{"Status":"%s","IsAudio":false,\
                "Size":9486668,"Definition":"SD","Duration":52.233433,"Encrypt":%s,\
                "FileUrl":"http://outin-x/y.m3u8","Format":"m3u8"}]}"""
                .formatted(status, fileId, status, encrypted);
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

    // =====================================================================
    // 【F-114 第二半】encrypt_type 记【实际观测值】，不记上传时的假设
    //
    // 下面两条【必须成对存在】：单独任何一条都可能是「恒记某个值」而照样绿。
    // 这条路此前【全库零覆盖】—— 喂「不加密的流」进消费链路的用例一个都没有，
    // 所以「传一个不加密视频 → 被判转码失败」这个缺陷能一直绿着。
    // =====================================================================

    /**
     * <b>不加密的一路 m3u8：必须被采纳，而不是被判成转码失败。</b>
     *
     * <p>改这条之前的行为：挑流条件写死 {@code Encrypt == 1} →
     * 阿里云转码<b>成功</b>而我们判「挑不出加密流」→ 置 {@code status=3} →
     * <b>控制台一切正常、我们这边显示失败，那个视频永远用不了</b>。
     */
    @Test
    @DisplayName("⚠ F-114 不加密的 m3u8：status→2、encrypt_type 记 0、hls_url 回填（旧代码在这里置 3）")
    void plainHlsIsAcceptedAndRecordedAsUnencrypted() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.onePlainHls("52.233433");
        queue.offer("r-plain", transcodeComplete(FILE_ID_TRANSCODING, "success", false));

        consumeService.consumeOnce();

        assertEquals(2, statusOf(CourseFixtures.VIDEO_TRANSCODING),
                "转码成功的不加密 m3u8 必须落 2 —— 判 3 等于把一条好视频永久废掉");
        assertEquals(0, encryptTypeOf(CourseFixtures.VIDEO_TRANSCODING),
                "记【实际观测到的】加密状态：GetPlayInfo 返回 Encrypt≠1 → 落 0");
        assertEquals("https://vod.example.cn/plain.m3u8", hlsUrlOf(CourseFixtures.VIDEO_TRANSCODING));
    }

    /**
     * <b>对照组</b>：同一条链路喂加密的一路，必须记 2。
     *
     * <p>与上一条<b>成对</b>才证明「按实际记」—— 只有不加密那条的话，
     * 把 {@code encrypt_type} 写死成 0 照样全绿（变异 M48）；
     * 只有加密这条的话，写死成 2 照样全绿（变异 M49）。
     */
    @Test
    @DisplayName("⚠ F-114 对照：加密的 m3u8 → encrypt_type 记 2（与上一条成对，缺一条就抓不住写死）")
    void encryptedHlsIsRecordedAsPrivateEncryption() {
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.oneEncryptedHls("52.233433");
        queue.offer("r-enc", transcodeComplete(FILE_ID_TRANSCODING, "success", true));

        consumeService.consumeOnce();

        assertEquals(2, statusOf(CourseFixtures.VIDEO_TRANSCODING));
        assertEquals(2, encryptTypeOf(CourseFixtures.VIDEO_TRANSCODING),
                "GetPlayInfo 返回 Encrypt=1 且 EncryptType=AliyunVoDEncryption → 落 2 私有加密");
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

    private Integer encryptTypeOf(long videoId) {
        return jdbcTemplate.queryForObject(
                "SELECT encrypt_type FROM vod_video WHERE id = ?", Integer.class, videoId);
    }

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
    // =====================================================================
    // 异步冗余刷新（§7.2 规则 9）：断言冗余值【真的变了】，不是断言「提交了任务」
    // =====================================================================

    /**
     * 转码成功后，引用该媒资的课时 {@code duration} 与所属课程 {@code total_duration}
     * <b>真的被刷新</b>。
     *
     * <h2>为什么断言的是「值变了」而不是「任务提交了」</h2>
     * <p>异步线程<b>不继承</b>租户上下文；Runnable 里漏了 {@code runWithTenant} 的表现是
     * ——任务照样提交、照样跑、异常被吞成一条没人看的 ERROR，而<b>冗余值一动不动</b>。
     * 断言「提交了任务」对这种失败<b>完全无感</b>。
     *
     * <p><b>变异验证</b>：去掉 {@code VodEventConsumeService#refreshCountersAsync} 里的
     * {@code TenantHelper.runWithTenant(...)} → 本条必红（输出见提交说明）。
     * 这是「漏了就静默不发生」唯一能被机器发现的形态。
     */
    @Test
    @DisplayName("§7.2 规则 9 异步刷新：crs_lesson.duration 与 crs_course.total_duration 真的变了")
    void asyncRefreshActuallyUpdatesRedundantColumns() throws Exception {
        long lessonId = 1968000000000004501L;
        courseFixtures.lesson(lessonId, CourseFixtures.C_ROOT, 0L, 1,
                CourseFixtures.VIDEO_TRANSCODING, null, 0, 1, CourseFixtures.TENANT_ID);
        setStatus(CourseFixtures.VIDEO_TRANSCODING, 1);
        cloud.oneEncryptedHls("52.233433");        // → ceil = 53 秒

        queue.offer("r-refresh", transcodeComplete(FILE_ID_TRANSCODING, "success"));
        consumeService.consumeOnce();

        // 异步：轮询等它跑完（不 sleep 一个拍脑袋的固定值）
        waitUntil(() -> lessonDuration(lessonId) == 53);

        assertEquals(53, lessonDuration(lessonId),
                "课时 duration 没被刷新 —— 异步 Runnable 里若漏了 runWithTenant，"
                        + "租户插件取不到租户会直接抛，而异常被吞成一条没人看的 ERROR");
        assertEquals(53, courseTotalDuration(CourseFixtures.C_ROOT),
                "所属课程 total_duration 同样要被全量重算回来");
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private int lessonDuration(long lessonId) {
        Integer d = jdbcTemplate.queryForObject(
                "SELECT duration FROM crs_lesson WHERE id = ?", Integer.class, lessonId);
        return d == null ? -1 : d;
    }

    private int courseTotalDuration(long courseId) {
        Integer d = jdbcTemplate.queryForObject(
                "SELECT total_duration FROM crs_course WHERE id = ?", Integer.class, courseId);
        return d == null ? -1 : d;
    }
}