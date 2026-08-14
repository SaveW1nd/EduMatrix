package com.edumatrix.org;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.org.support.OrgFixtures;
import com.edumatrix.org.support.OrgIntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03-02 §3.6 重置人员密码。
 *
 * <p>事务内三件事：写 {@code sys_user.password} → 置 {@code pwd_reset_flag=1} →
 * 作废该用户全部在线 Token。新密码明文<b>仅本次响应返回一次</b>。
 */
class NodePasswordResetIT extends OrgIntegrationTestBase {

    private static final String RESET_PATH = "/api/v1/org/nodes/%d/password/reset";

    @Test
    @DisplayName("留空 newPassword 时服务端生成 ≥12 位强口令，明文只回一次，且下次登录强制改密")
    void generatedPasswordIsReturnedOnceAndForcesChange() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        String before = orgFixtures.passwordHashOf(OrgFixtures.S1);

        JsonNode data = data(client.putWithToken(
                RESET_PATH.formatted(OrgFixtures.S1), token, "{\"newPassword\":null}"));

        String plain = data.path("newPassword").asText();
        assertThat(plain).hasSizeGreaterThanOrEqualTo(12);
        assertThat(data.path("mustChangeOnNextLogin").asBoolean()).isTrue();
        assertThat(data.path("userId").asText())
                .isEqualTo(String.valueOf(OrgFixtures.userIdOf(OrgFixtures.S1)));

        assertThat(orgFixtures.pwdResetFlagOf(OrgFixtures.S1)).isEqualTo(1);
        // 明文永不落库：库里存的是 BCrypt 密文，且与重置前不同
        assertThat(orgFixtures.passwordHashOf(OrgFixtures.S1))
                .isNotEqualTo(before)
                .startsWith("$2a$")
                .isNotEqualTo(plain);

        // 新口令确实能登进去（证明写进去的密文与回传的明文是同一个）
        assertThat(client.login(OrgFixtures.usernameOf(OrgFixtures.S1), plain).path("code").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("重置后该账号全部在线 Token 立即作废")
    void allSessionsAreRevoked() throws Exception {
        String studentToken = loginAs(OrgFixtures.S1);
        // 先确认这个 Token 是好用的
        assertThat(client.getRawWithToken("/api/v1/auth/me", studentToken).getResponse().getStatus())
                .isEqualTo(200);

        String adminToken = loginAs(OrgFixtures.ROOT);
        assertThat(code(client.putWithToken(
                RESET_PATH.formatted(OrgFixtures.S1), adminToken, "{}"))).isEqualTo(200);

        assertThat(client.getRawWithToken("/api/v1/auth/me", studentToken).getResponse().getStatus())
                .as("重置密码后旧 accessToken 必须失效")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("指定 newPassword 时用指定值；格式不合规 → 400")
    void explicitPasswordIsValidated() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);

        assertThat(code(client.putWithToken(RESET_PATH.formatted(OrgFixtures.S1), token,
                "{\"newPassword\":\"Abcd1234\"}"))).isEqualTo(200);
        assertThat(client.login(OrgFixtures.usernameOf(OrgFixtures.S1), "Abcd1234")
                .path("code").asInt()).isEqualTo(200);

        // 只有字母没有数字
        assertThat(code(client.putWithToken(RESET_PATH.formatted(OrgFixtures.S1), token,
                "{\"newPassword\":\"abcdefgh\"}"))).isEqualTo(400);
        // 长度不足
        assertThat(code(client.putWithToken(RESET_PATH.formatted(OrgFixtures.S1), token,
                "{\"newPassword\":\"Ab1\"}"))).isEqualTo(400);
    }

    @Test
    @DisplayName("教师可以重置名下学员的密码，但够不到别人的学员（子树判定天然就是那条限制）")
    void teacherCanResetOnlyHisOwnStudents() throws Exception {
        String token = loginAs(OrgFixtures.T3);

        assertThat(code(client.putWithToken(RESET_PATH.formatted(OrgFixtures.S6), token, "{}")))
                .isEqualTo(200);

        // S1 是 T1 名下的学员，不在 T3 的子树里 → 404（不暴露存在性）
        var result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(RESET_PATH.formatted(OrgFixtures.S1))
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("不得对自己执行 → 10012（改自己的密码走 03-01 §1.6）")
    void cannotResetOwnPassword() throws Exception {
        String token = loginAs(OrgFixtures.A2);

        assertThat(code(client.putWithToken(RESET_PATH.formatted(OrgFixtures.A2), token, "{}")))
                .isEqualTo(10012);
    }

    @Test
    @DisplayName("目标节点已停用时照常可重置（重置不解除停用）")
    void resettingWorksOnDisabledNode() throws Exception {
        String token = loginAs(OrgFixtures.ROOT);
        assertThat(code(client.putWithToken("/api/v1/org/nodes/" + OrgFixtures.S1 + "/status",
                token, "{\"status\":1}"))).isEqualTo(200);

        assertThat(code(client.putWithToken(RESET_PATH.formatted(OrgFixtures.S1), token, "{}")))
                .isEqualTo(200);
        assertThat(orgFixtures.nodeStatusOf(OrgFixtures.S1)).isEqualTo(1);
    }
}
