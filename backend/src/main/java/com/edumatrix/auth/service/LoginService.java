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
     *
     * <h2>原子消费之后的任何一步失败，旧 refreshToken 都已不可用 —— 这是有意的取舍</h2>
     * <p>覆盖两类失败，不只是校验那一类：
     * <ul>
     *   <li>{@code assertLoginable} 抛出（租户到期、账号封禁、学籍归档、节点停用）；
     *   <li>{@code openSession} 抛出（Redis 抖动、Sa-Token 装配问题）。
     * </ul>
     * 两者的结果相同：用户只能重新登录。
     *
     * <p><b>反过来「失败就把 token 放回去」会重新打开竞争窗口</b> ——
     * 那等于把原子消费退化成「取值 → 干活 → 也许删也许还回去」，
     * 并发的两个请求又能双双拿到有效值。
     *
     * <p>而这个取舍的代价很小：第一类失败的那些场景，<b>本来就意味着他登不进去</b>
     * （重新登录同样会被同一条判定链拒掉，只是错误码更明确）；第二类是基础设施故障，
     * 重新登录本就是合理处置。
     */
    public RefreshVO refresh(RefreshRequest request) {
        // 【第一步就把旧令牌原子消费掉】取值与删除在同一次 Redis 往返里完成，
        // 并发的两个刷新只有一个拿得到值，另一个当场 10006 ——「一次性使用」（§2.2 规则 3）
        // 是靠这一步成立的，而不是靠后面那句删除。完整推导见 TokenService#consumeRefreshToken
        TokenService.RefreshTokenRecord record = tokenService.consumeRefreshToken(request.getRefreshToken());
        Long userId = record.userId();

        // 刷新是白名单接口，没有会话 —— 租户从令牌里显式取（契约 §2.8 规则 1「从数据显式取」），
        // 而不是再开一处 ignore() 逃生舱。
        //
        // 【这里走的是「显式租户上下文」，它会压过「超管整体放行」——对超管两者同解，不是漏了分支】
        //   TenantHelper 有四条取值路径，这里用的是第一条<b>显式 runWithTenant</b>，
        //   而超管跨租户靠的是第三条<b>超管会话整体放行</b>（契约 §2.9）。
        //   两条路径不能同时生效：isSuperAdminSession() 的判定是
        //   「没有显式租户上下文 且 provider.isSuperAdmin()」，一旦 runWithTenant 设了值，
        //   整体放行就被关掉了。这是 TenantHelper 自己的定案，不是这里的疏忽 ——
        //   它的原话是「显式租户上下文优先于超管身份：超管手动指定要操作哪个租户时，
        //   应当按那个租户过滤，而不是继续全局放行」。
        //
        //   <b>对超管而言两者结果相同</b>，因为刷新链路要读的三样东西全都在 tenant_id = 0 里：
        //     ① sys_user 里超管自己那一行（NOT NULL 列，值就是 0，见 TokenService#issueRefreshToken）；
        //     ② org_node 的 0 号平台根（契约 §2.1，tenant_id = 0）；
        //     ③ 冻结集校验对上面两者的点查。
        //   一处需要跨租户的读都没有。
        //
        //   <b>所以不要在这里加「if 超管则跳过 runWithTenant」的分支</b> —— 那会让超管这条路
        //   与其他角色分叉，而分叉出来的那一支没有任何测试覆盖得到的收益。
        //   超管刷新令牌的端到端断言见 AuthTokenLifecycleIT#superAdminCanRefreshToken。
        AuthUser user = TenantHelper.runWithTenant(record.tenantId(),
                () -> authUserMapper.selectById(userId));
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        loginCheckService.assertLoginable(user);

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
