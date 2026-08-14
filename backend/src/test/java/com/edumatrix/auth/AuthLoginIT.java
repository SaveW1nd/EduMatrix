package com.edumatrix.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.auth.support.AuthFixtures;
import com.edumatrix.auth.support.AuthIntegrationTestBase;
import com.edumatrix.auth.support.ProtectedProbeController;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.redis.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录链路的验收（04-实施计划.md 模块 02「做完什么算做完」判据 1 / 2 / 3）
 * 与四个不可合并错误码的对照。
 */
class AuthLoginIT extends AuthIntegrationTestBase {

    /**
     * 一个<b>故意不存在</b>的用户名，用于验「账号不存在与密码错误不区分」（{@code 10003}）。
     *
     * <h2>它必须由本类自清，不能进 {@code cleanAuthRedisKeys()} 的清单</h2>
     * <p>那个清单是<b>夹具账号清单</b>（{@code AuthFixtures} 建出来的 7 个账号），
     * 而本常量恰恰是「故意不存在的账号」—— 放进夹具清单语义上就错了，
     * 而且下一个人再写一个同类用户名时又会漏。<b>谁造的谁清。</b>
     *
     * <h2>不自清会怎样：连跑第 6 次必红，且红的地方与改动无关</h2>
     * <p>登录失败会累加 {@code auth:fail:{username}}（00-通用约定 §8），
     * 而本用户名不在 {@code cleanAuthRedisKeys()} 的覆盖范围内，于是<b>计数跨运行累加</b>：
     * 连续跑 5 次 {@code mvn verify} 后 {@code auth:lock:it_no_such_user = 5}，
     * 第 6 次触发账号锁定，本用例断言 {@code 10003} 却拿到 {@code 10005}。
     *
     * <p><b>这种失败没人查得动</b>：它与当次改动毫无关系，出现在模块 02 的测试里，
     * 而当事人多半正在改别的模块。
     */
    private static final String UNKNOWN_USERNAME = "it_no_such_user";

    /** 前清：上一次运行若异常中断，残留的计数不该算到这次头上。 */
    @BeforeEach
    void clearUnknownAccountCountersBefore() {
        clearUnknownAccountCounters();
    }

    /** 后清：本次造出的计数不留给下一次运行 —— 这一条才是「连跑第 6 次必红」的解。 */
    @AfterEach
    void clearUnknownAccountCountersAfter() {
        clearUnknownAccountCounters();
    }

    private void clearUnknownAccountCounters() {
        redisTemplate.delete(RedisKeys.loginFail(UNKNOWN_USERNAME));
        redisTemplate.delete(RedisKeys.loginLock(UNKNOWN_USERNAME));
    }

    // =====================================================================
    // 判据 1：租户到期 → 10007，且 sys_login_log 记录失败状态
    // =====================================================================

    @Test
    @DisplayName("判据 1｜机构 expire_time 已过 → 10007，且登录失败留痕（PRD F1-1 验收标准）")
    void expiredTenantIsRejectedAndLogged() throws Exception {
        // Given：机构 expire_time = 2026-08-11 23:59:59（夹具逐字取自 PRD F1-1 验收标准）
        // When ：该机构账号在此之后登录（当前时间早已越过验收标准里的 2026-08-12 09:00:00）
        JsonNode response = client.login(AuthFixtures.EXPIRED_USERNAME, AuthFixtures.PASSWORD);

        // Then：10007，且与账号级封禁（10005）区分开 —— 找的是平台，不是超管
        assertThat(response.path("code").asInt())
                .as("租户停用或到期是 10007，不得与 10005 / 10015 / 10017 合并（00-通用约定 §9.2）")
                .isEqualTo(ErrorCode.TENANT_DISABLED_OR_EXPIRED.getCode());

        assertThat(fixtures.latestLoginLogStatus(AuthFixtures.EXPIRED_USERNAME))
                .as("PRD F1-1 验收标准要求失败也写 sys_login_log —— 没有它，"
                        + "「某账号被人试了 500 次」在系统里不留任何痕迹")
                .isEqualTo(1);
    }

    // =====================================================================
    // 判据 2：初始密码强制改密
    // =====================================================================

