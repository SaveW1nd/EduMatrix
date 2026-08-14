package com.edumatrix.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.auth.support.ProtectedProbeController;
import com.edumatrix.common.errorcode.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双 Token 的生命周期（00-通用约定 §2.1 / §2.2，03-01 §1.3 / §1.4 / §1.6）。
 */
class AuthTokenLifecycleIT extends AuthIntegrationTestBase {

    @Test
    @DisplayName("§2.2 规则 3｜refreshToken 旋转：下发新的，旧的立即失效（10006）")
    void refreshTokenRotates() throws Exception {
        JsonNode login = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String firstRefresh = login.path("data").path("refreshToken").asText();

        JsonNode refreshed = client.refresh(firstRefresh);
        assertThat(refreshed.path("code").asInt()).isEqualTo(200);
        String secondRefresh = refreshed.path("data").path("refreshToken").asText();

        assertThat(secondRefresh)
                .as("每次刷新都下发新的")
                .isNotEqualTo(firstRefresh);
        // §1.2 字段说明：expiresIn 是「accessToken 剩余有效秒数」，不是配置值本身 ——
        // 签发与读取之间跨了一秒就是 7199，断言写死 7200 会变成一个偶发红灯
        assertThat(refreshed.path("data").path("expiresIn").asLong()).isBetween(7190L, 7200L);
        assertThat(refreshed.path("data").path("refreshExpiresIn").asLong()).isEqualTo(604800);

        assertThat(client.refresh(firstRefresh).path("code").asInt())
                .as("旧 refreshToken 一次性使用，防重放（§2.2 规则 3）")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());

