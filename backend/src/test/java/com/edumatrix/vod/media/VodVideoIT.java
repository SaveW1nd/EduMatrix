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

    /**
     * <b>需方 2026-08-21 定案二的唯一判据。</b>
     *
     * <p>「我最初的想法是所有的资产归超级管理员所有，别人无权上传」——
     * 收窄靠迁移 {@code V202608210000} 撤销 {@code teacher → vod:video:add} 的绑定，
     * <b>不靠代码里的角色门</b>（与 F-72 逐字同源）。
     * 把那条迁移删掉、库重建 → 本条立刻红。
     *
     * <p><b>两侧都要断言</b>：只写「教师 403」的话，把权限写死拒绝、或者把整个端点删掉，
     * 都能让它全绿 —— 而那等于把上传功能也一起关了，且看不出来。
     * 本文件其余的上传凭证用例已从 {@code TA}（教师）改为 {@code ROOT}（管理员），
     * 那批改动是这次收窄的<b>连带后果</b>，不是判据。
     */
    @Test
    @DisplayName("⚠ §7.1 上传凭证【仅 org_admin】：教师 403、管理员 200（需方定案二）")
    void uploadTokenIsOrgAdminOnly() throws Exception {
        String body = """
                {"videoName":"权限探针","fileName":"a.mp4","fileSize":1048576}""";

        JsonNode teacher = client.postWithToken(VIDEOS + "/upload-token",
                loginAs(CourseFixtures.TA), body);
        assertEquals(403, code(teacher),
                "教师不再能上传视频 —— 判定来自 sys_role_menu 的绑定，不是代码里的角色门");

        JsonNode admin = client.postWithToken(VIDEOS + "/upload-token",
                loginAs(CourseFixtures.ROOT), body);
        assertEquals(200, code(admin),
                "这一侧不写，等于把上传整个关掉也全绿");
    }

    @Test
    @DisplayName("§7.1 新建：先调云再落库，vod_file_id 发凭证时就写入（契约 §2.8 规则 1 的前提）")
    void createWritesVodFileIdAtCredentialTime() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
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
        assertEquals(0, encryptType,
                "F-114 定案：第一版不加密 → Service 显式写 0。"
                        + "该列出现过三个值（DDL 默认 1 标准加密、R1a 定案 2 私有加密、F-114 定案 0 不加密），"
                        + "正因如此不能依赖 DDL 默认值。"
                        + "⚠ 本列是【事实记录】不是意图声明：它必须与转码模板实际产出的东西一致，"
                        + "模块 12 的 play-auth 按它决定下发给 Aliplayer 的 encryptType，写错的表现是「播不了」");
    }

    @Test
    @DisplayName("§7.1 云调失败则一行都不落（不留 vod_file_id 为 NULL 的僵尸行）")
    void cloudFailureLeavesNoRow() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
        long before = countVideos();
        vodClient.failNext = true;

        client.postWithToken(VIDEOS + "/upload-token", token, """
                {"videoName":"注定失败","fileName":"a.mp4","fileSize":1048576}""");

        assertEquals(before, countVideos(), "云调失败却落了行 —— 那是一条永远等不到事件的僵尸媒资");
    }

    @Test
    @DisplayName("§7.1 请求体里的 videoId 查不到 → 20015（param-addressed，不是 404）")
    void unknownVideoIdInBodyIs20015() throws Exception {
        String token = loginAs(CourseFixtures.ROOT);
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

    /**
     * <b>需方 2026-08-21 定案（排期 A）的判据之一 —— 媒资侧代表端点。</b>
     *
     * <p>收窄靠迁移 {@code V202608210200} 撤销 {@code teacher → vod:video:remove} 的绑定。
     * 同一批还撤了 {@code retranscode} 与 {@code status}；{@code vod:video:add} 早已由
     * {@code V202608210000} 撤走（F-105），{@code vod:video:list} <b>保留</b> ——
     * 教师仍要看得见媒资才能选来授权给学员。
     *
     * <p><b>为什么要先把 owner 改成教师本人</b>：删除端点有归属前置判定，
     * 教师对 ROOT 的媒资本来就「不可见 → 404」，那样断出来的 403 不是权限撤销的功劳。
     * 把 owner 挪到教师身上，归属这一关就过了，<b>挡住他的只剩权限绑定这一件事</b>。
     * 两侧用的是<b>同一个媒资、同一个端点</b>，只换演员与随之匹配的归属。
     */
    @Test
    @DisplayName("⚠ §7.4 删除媒资【仅 org_admin】：教师 403、管理员 200（需方定案，排期 A）")
    void deleteVideoIsOrgAdminOnly() throws Exception {
        jdbcTemplate.update("UPDATE vod_video SET owner_node_id = ? WHERE id = ?",
                CourseFixtures.TA, CourseFixtures.VIDEO_OK);
        JsonNode teacher = deleteWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK,
                loginAs(CourseFixtures.TA));
        assertEquals(403, code(teacher),
                "教师不再能删媒资 —— 他是 owner，归属这一关是过的，挡住他的只有 sys_role_menu 的绑定");

        jdbcTemplate.update("UPDATE vod_video SET owner_node_id = ? WHERE id = ?",
                CourseFixtures.ROOT, CourseFixtures.VIDEO_OK);
        JsonNode admin = deleteWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK,
                loginAs(CourseFixtures.ROOT));
        assertEquals(200, code(admin),
                "这一侧不写，等于把删除整个关掉也全绿");
    }

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
        // 【F-114 收窄后演员必须再换一次，理由与当初从教师换到 A1 完全同源】
        // 媒资写操作现在【仅机构根】可做，下级管理员 A1 会在 assertOrgRoot() 处 403 ——
        // 两次 DELETE 仍然「逐字相同」，但本条会【绿着退化】成「A1 碰不到删除端点」，
        // 而它要证的是「404 不暴露存在性」。这正是原注释警告过的那个形状，只是闸换了一道。
        //
        // 换成【另一个租户的机构根】ROOT2：他过得了 assertOrgRoot()（自己就是机构根），
        // 于是判定真的落到可见性/租户隔离那一层，本条才在证它该证的东西。
        //
        // ⚠ 顺带记一个【收窄带来的真实后果】：同租户内「有写权限但看不见某个媒资」
        //    这个组合【从此不存在】—— 唯一能走到写端点的人是机构根，而他看得见本租户全部媒资。
        //    F-49 在写端点上的同租户探测面因此消失了，剩下的只有跨租户这一层。
        String token = loginAs(CourseFixtures.ROOT2);

        // 用 DELETE 而不是 POST：两条都走 VodVideoAccessGuard#loadOwnedByPath 这同一个入口，
        // 而 outcome() 只支持 GET/PUT/DELETE（模块 08 建的，本条不为一个探针去改它）
        HttpOutcome missing = outcome("DELETE", VIDEOS + "/1968000000000009999", token, null);
        HttpOutcome invisible = outcome("DELETE", VIDEOS + "/" + CourseFixtures.VIDEO_OK, token, null);

        assertEquals(missing, invisible,
                "两次响应必须逐字相同 —— 不同则可拿来探测存在性（契约 §2.4 三分法第 1 行、F-42 同形状）");
        assertEquals(404, missing.httpStatus());
    }

    // =====================================================================
    // 【F-114】上传时可选加密：选了就要真的走加密那个模板组
    //
    // 三条一组：不选 / 选了 / 加密组没配。
    // 前两条成对才证明「真的按参数选」——单独任何一条都可能是「恒用某一个组」。
    // 第三条守的是【不许悄悄降级】：降级的表现是「以为加密了其实没有」，不报错。
    // =====================================================================

    @Test
    @DisplayName("⚠ F-114 不选加密 → 走默认（不加密）模板组，并记进 template_group_id")
    void uploadWithoutEncryptionUsesDefaultGroup() throws Exception {
        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", loginAs(CourseFixtures.ROOT),
                """
                {"videoName":"明文视频","fileName":"a.mp4","fileSize":1048576}""");
        assertEquals(200, code(res));
        assertTrue(vodClient.calls.stream().anyMatch(c -> c.contains("tpl=TPL-PLAIN")),
                "未选加密必须走默认组，实际调用轨迹：" + vodClient.calls);
        assertEquals("TPL-PLAIN", templateGroupOf(data(res).path("videoId").asLong()));
    }

    @Test
    @DisplayName("⚠ F-114 选了加密 → 走加密模板组（与上一条成对，缺一条抓不住「恒用某个组」）")
    void uploadWithEncryptionUsesEncryptedGroup() throws Exception {
        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", loginAs(CourseFixtures.ROOT),
                """
                {"videoName":"加密视频","fileName":"b.mp4","fileSize":1048576,"encrypted":true}""");
        assertEquals(200, code(res));
        assertTrue(vodClient.calls.stream().anyMatch(c -> c.contains("tpl=TPL-ENCRYPTED")),
                "选了加密必须走加密组，实际调用轨迹：" + vodClient.calls);
        assertEquals("TPL-ENCRYPTED", templateGroupOf(data(res).path("videoId").asLong()));
    }

    @Test
    @DisplayName("⚠ F-114 加密组没配 → 【拒绝】，不许悄悄降级成不加密")
    void uploadWithEncryptionRejectedWhenNoEncryptedGroupConfigured() throws Exception {
        vodClient.encryptedGroup = "";      // 模拟本部署没配加密模板组
        JsonNode res = client.postWithToken(VIDEOS + "/upload-token", loginAs(CourseFixtures.ROOT),
                """
                {"videoName":"要加密","fileName":"c.mp4","fileSize":1048576,"encrypted":true}""");
        assertTrue(code(res) != 200,
                "没配加密组时必须报错。悄悄降级成不加密的表现是【以为加密了其实没有】，"
                        + "页面一切正常、没人会发现 —— 与挑流那条「挑不到必须置 3、绝不可置 2」同一条纪律");
        assertTrue(vodClient.calls.stream().noneMatch(c -> c.startsWith("createUploadVideo")),
                "被拒绝时不应产生任何云调用");
    }

    /**
     * <b>重转必须复用这个视频当初用的模板组</b>，不能用当前配置的那个。
     *
     * <p>用当前配置的会在<b>同一个视频上叠出第二套流</b> → {@code GetPlayInfo} 返回两路
     * → 「恰好一路」守卫失败，而它报的原因（「模板组配了多档」）与真实原因
     * （两次转码用了不同组）<b>对不上</b>，排查会被带偏。
     */
    @Test
    @DisplayName("⚠ F-114 重新发起转码：复用【当初那个】模板组，不用当前配置的")
    void retranscodeReusesOriginalTemplateGroup() throws Exception {
        // 这条媒资当初是按【加密】传的
        jdbcTemplate.update("UPDATE vod_video SET status = 3, template_group_id = ? WHERE id = ?",
                "TPL-ENCRYPTED", CourseFixtures.VIDEO_OK);
        // 与此同时当前默认配置是【不加密】—— 两者不同，才分得出用了哪一个
        vodClient.reset();

        JsonNode res = client.postWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK + "/retranscode",
                loginAs(CourseFixtures.ROOT), "");
        assertEquals(200, code(res));

        assertTrue(vodClient.calls.stream().anyMatch(c -> c.contains("submitTranscodeJobs")
                        && c.contains("tpl=TPL-ENCRYPTED")),
                "必须复用当初的 TPL-ENCRYPTED；用了当前默认的 TPL-PLAIN 就会叠出第二套流。"
                        + "实际调用轨迹：" + vodClient.calls);
        assertTrue(vodClient.calls.stream().noneMatch(c -> c.contains("tpl=TPL-PLAIN")),
                "不该出现默认组");
    }

    private String templateGroupOf(long videoId) {
        return jdbcTemplate.queryForObject(
                "SELECT template_group_id FROM vod_video WHERE id = ?", String.class, videoId);
    }

    /**
     * <b>收窄本身要有用例守着</b>，否则把 {@code assertOrgRoot()} 删掉全库无人发觉。
     *
     * <p>两侧都断言：只写「A1 被拒」的话，把整个删除端点关掉也能全绿。
     */
    @Test
    @DisplayName("⚠ F-114 收窄：媒资写操作【仅机构根】—— 下级管理员 403、机构根 200（两侧都断）")
    void mediaWriteIsOrgRootOnly() throws Exception {
        // A1 是下级管理员：有 vod:video:remove 权限位，但不是机构根
        HttpOutcome sub = outcome("DELETE", VIDEOS + "/" + CourseFixtures.VIDEO_DELETED,
                loginAs(CourseFixtures.A1), null);
        assertEquals(403, sub.httpStatus(),
                "下级管理员即使有权限位也不行 —— 这是资源归属层级的约束，不是权限等级");

        // 机构根 ROOT：同一个端点、同一条媒资，必须过得去
        // （VIDEO_DELETED 已逻辑删除 → 404；关键是【不是 403】，说明他过了 assertOrgRoot）
        HttpOutcome root = outcome("DELETE", VIDEOS + "/" + CourseFixtures.VIDEO_DELETED,
                loginAs(CourseFixtures.ROOT), null);
        assertEquals(404, root.httpStatus(),
                "机构根必须过得了结构闸（这条媒资已删故 404）—— 若这里也 403，说明收窄把机构根一起挡了");
    }

    @Test
    @DisplayName("F-49 被授权者：可见但非 owner，写操作 403（不再收敛成 404 —— 他已知道它存在）")
    void grantedButNotOwnerGets403() throws Exception {
        // 【被授权者换成管理员 A1】教师已无该写权限（V202608210200），
        // 继续用教师会让这条 403 【绿着退化】：判定从「可见但非 owner」
        // 变成「压根没这个权限」，而本条要证的正是前者。A1 有权限、只是不是 owner。
        // ⚠【F-114 换演员 —— 这条上一轮就被我弄退化了，当时没抓到】
        //   媒资写操作收窄到机构根之后，A1 会在【机构根闸】处 403，本条绿着退化成
        //   「A1 碰不到删除端点」。把 VIDEO_OK 的 owner 改成教师 TA、演员换成机构根 ROOT：
        //   ROOT 过得了机构根闸、也有 vod:video:remove，403 才真的来自归属判定。
        jdbcTemplate.update("UPDATE vod_video SET owner_node_id = ? WHERE id = ?",
                CourseFixtures.TA, CourseFixtures.VIDEO_OK);
        grantVideoTo(CourseFixtures.VIDEO_OK, CourseFixtures.ROOT);
        String token = loginAs(CourseFixtures.ROOT);

        JsonNode res = deleteWithToken(VIDEOS + "/" + CourseFixtures.VIDEO_OK, token);
        assertEquals(403, code(res), "§7.4/§7.5/§7.6 逐字：仅被授权者只读，写操作返回 403 —— "
                + "演员是【机构根】ROOT，过得了 F-114 的机构根闸、也有 vod:video:remove，"
                + "403 只可能来自归属判定 —— owner 已改为 TA");
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
