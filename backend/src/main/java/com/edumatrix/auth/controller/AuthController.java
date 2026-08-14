package com.edumatrix.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.auth.dto.ChangePasswordRequest;
import com.edumatrix.auth.dto.LoginRequest;
import com.edumatrix.auth.dto.RefreshRequest;
import com.edumatrix.auth.service.CaptchaService;
import com.edumatrix.auth.service.LoginLogService;
import com.edumatrix.auth.service.LoginRateLimiter;
import com.edumatrix.auth.service.LoginService;
import com.edumatrix.auth.service.MeService;
import com.edumatrix.auth.vo.CaptchaVO;
import com.edumatrix.auth.vo.LoginVO;
import com.edumatrix.auth.vo.MeVO;
import com.edumatrix.auth.vo.RefreshVO;
import com.edumatrix.common.response.R;

import jakarta.validation.Valid;

/**
 * 认证接口（03-01 §1.1~§1.6，六个，一个不多）。
 *
 * <p>前三条在 {@code common/config/SaTokenConfig.AUTH_WHITELIST} 里免登录
 * （00-通用约定 §2.3 的四条白名单，第四条 {@code /vod/decrypt-key} 归模块 12）；
 * 后三条要求已登录。
 *
 * <p>Controller 只做三件事：参数绑定、调 Service、包 {@code R<T>}
 * （05-工程结构.md §C2 对 {@code controller/} 的定义）。判定顺序、错误码、事务
 * 一律不在这里 —— 它们在 {@code service/}，登录与刷新才能共用同一份判定链。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginService loginService;
    private final CaptchaService captchaService;
    private final MeService meService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(LoginService loginService,
                          CaptchaService captchaService,
                          MeService meService,
                          LoginRateLimiter rateLimiter) {
        this.loginService = loginService;
        this.captchaService = captchaService;
        this.meService = meService;
        this.rateLimiter = rateLimiter;
    }

    /** §1.1 获取图形验证码。免登录；同一 IP 60 秒内最多 20 次（超限 429）。 */
    @GetMapping("/captcha")
    public R<CaptchaVO> captcha() {
        rateLimiter.checkCaptchaRate(LoginLogService.currentIp());
        return R.ok(captchaService.generate());
    }

    /** §1.2 登录。免登录；四类角色共用，服务端按账号自动识别 {@code userType}。 */
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return R.ok("登录成功", loginService.login(request));
    }

    /** §1.3 刷新令牌。免登录，凭 refreshToken；旧令牌立即失效（旋转）。 */
    @PostMapping("/refresh")
    public R<RefreshVO> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(loginService.refresh(request));
    }

    /** §1.4 登出。当前会话的两个 Token 同时作废；对已失效 Token 调用同样返回成功。 */
    @PostMapping("/logout")
    public R<Void> logout() {
        loginService.logout();
        return R.ok("登出成功", null);
    }

    /** §1.5 获取当前用户信息。仅返回本人信息（数据范围天然为本人）。 */
    @GetMapping("/me")
    public R<MeVO> me() {
        return R.ok(meService.currentUser());
    }

    /** §1.6 修改密码（本人）。成功后除当前会话外其余会话全部作废。 */
    @PutMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        loginService.changePassword(request);
        return R.ok("密码修改成功", null);
    }
}
