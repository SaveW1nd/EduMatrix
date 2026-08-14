package com.edumatrix.auth.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.edumatrix.auth.dto.ChangePasswordRequest;
import com.edumatrix.auth.dto.LoginRequest;
import com.edumatrix.auth.dto.RefreshRequest;
import com.edumatrix.auth.entity.AuthUser;
import com.edumatrix.auth.mapper.AuthUserMapper;
import com.edumatrix.auth.session.LoginHelper;
import com.edumatrix.auth.vo.LoginVO;
import com.edumatrix.auth.vo.RefreshVO;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 登录 / 刷新 / 登出 / 改密的编排（03-01 §1.2 §1.3 §1.4 §1.6）。
 *
 * <h2>判定顺序（八步，顺序本身就是规则）</h2>
 * <pre>
 * ① IP 限流 10 次/60s ─────────────── 429      机器刷
 * ② 验证码 ────────────────────────── 10004    防暴力破解
 * ③ 账号锁定（连续 5 次错）────────── 10005    带剩余时间
 * ④ 用户名 + BCrypt 密码 ──────────── 10003    失败即累加计数
 * ⑤ sys_user.status = 1 ───────────── 10005    账号级封禁
 * ⑥ 租户停用 / 到期 ───────────────── 10007    超管跳过
 * ⑦ org_student.status = 2 ────────── 10015    仅学生
 * ⑧ 组织侧两段停用 ────────────────── 10017    本人 / 祖先管理员
 * </pre>
 *
 * <p>三处顺序是有理由的，别调：
 * <ul>
 *   <li><b>①在②前</b> —— 反过来的话，限流本身能被「不断取新验证码」绕过；
 *   <li><b>③在④前</b> —— 锁定期间不该继续消耗尝试次数，否则持续的错误尝试会让
 *       「锁 15 分钟」不断续期，实际变成永久锁定；
 *   <li><b>⑥在④后</b> —— 未验密码者不该知道「这家机构存不存在 / 到没到期」。
 *       {@code 10007} 是一条对外可见的机构状态。
 * </ul>
 *
 * <h2>成功与失败都写 sys_login_log</h2>
 * <p>PRD F1-1 验收标准要求失败也留痕。唯一不写的是 <b>①的 429</b>：
 * 那是前置的流量控制，为每个被挡住的机器请求写一行日志，等于把限流变成一次写库放大。
 */
@Service
public class LoginService {

    private final AuthUserMapper authUserMapper;
    private final LoginCheckService loginCheckService;
    private final LoginRateLimiter rateLimiter;
    private final CaptchaService captchaService;
    private final PasswordService passwordService;
    private final TokenService tokenService;
    private final LoginLogService loginLogService;

    public LoginService(AuthUserMapper authUserMapper,
                        LoginCheckService loginCheckService,
                        LoginRateLimiter rateLimiter,
                        CaptchaService captchaService,
                        PasswordService passwordService,
                        TokenService tokenService,
                        LoginLogService loginLogService) {
        this.authUserMapper = authUserMapper;
        this.loginCheckService = loginCheckService;
        this.rateLimiter = rateLimiter;
        this.captchaService = captchaService;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.loginLogService = loginLogService;
    }

    // =====================================================================
    // §1.2 登录
    // =====================================================================

    public LoginVO login(LoginRequest request) {
        String username = request.getUsername();

        // ① IP 限流：在验证码之前，且不写登录日志
        rateLimiter.checkLoginRate(LoginLogService.currentIp());

        AuthUser user = null;
        try {
            captchaService.verifyAndConsume(request.getCaptchaKey(), request.getCaptchaCode()); // ②
            rateLimiter.assertNotLocked(username);                                              // ③

            user = loadByUsername(username);                                                    // ④
            if (user == null || !passwordService.matches(request.getPassword(), user.getPassword())) {
                rateLimiter.onPasswordFail(username);
                // 账号不存在与密码错误<b>不区分</b>（00-通用约定 §9.2：防撞库探测）
                throw new BizException(ErrorCode.USERNAME_OR_PASSWORD_WRONG);
            }

            loginCheckService.assertLoginable(user);                                            // ⑤⑥⑦⑧
        } catch (BizException e) {
            loginLogService.recordFailure(
                    user == null ? null : user.getId(), username,
                    user == null ? null : user.getTenantId(), e.getMessage());
            throw e;
        }

        rateLimiter.onLoginSuccess(username);
        touchLastLoginTime(user);

        String refreshToken = tokenService.openSession(user);
        loginLogService.recordSuccess(user.getId(), username, user.getTenantId());

        return new LoginVO(
                TokenService.TOKEN_TYPE,
                tokenService.currentAccessToken(),
                tokenService.currentAccessTokenExpiresIn(),
                refreshToken,
                tokenService.refreshTokenExpiresIn(),
                user.getId(),
                user.getUserType(),
                user.needChangePassword());
    }

