package com.edumatrix.org.grant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.common.response.BizException;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.org.grant.support.GrantFixtures;
import com.edumatrix.org.grant.support.GrantIntegrationTestBase;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.service.FileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模块 11 · C9：{@code material_attach} 的归属校验 —— <b>解除模块 05 的 B-3 / F-38 fail-closed</b>。
 *
 * <h2>这两条用例是这次解除的<b>唯一判据</b></h2>
 * <p>解除之后「讲义附件能不能下」由<b>授权</b>说了算，而不再是恒 404。
 * 写松了就等于<b>把闸开了而没人知道</b> —— 而 {@code fileId} 是雪花 ID、
 * 同租户内时间相邻<b>可近邻枚举</b>（03-01 §7.2 自己用这一点论证过为什么详情接口
 * 不能下发直链）。所以这里必须<b>两侧都断言</b>：未授权的下不了、已授权的下得了。
 *
 * <p>只断言「未授权 404」是不够的：把 {@code canAccess} 写死 {@code false} 也全绿，
 * 而那等于这次解除<b>什么都没解除</b>，且看不出来。
 *
 * <p>被测实现在 {@code course/catalog/MaterialAttachOwnershipChecker}（F-91：
 * 它要读 {@code crs_material} / {@code crs_lesson}，而检查③ 禁止 {@code org} 域
 * import {@code course} 域）；夹具里有学生的是本模块，故用例落在这里。
 */
class MaterialAttachDownloadIT extends GrantIntegrationTestBase {

    @Autowired
    private FileService fileService;

    @Test
    @DisplayName("⚠ 未被授权该课程的学生：讲义附件下不了（404，不暴露存在性）")
    void unauthorizedStudentGets404() throws Exception {
        runAsStudent(GrantFixtures.S[0], () ->
                assertThatThrownBy(() -> fileService.resolveForDownload(GrantFixtures.ATTACH_FILE))
                        .as("03-03 §6.3：学生看图文课时必须「该学生节点被显式授权该课程」。"
                                + "走文件接口必须得出同一个结论 —— 否则同一份讲义走两条路结论不同，"
                                + "而 fileId 可近邻枚举")
                        .isInstanceOf(BizException.class)
                        .extracting(e -> ((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("⚠ 已被授权该课程的学生：拿得到文件（这条不写，等于闸开了没人知道）")
    void authorizedStudentGetsTheFile() throws Exception {
        // 逐级授到学生（契约 §2.5 规则 3：每一跳都要显式授权）
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S[0], GrantFixtures.T1);

        runAsStudent(GrantFixtures.S[0], () -> {
            SysFile file = fileService.resolveForDownload(GrantFixtures.ATTACH_FILE);
            assertThat(file.getId()).isEqualTo(GrantFixtures.ATTACH_FILE);
            assertThat(file.getFileName()).isEqualTo("讲义.pdf");
        });
    }

    @Test
    @DisplayName("同班同学没被授权 → 仍然 404（授权精准到人，不按班级放行）")
    void classmateWithoutGrantStillGets404() throws Exception {
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.S[0], GrantFixtures.T1);

        runAsStudent(GrantFixtures.S[1], () ->
                assertThatThrownBy(() -> fileService.resolveForDownload(GrantFixtures.ATTACH_FILE))
                        .isInstanceOf(BizException.class));
    }

    @Test
    @DisplayName("管理端：资料归属在我子树内就能下（crs_material.owner_node_id 的 DDL 列注释）")
    void ownerSideCanDownloadWithoutAnyGrant() throws Exception {
        runAsStudent(GrantFixtures.ROOT, () ->
                assertThatCode(() -> fileService.resolveForDownload(GrantFixtures.ATTACH_FILE))
                        .as("资料 owner_node_id = ROOT，且 ROOT 一条课程授权都没有 —— "
                                + "「管理端按 owner_node_id 子树过滤」那一支要能单独成立")
                        .doesNotThrowAnyException());
    }

    @Test
    @DisplayName("⚠ 跨管辖的教师【仍然打得开】讲义 —— 使用端要的是 canUse，不是 canRegrant")
    void crossScopeTeacherCanStillOpenTheMaterial() throws Exception {
        // 这条是自查补的：上面三条里被授权的学生【链是完整的】，canUse 与 canRegrant 同为真，
        // 于是把 canUse 误写成 canRegrant 也全绿（实测确认过）。要区分两者必须造一个
        // 「能用、不能再下发」的持有者 —— 也就是契约 §2.5 规则 9 的调岗教师。
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.A1, GrantFixtures.ROOT);
        grantFixtures.grant(1, GrantFixtures.C1, GrantFixtures.T1, GrantFixtures.A1);
        moveT1UnderA2();

        runAsStudent(GrantFixtures.T1, () ->
                assertThatCode(() -> fileService.resolveForDownload(GrantFixtures.ATTACH_FILE))
                        .as("契约 §2.5 规则 9：跨管辖授权【仅保留使用能力】，丧失的是再下发。"
                                + "用 canRegrant 判使用端的表现是：调岗教师的备课资料突然打不开，"
                                + "而接口只回一个 404")
                        .doesNotThrowAnyException());
    }

    /** 把 T1 挪到 A2 名下（A2 不持有 C1）—— 造出「能用、不能再下发」。 */
    private void moveT1UnderA2() {
        String underA2 = "0," + GrantFixtures.ROOT + "," + GrantFixtures.A2;
        jdbcTemplate.update("UPDATE org_node SET parent_id = ?, ancestors = ? WHERE id = ?",
                GrantFixtures.A2, underA2, GrantFixtures.T1);
        jdbcTemplate.update("UPDATE org_node SET ancestors = ? WHERE parent_id = ?",
                underA2 + "," + GrantFixtures.T1, GrantFixtures.T1);
        cleanGrantRedisKeys();
    }

    /** 以指定节点的会话直接调 Service（{@code FileService} 没有对应的 Controller 入口）。 */
    private void runAsStudent(long nodeId, ThrowingRunnable action) throws Exception {
        testContextProvider.asTenantUser(GrantFixtures.TENANT_ID,
                GrantFixtures.userIdOf(nodeId), nodeId);
        com.edumatrix.common.tenant.TenantHelper.setProvider(testContextProvider);
        try {
            action.run();
        } finally {
            com.edumatrix.common.tenant.TenantHelper.setProvider(saTokenContextProvider);
            testContextProvider.asNoSession();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
