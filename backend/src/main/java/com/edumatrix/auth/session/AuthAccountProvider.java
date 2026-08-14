package com.edumatrix.auth.session;

import org.springframework.stereotype.Component;

import com.edumatrix.auth.service.PasswordService;
import com.edumatrix.auth.service.TokenService;
import com.edumatrix.common.account.PasswordHasher;
import com.edumatrix.common.account.SessionRevoker;

/**
 * {@code common/account} 两个 SPI 的实现：把 {@code auth} 领域的会话与口令能力
 * 暴露给 {@code system}（模块 03）与 {@code org}（模块 07）。
 *
 * <p>与 {@link SaTokenCurrentContextProvider} 同构 —— 接口在 {@code common/}、
 * 实现在 {@code auth/}、消费方按接口注入。跨领域依赖为零，
 * {@code scripts/check_backend_conventions.sh} 的检查③零命中。
 *
 * <h2>为什么一个类实现两个接口</h2>
 * <p>它们的形状完全相同：<b>同一个提供方（auth）、同一批消费方（system + org）、
 * 同样是「委派一行」的适配</b>。拆成两个类只会多一处装配点，而每个装配点都是
 * 一次「新模块忘了注入哪一个」的机会。两个接口分开声明是为了让消费方
 * <b>按需要的能力</b>依赖（只建用户的地方不该拿到作废会话的能力）。
 *
 * <p>本类<b>不含任何逻辑</b>：一旦这里出现 if / 循环 / 错误码，就说明该逻辑
 * 放错了地方 —— 它要么属于 {@code TokenService} / {@code PasswordService}，
 * 要么属于调用它的那个业务 Service。
 */
@Component
public class AuthAccountProvider implements SessionRevoker, PasswordHasher {

    private final TokenService tokenService;
    private final PasswordService passwordService;

    public AuthAccountProvider(TokenService tokenService, PasswordService passwordService) {
        this.tokenService = tokenService;
        this.passwordService = passwordService;
    }

    @Override
    public void revokeAllSessions(Long userId) {
        tokenService.revokeAllSessions(userId);
    }

    @Override
    public String hash(String rawPassword) {
        return passwordService.encode(rawPassword);
    }
}