    @Test
    @DisplayName("判据 2｜初始密码首次登录 → needChangePassword=true，未改密前访问其他接口 403")
    void initialPasswordForcesPasswordChange() throws Exception {
        JsonNode login = client.login(AuthFixtures.ROOT_USERNAME, AuthFixtures.PASSWORD);
        assertThat(login.path("code").asInt()).isEqualTo(200);
        assertThat(login.path("data").path("needChangePassword").asBoolean())
                .as("pwd_reset_flag = 1 → 前端据此强制跳转改密页（PRD F1-1 规则 6）")
                .isTrue();

        String token = login.path("data").path("accessToken").asText();

        // 其他接口一律 403（03-01 §1.6：除改密、登出、取本人信息外）
        assertThat(client.getRawWithToken(ProtectedProbeController.PATH, token).getResponse().getStatus())
                .as("未改密前不得访问其他页面（PRD F1-1 / F1-4 验收）")
                .isEqualTo(403);

        // 三条放行接口照常可用，否则用户根本走不到改密页
        assertThat(client.getWithToken("/api/v1/auth/me", token).path("code").asInt()).isEqualTo(200);

        JsonNode changed = client.putWithToken("/api/v1/auth/password", token,
                """
                {"oldPassword":"%s","newPassword":"NewPwd2026"}
                """.formatted(AuthFixtures.PASSWORD));
        assertThat(changed.path("code").asInt()).isEqualTo(200);

        // 改密后同一会话立刻可用（needChangePassword 置 false）
        assertThat(client.getRawWithToken(ProtectedProbeController.PATH, token).getResponse().getStatus())
                .as("改密后 needChangePassword 置 false，门禁随即放行")
                .isEqualTo(200);

        // 新密码可登、旧密码不可登
        assertThat(client.login(AuthFixtures.ROOT_USERNAME, "NewPwd2026").path("code").asInt())
                .isEqualTo(200);
        assertThat(client.login(AuthFixtures.ROOT_USERNAME, AuthFixtures.PASSWORD).path("code").asInt())
                .isEqualTo(ErrorCode.USERNAME_OR_PASSWORD_WRONG.getCode());
    }

    // =====================================================================
    // 判据 3：管理员停用级联、教师停用不级联
    // =====================================================================