    // =====================================================================
    // §1.3 刷新令牌
    // =====================================================================

    /**
     * refreshToken <b>旋转</b>：下发新的，旧的立即失效（00-通用约定 §2.2 规则 3）。
     *
     * <p>同样跑⑤⑥⑦⑧四步 —— <b>停用后不许续签</b>（§1.3 的错误码表里就有
     * {@code 10005} / {@code 10007} / {@code 10017}）。少跑这一段，被停用的账号
     * 就能靠 7 天有效期的 refreshToken 无限续命。
     */
    public RefreshVO refresh(RefreshRequest request) {
        TokenService.RefreshTokenRecord record = tokenService.resolveRefreshToken(request.getRefreshToken());
        Long userId = record.userId();

        // 刷新是白名单接口，没有会话 —— 租户从令牌里显式取（契约 §2.8 规则 1「从数据显式取」），
        // 而不是再开一处 ignore() 逃生舱
        AuthUser user = TenantHelper.runWithTenant(record.tenantId(),
                () -> authUserMapper.selectById(userId));
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        loginCheckService.assertLoginable(user);

        // 先作废旧的再发新的：反过来会有两个令牌同时有效的窗口（§2.2 规则 3「一次性使用，防重放」）
        tokenService.revokeRefreshToken(userId, request.getRefreshToken());
        String refreshToken = tokenService.openSession(user);

        return new RefreshVO(
                TokenService.TOKEN_TYPE,
                tokenService.currentAccessToken(),
                tokenService.currentAccessTokenExpiresIn(),
                refreshToken,
                tokenService.refreshTokenExpiresIn());
    }

    // =====================================================================
    // §1.4 登出
    // =====================================================================

    /** 当前会话的两个 Token 同时作废；对已失效 Token 调用同样返回成功（§1.4）。 */
    public void logout() {
        tokenService.closeCurrentSession();
    }

    // =====================================================================
    // §1.6 修改密码（本人）
    // =====================================================================

    /**
     * 改密成功后：{@code needChangePassword} 置 false，
     * <b>除当前会话外</b>该账号其余在线会话的两个 Token 全部作废
     * （§1.6 / PRD §7.3「改密即时失效对应 Token」）。
     */
    public void changePassword(ChangePasswordRequest request) {
        Long userId = LoginHelper.getUserId();
        // 有会话：租户由插件按会话注入，既不用 ignore 也不用 runWithTenant
        AuthUser user = authUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!passwordService.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.OLD_PASSWORD_WRONG);
        }
        passwordService.assertStrongEnough(request.getNewPassword(), request.getOldPassword());

        String encoded = passwordService.encode(request.getNewPassword());
        TenantHelper.runWithTenant(user.getTenantId(),
                () -> authUserMapper.updatePassword(userId, encoded));

        LoginHelper.clearPwdResetFlag(userId);
        tokenService.revokeOtherSessions(userId);
    }

    // =====================================================================

    /**
     * 按用户名取账号 —— <b>全系统唯一正当的跨租户查询</b>。
     *
     * <p>登录那一刻还不知道租户是谁，<b>租户恰恰是这次查询的结果</b>
     * （{@code TenantHelper} 类注释逐字如此）。不包 {@code ignore} 的话，
     * 租户插件走到 {@code requireTenantId()} 抛异常，表现为「谁都登不进来」。
     */
    private AuthUser loadByUsername(String username) {
        return TenantHelper.ignore(() -> authUserMapper.selectByUsername(username));
    }

    /** 回写最后登录时间（模块 02 涉及表：{@code sys_user} 的 {@code last_login_time}）。 */
    private void touchLastLoginTime(AuthUser user) {
        TenantHelper.runWithTenant(user.getTenantId(),
                () -> authUserMapper.updateLastLoginTime(user.getId(), LocalDateTime.now()));
    }
}