        // 新令牌照常可用
        assertThat(client.refresh(secondRefresh).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("§2.2 规则 4｜伪造/乱写的 refreshToken → 10006，不去 Redis 试")
    void invalidRefreshTokenIsRejected() throws Exception {
        assertThat(client.refresh("not-a-real-token").path("code").asInt())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("§1.4 登出｜当前会话的两个 Token 同时作废；对已失效 Token 再登出仍返回成功")
    void logoutInvalidatesBothTokens() throws Exception {
        JsonNode login = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String accessToken = login.path("data").path("accessToken").asText();
        String refreshToken = login.path("data").path("refreshToken").asText();

        assertThat(client.postWithToken("/api/v1/auth/logout", accessToken, null)
                .path("code").asInt()).isEqualTo(200);

        assertThat(client.getRawWithToken(ProtectedProbeController.PATH, accessToken)
                .getResponse().getStatus())
                .as("accessToken 立即失效（PRD §7.3 安全条款 2：登出即时失效）")
                .isEqualTo(401);

        assertThat(client.refresh(refreshToken).path("code").asInt())
                .as("§1.4：当前会话的 refreshToken 一并作废")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());

        // ⚠ 文档冲突，实现按 00-通用约定 §2.3 走，未自行调和 —— 详见方法下方说明
        assertThat(client.postWithToken("/api/v1/auth/logout", accessToken, null)
                .path("code").asInt())
                .as("再次登出返回 401：/auth/logout 不在白名单四条里，Sa-Token 拦截器先于"
                        + "Controller 拒掉它。与 03-01 §1.4「对已失效 Token 调用同样返回成功」"
                        + "对不上，已作为待决项上报，实现方不自行挑一个")
                .isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
    }

    /*
     * 【上面那条断言背后的文档冲突】
     *
     * 03-01 §1.4 的响应说明写着「对已失效 Token 调用同样返回成功」，
     * 而 00-通用约定 §2.3 的白名单穷举只有四条、且明写「其余全部接口必须携带有效 accessToken」，
     * /auth/logout 不在其中。两者不可能同时成立：
     *
     *   - 要满足 §1.4，就得把 /auth/logout 加进 SaTokenConfig.AUTH_WHITELIST；
     *     而模块 01 在那个常量上写死了「不要往这里加路径。每加一条就是把一个入口
     *     暴露到鉴权之外」，且 §2.3 的白名单是穷举而非举例。
     *   - 要满足 §2.3，已失效 Token 调登出就是 401，§1.4 那句话落不了地。
     *
     * 按纪律不自行挑一个：实现保持 §2.3（白名单一条不加），本用例断言真实行为，
     * 冲突已上报需求方定夺。真要满足 §1.4，改的是白名单而不是这里的断言。
     */

    @Test
    @DisplayName("§1.6 改密｜除当前会话外，该账号其余会话的两个 Token 全部作废")
    void changingPasswordRevokesOtherSessionsOnly() throws Exception {
        // 会话 A（另一台设备）
        JsonNode deviceA = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String tokenA = deviceA.path("data").path("accessToken").asText();
        String refreshA = deviceA.path("data").path("refreshToken").asText();

        // 会话 B（当前设备，在这里改密）
        JsonNode deviceB = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        String tokenB = deviceB.path("data").path("accessToken").asText();
        String refreshB = deviceB.path("data").path("refreshToken").asText();

        assertThat(client.putWithToken("/api/v1/auth/password", tokenB,
                """
                {"oldPassword":"%s","newPassword":"Zw@20260812"}
                """.formatted(AuthFixtures.PASSWORD)).path("code").asInt()).isEqualTo(200);

        assertThat(client.getRawWithToken(ProtectedProbeController.PATH, tokenA)
                .getResponse().getStatus())
                .as("其余在线会话的 accessToken 作废，需用新密码重新登录")
                .isEqualTo(401);
        assertThat(client.refresh(refreshA).path("code").asInt())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());

        assertThat(client.getWithToken(ProtectedProbeController.PATH, tokenB).path("code").asInt())
                .as("§1.6 原文：除当前会话外 —— 当前这条不能被自己踢掉")
                .isEqualTo(200);
        assertThat(client.refresh(refreshB).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("§1.6｜原密码错误 10014；新密码不合弱密码策略 400（两者不是一回事）")
    void passwordPolicyIsEnforced() throws Exception {
        String token = client.loginForToken(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);

        assertThat(client.putWithToken("/api/v1/auth/password", token,
                """
                {"oldPassword":"WrongOld2026","newPassword":"Zw@20260812"}
                """).path("code").asInt())
                .isEqualTo(ErrorCode.OLD_PASSWORD_WRONG.getCode());

        assertThat(client.putWithToken("/api/v1/auth/password", token,
                """
                {"oldPassword":"%s","newPassword":"alllettersonly"}
                """.formatted(AuthFixtures.PASSWORD)).path("code").asInt())
                .as("PRD §7.3：≥8 位且同时含字母与数字，不合规返回 400（03-01 §1.6）")
                .isEqualTo(ErrorCode.BAD_REQUEST.getCode());

        assertThat(client.putWithToken("/api/v1/auth/password", token,
                """
                {"oldPassword":"%s","newPassword":"%s"}
                """.formatted(AuthFixtures.PASSWORD, AuthFixtures.PASSWORD)).path("code").asInt())
                .as("不得与原密码相同 → 400")
                .isEqualTo(ErrorCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("平台超管｜登录 → 刷新 → 新 Token 可用；旧 refreshToken 立即失效")
    void superAdminCanRefreshToken() throws Exception {
        // 六条完成判据全是租户内账号，超管这条路从来没被端到端跑过。而它走的租户上下文
        // 与其他角色不同：refresh() 用的是「显式 runWithTenant」（TenantHelper 路径①），
        // 它会关掉「超管会话整体放行」（路径③）。两者对超管同解 —— 因为超管在
        // sys_user 里的 tenant_id 就是 0（NOT NULL 列，DDL 注释「平台超管为 0」），
        // 刷新要读的三样东西全在 tenant_id = 0 里。本用例就是这句话的证据。
        JsonNode login = client.login(AuthFixtures.SUPER_ADMIN_USERNAME, AuthFixtures.PASSWORD);
        assertThat(login.path("code").asInt()).isEqualTo(200);
        assertThat(login.path("data").path("userType").asInt())
                .as("确认这条用例跑的确实是超管（user_type = 0）")
                .isZero();
        String oldRefreshToken = login.path("data").path("refreshToken").asText();

        // ① 刷新返回 200
        JsonNode refreshed = client.refresh(oldRefreshToken);
        assertThat(refreshed.path("code").asInt())
                .as("若这里是 10006，去看的应是租户过滤而不是 Redis / TTL / 旋转逻辑 —— "
                        + "错误码指向的方向与真实原因不一致，这正是本用例存在的理由")
                .isEqualTo(200);

        // ② 新 accessToken 可用
        String newAccessToken = refreshed.path("data").path("accessToken").asText();
        JsonNode me = client.getWithToken("/api/v1/auth/me", newAccessToken);
        assertThat(me.path("code").asInt()).isEqualTo(200);
        assertThat(me.path("data").path("username").asText()).isEqualTo(AuthFixtures.SUPER_ADMIN_USERNAME);
        assertThat(me.path("data").path("tenant").isNull())
                .as("§1.5：平台超管的 tenant 为 null —— 会话上下文没被刷新链路弄坏")
                .isTrue();

        // ③ 旋转对超管同样生效：旧令牌立即失效
        assertThat(client.refresh(oldRefreshToken).path("code").asInt())
                .as("§2.2 规则 3 对超管不打折")
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode());
    }

    @Test
    @DisplayName("白名单四条之外一律要 Token：无 Authorization 头 → 401")
    void protectedEndpointRequiresToken() throws Exception {
        assertThat(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(ProtectedProbeController.PATH))
                .andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }
}