    @Test
    @DisplayName("判据 3a｜停用管理员节点 N → 其子树内学生登录被拒 10017（分支冻结）")
    void disablingAdminNodeFreezesWholeSubtree() throws Exception {
        // 停用只写 org_node.status 一行（契约 §2.3），顺序：先 SADD 再提交事务
        fixtures.setNodeStatus(AuthFixtures.ADMIN_NODE, 1);

        assertThat(client.login(AuthFixtures.STUDENT1_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .as("②段：祖先链中有 node_type=1 且 status=1 的管理员 → 拒登")
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());

        assertThat(client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .as("①段：本人所在节点被停用 → 拒登")
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());

        assertThat(client.login(AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .as("被停用管理员的兄弟分支不受影响 —— 冻结的是子树，不是整个机构")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("判据 3b｜停用教师节点 T → 本人被拒，其名下学员照常登录（教师停用不级联）")
    void disablingTeacherNodeDoesNotCascade() throws Exception {
        fixtures.setNodeStatus(AuthFixtures.TEACHER_NODE, 1);

        assertThat(client.login(AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .as("①段承担教师/学生的「仅本人」停用；只写②段的话这里会放行")
                .isEqualTo(ErrorCode.NODE_OR_ANCESTOR_DISABLED.getCode());

        assertThat(client.login(AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .as("PRD F1-2 验收标准：教师停用后其名下学员仍能登录。"
                        + "②段的 node_type=1 就是为这一条存在的 —— 级联会让整批学员突然登不进去，"
                        + "契约 §2.3 称之为业务事故")
                .isEqualTo(200);
    }

    // =====================================================================
    // 四个错误码不得合并（00-通用约定 §9.2）
    // =====================================================================

    @Test
    @DisplayName("10005｜sys_user.status=1（账号级封禁，仅超管可置）与 10017 是两个不同的原因")
    void accountBanIsNotOrgFreeze() throws Exception {
        // 模拟超管的风控封禁 —— 本模块只读这一列，写它是模块 03 的 PUT /system/users/{id}/status
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = ?", AuthFixtures.TEACHER_USER);

        assertThat(client.login(AuthFixtures.TEACHER_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .as("账号级封禁走 10005（找超管），组织侧冻结走 10017（找机构管理员），"
                        + "前端提示语与自助路径不同")
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED_OR_LOCKED.getCode());
    }

    @Test
    @DisplayName("10015｜学籍已归档 → 与 10005 / 10017 分开")
    void archivedStudentIsRejected() throws Exception {
        fixtures.archiveStudent(AuthFixtures.STUDENT2_NODE);

        assertThat(client.login(AuthFixtures.STUDENT2_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt())
                .isEqualTo(ErrorCode.ACCOUNT_ARCHIVED.getCode());
    }

    @Test
    @DisplayName("10003｜账号不存在与密码错误不区分（防撞库探测）")
    void wrongPasswordAndUnknownAccountAreIndistinguishable() throws Exception {
        JsonNode wrongPassword = client.login(AuthFixtures.ADMIN_USERNAME, "WrongPwd2026");
        JsonNode unknownAccount = client.login(UNKNOWN_USERNAME, AuthFixtures.PASSWORD);

        assertThat(wrongPassword.path("code").asInt())
                .isEqualTo(ErrorCode.USERNAME_OR_PASSWORD_WRONG.getCode());
        assertThat(unknownAccount.path("code").asInt())
                .as("00-通用约定 §9.2：登录失败不区分具体哪项错误")
                .isEqualTo(ErrorCode.USERNAME_OR_PASSWORD_WRONG.getCode());
        assertThat(unknownAccount.path("msg").asText()).isEqualTo(wrongPassword.path("msg").asText());
    }

    @Test
    @DisplayName("10004｜验证码错误；且验证码一次性，用过即废")
    void captchaIsCheckedAndSingleUse() throws Exception {
        JsonNode captcha = client.getWithToken("/api/v1/auth/captcha", "");
        String captchaKey = captcha.path("data").path("captchaKey").asText();
        String captchaCode = redisTemplate.opsForValue().get(captchaKey);

        String body = """
                {"username":"%s","password":"%s","captchaKey":"%s","captchaCode":"ZZZZ"}
                """.formatted(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD, captchaKey);
        JsonNode wrong = postLogin(body);
        assertThat(wrong.path("code").asInt()).isEqualTo(ErrorCode.CAPTCHA_WRONG_OR_EXPIRED.getCode());

        // 同一个 captchaKey 即使这次填对也已作废 —— 不作废就能被无限次用于撞库
        String retry = """
                {"username":"%s","password":"%s","captchaKey":"%s","captchaCode":"%s"}
                """.formatted(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD, captchaKey, captchaCode);
        assertThat(postLogin(retry).path("code").asInt())
                .as("验证码校验后立即删除，无论对错")
                .isEqualTo(ErrorCode.CAPTCHA_WRONG_OR_EXPIRED.getCode());
    }

    @Test
    @DisplayName("10005｜连续 5 次密码错误锁定 15 分钟，msg 带剩余时间（00-通用约定 §8）")
    void fiveFailuresLockAccount() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(client.login(AuthFixtures.ADMIN_USERNAME, "WrongPwd2026").path("code").asInt())
                    .isEqualTo(ErrorCode.USERNAME_OR_PASSWORD_WRONG.getCode());
        }

        // 第 6 次即便密码正确也被锁定挡住
        JsonNode locked = client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD);
        assertThat(locked.path("code").asInt()).isEqualTo(ErrorCode.ACCOUNT_DISABLED_OR_LOCKED.getCode());
        assertThat(locked.path("msg").asText())
                .as("§8 要求 msg 提示锁定剩余时间")
                .contains("分钟");
    }

    @Test
    @DisplayName("登录成功也写 sys_login_log（成功与失败都留痕）")
    void successfulLoginIsAlsoLogged() throws Exception {
        assertThat(client.login(AuthFixtures.ADMIN_USERNAME, AuthFixtures.PASSWORD)
                .path("code").asInt()).isEqualTo(200);

        assertThat(fixtures.latestLoginLogStatus(AuthFixtures.ADMIN_USERNAME)).isEqualTo(0);
        assertThat(fixtures.loginLogCount(AuthFixtures.ADMIN_USERNAME)).isEqualTo(1);
    }

    private JsonNode postLogin(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/auth/login")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
