package com.edumatrix.vod.play;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.edumatrix.course.catalog.support.CourseFixtures;
import com.edumatrix.course.catalog.support.CourseIntegrationTestBase;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestSupportConfiguration.FakeVodMediaClient;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口 28 · 获取播放凭证（03-03 §8.1）—— <b>整条链上唯一的那道闸</b>。
 *
 * <p>五步的<b>判定逻辑</b>由 {@code PlayAuthChainServiceTest} 单测守（M38~M42 各已验证会红）；
 * 本 IT 守的是<b>走通 HTTP 之后的形状</b>：错误码、响应字段、Redis、审计、水印与随机间隔。
 */
@IntegrationTest
class PlayAuthIT extends CourseIntegrationTestBase {

    private static final String PLAY_AUTH = "/api/v1/vod/play-auth";

    private static final long LESSON_OK = 1968000000000004101L;
    private static final long LESSON_TRANSCODING = 1968000000000004102L;
    private static final long LESSON_HIDDEN = 1968000000000004103L;

    @Autowired
    private FakeVodMediaClient vodClient;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void seedPlayScenario() {
        vodClient.reset();

        // 两个学生节点：在读 / 已退课
        courseFixtures.studentNode(CourseFixtures.S_ACTIVE, CourseFixtures.A1,
                "0," + CourseFixtures.ROOT + "," + CourseFixtures.A1, "学生甲", 0, CourseFixtures.TENANT_ID);
        courseFixtures.studentNode(CourseFixtures.S_QUIT, CourseFixtures.A1,
                "0," + CourseFixtures.ROOT + "," + CourseFixtures.A1, "学生乙", 1, CourseFixtures.TENANT_ID);

        // C_ROOT 上架 + 三条课时：正常 / 转码中 / 隐藏
        jdbcTemplate.update("UPDATE crs_course SET status = 1 WHERE id = ?", CourseFixtures.C_ROOT);
        courseFixtures.lesson(LESSON_OK, CourseFixtures.C_ROOT, 0L, 1,
                CourseFixtures.VIDEO_OK, null, 600, 1, CourseFixtures.TENANT_ID);
        courseFixtures.lesson(LESSON_TRANSCODING, CourseFixtures.C_ROOT, 0L, 1,
                CourseFixtures.VIDEO_TRANSCODING, null, 600, 1, CourseFixtures.TENANT_ID);
        courseFixtures.lesson(LESSON_HIDDEN, CourseFixtures.C_ROOT, 0L, 1,
                CourseFixtures.VIDEO_OK, null, 600, 1, CourseFixtures.TENANT_ID);
        jdbcTemplate.update("UPDATE crs_lesson SET status = 0 WHERE id = ?", LESSON_HIDDEN);

        // 两名学生都授权该课程 —— 这样第 3 步就不是「顺带挡住退课学生」的那道闸
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.S_ACTIVE, CourseFixtures.TENANT_ID);
        courseFixtures.grantCourse(CourseFixtures.C_ROOT, CourseFixtures.S_QUIT, CourseFixtures.TENANT_ID);
    }

    private JsonNode ask(long nodeId, long lessonId) throws Exception {
        return client.postWithToken(PLAY_AUTH, loginAs(nodeId), "{\"lessonId\":" + lessonId + "}");
    }

    private List<Map<String, Object>> auditRows() {
        return jdbcTemplate.queryForList("SELECT * FROM vod_play_auth_log WHERE tenant_id = ? ORDER BY id",
                CourseFixtures.TENANT_ID);
    }

    // =====================================================================
    // 正常路径与响应形状
    // =====================================================================

    @Test
    @DisplayName("在读学生取到凭证：vid / playAuth / encryptType=1 / authExpire=300 齐备")
    void studentGetsPlayAuth() throws Exception {
        JsonNode res = ask(CourseFixtures.S_ACTIVE, LESSON_OK);
        assertThat(code(res)).isEqualTo(200);
        JsonNode d = data(res);

        assertThat(d.path("playAuth").asText()).isEqualTo(vodClient.playAuth);
        assertThat(d.path("vid").asText()).isNotBlank();
        assertThat(d.path("encryptType").asInt())
                .as("夹具的 vod_video.encrypt_type = 1（HLS 标准加密）→ 下发 0："
                        + "Aliplayer 的 encryptType 只表示【私有加密】，不是「加没加密」")
                .isZero();
        assertThat(d.path("authExpire").asInt()).isEqualTo(300);
        assertThat(d.path("authToken").asText()).hasSize(32);
        assertThat(d.path("sessionId").asText()).hasSize(32);
        assertThat(vodClient.playAuthCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("authToken 落 Redis 且带 300s TTL —— 模块 13 的心跳规则 1 直接读这个键")
    void authTokenStoredInRedisWithTtl() throws Exception {
        JsonNode d = data(ask(CourseFixtures.S_ACTIVE, LESSON_OK));
        String key = "play:auth:" + d.path("authToken").asText();

        assertThat(redis.opsForHash().get(key, "lessonId")).isEqualTo(String.valueOf(LESSON_OK));
        assertThat(redis.opsForHash().get(key, "sessionId")).isEqualTo(d.path("sessionId").asText());
        Long ttl = redis.getExpire(key);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(1L, 300L);
    }

    @Test
    @DisplayName("第一版进度快照恒为 0 —— 模块 13 还没开工，watchStatus 不会出现 2")
    void progressSnapshotIsZeroBeforeModule13() throws Exception {
        JsonNode d = data(ask(CourseFixtures.S_ACTIVE, LESSON_OK));
        assertThat(d.path("maxPosition").asInt()).isZero();
        assertThat(d.path("watchedDuration").asInt()).isZero();
        assertThat(d.path("watchStatus").asInt())
                .as("F-113 定案四：完播判定延后，第一版只在 0/1 之间流转")
                .isZero();
    }

    /**
     * <b>映射的另一侧。</b>只验一边证不了它真在翻译 —— 把 {@code aliplayerEncryptTypeOf}
     * 写死成 {@code return 0;} 时上一条照样绿，只有本条会红。
     *
     * <p>两边都要有，还因为<b>存量视频与新视频会长期混在一起</b>：
     * F-114 定案第一版不加密（新视频写 0），而早先上传的是 {@code encrypt_type = 2}。
     */
    @Test
    @DisplayName("⚠ encryptType 按行映射：库里 2（私有加密）→ 下发 1")
    void privateEncryptedVideoMapsToOne() throws Exception {
        jdbcTemplate.update("UPDATE vod_video SET encrypt_type = 2 WHERE id = ?", CourseFixtures.VIDEO_OK);
        JsonNode d = data(ask(CourseFixtures.S_ACTIVE, LESSON_OK));
        assertThat(d.path("encryptType").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("encryptType 按行映射：库里 0（不加密，F-114 第一版）→ 下发 0")
    void unencryptedVideoMapsToZero() throws Exception {
        jdbcTemplate.update("UPDATE vod_video SET encrypt_type = 0 WHERE id = ?", CourseFixtures.VIDEO_OK);
        JsonNode d = data(ask(CourseFixtures.S_ACTIVE, LESSON_OK));
        assertThat(d.path("encryptType").asInt()).isZero();
    }

    // =====================================================================
    // 五步在 HTTP 层的错误码（F-48 定案：20014 与 20013 保持两种回答）
    // =====================================================================

    @Test
    @DisplayName("⚠ 第 1 步：已退课学生 → 20013（撤销之外唯一的失效手段；他的授权还在）")
    void quitStudentRejected() throws Exception {
        assertThat(code(ask(CourseFixtures.S_QUIT, LESSON_OK)))
                .as("该生 org_resource_grant 的授权行仍在，挡住他的只能是第 1 步")
                .isEqualTo(20013);
        assertThat(auditRows()).as("被拦下的请求不写审计").isEmpty();
    }

    @Test
    @DisplayName("第 2 步：课时隐藏 → 20014（F-48 定案：不并进 20013）")
    void hiddenLessonRejectedWith20014() throws Exception {
        assertThat(code(ask(CourseFixtures.S_ACTIVE, LESSON_HIDDEN))).isEqualTo(20014);
    }

    @Test
    @DisplayName("第 3 步：学生节点未被授权 → 20013")
    void ungrantedStudentRejected() throws Exception {
        jdbcTemplate.update("DELETE FROM org_resource_grant WHERE resource_id = ? AND target_node_id = ?",
                CourseFixtures.C_ROOT, CourseFixtures.S_ACTIVE);
        assertThat(code(ask(CourseFixtures.S_ACTIVE, LESSON_OK))).isEqualTo(20013);
    }

    @Test
    @DisplayName("第 4 步：课程已下架 → 20013（学生有授权也不行）")
    void offShelfCourseRejected() throws Exception {
        jdbcTemplate.update("UPDATE crs_course SET status = 2 WHERE id = ?", CourseFixtures.C_ROOT);
        assertThat(code(ask(CourseFixtures.S_ACTIVE, LESSON_OK))).isEqualTo(20013);
    }

    @Test
    @DisplayName("第 5 步：转码未完成 → 20003")
    void transcodingVideoRejected() throws Exception {
        assertThat(code(ask(CourseFixtures.S_ACTIVE, LESSON_TRANSCODING))).isEqualTo(20003);
    }

    // =====================================================================
    // 审计（含管理端预览）
    // =====================================================================

    @Test
    @DisplayName("学生取证写审计：event_type=1、viewer_type=3、student_id 有值")
    void studentIssueWritesAudit() throws Exception {
        ask(CourseFixtures.S_ACTIVE, LESSON_OK);
        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("event_type")).intValue())
                .as("取值 2 随接口 29 删除后不再写入，DDL 与枚举不动")
                .isEqualTo(1);
        assertThat(((Number) row.get("viewer_type")).intValue()).isEqualTo(3);
        assertThat(row.get("student_id")).isNotNull();
        assertThat((String) row.get("auth_token"))
                .as("落库的是我们自己的 authToken，【不是 playAuth】—— 后者能直接解密播放")
                .isNotEqualTo(vodClient.playAuth);
    }

    @Test
    @DisplayName("⚠ 管理端预览【同样落审计】，且 student_id 留 NULL")
    void adminPreviewWritesAuditWithNullStudentId() throws Exception {
        JsonNode res = ask(CourseFixtures.ROOT, LESSON_OK);
        assertThat(code(res)).isEqualTo(200);

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).as("能看到全机构课程的人批量取证，正是最需要留痕的场景").hasSize(1);
        assertThat(((Number) rows.get(0).get("viewer_type")).intValue()).isEqualTo(1);
        assertThat(rows.get(0).get("student_id"))
                .as("教师与管理员没有 org_student 档案行")
                .isNull();
    }

}
