package com.edumatrix.auth.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.response.BizException;

/**
 * 登录相关的两类频率控制（00-通用约定 §8）。
 *
 * <table border="1">
 *   <caption>§8 限流约定</caption>
 *   <tr><th>场景</th><th>限制</th><th>超限响应</th></tr>
 *   <tr><td>{@code POST /auth/login}</td><td>同一 IP 60 秒内最多 10 次</td><td>HTTP 429</td></tr>
 *   <tr><td>{@code GET /auth/captcha}</td><td>同一 IP 60 秒内最多 20 次</td><td>HTTP 429</td></tr>
 *   <tr><td>同一账号连续密码错误</td><td>连续 5 次锁定 15 分钟</td><td>{@code 10005}，msg 带剩余时间</td></tr>
 * </table>
 *
 * <h2>两者不是一回事，别合并</h2>
 * <p>IP 限流防的是<b>机器刷</b>，返回 429（框架层，客户端按 {@code Retry-After} 退避）；
 * 账号锁定防的是<b>撞库</b>，返回 {@code 10005}（业务层，要给用户看懂的提示）。
 * 合并成一个只会让前端分不清「歇一会儿再试」和「你的账号被锁了 15 分钟」。
 *
 * <h2>锁定剩余时间直接取 key 的 TTL</h2>
 * <p>不另存一个「解锁时刻」字段 —— 两份时间早晚会不一致，而不一致的表现是
 * 「提示还剩 3 分钟、实际 5 分钟后才能登」这种没人会去查的小毛病。
 */
@Service
public class LoginRateLimiter {

    /** 00-通用约定 §8：登录同一 IP 60 秒内最多 10 次。 */
    public static final int LOGIN_LIMIT_PER_MINUTE = 10;
    /** 00-通用约定 §8：验证码同一 IP 60 秒内最多 20 次。 */
    public static final int CAPTCHA_LIMIT_PER_MINUTE = 20;
    /** 00-通用约定 §8：连续 5 次密码错误即锁定。 */
    public static final int MAX_FAIL_TIMES = 5;
    /** 00-通用约定 §8：锁定 15 分钟。 */
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);
    private static final String HEADER_RETRY_AFTER = "Retry-After";

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // =====================================================================
    // IP 限流 —— 429
    // =====================================================================

    /** {@code POST /auth/login} 的 IP 限流；超限抛 429。 */
    public void checkLoginRate(String ip) {
        checkRate(RedisKeys.rateLimitLogin(ip), LOGIN_LIMIT_PER_MINUTE);
    }

    /** {@code GET /auth/captcha} 的 IP 限流；超限抛 429。 */
    public void checkCaptchaRate(String ip) {
        checkRate(RedisKeys.rateLimitCaptcha(ip), CAPTCHA_LIMIT_PER_MINUTE);
    }

    private void checkRate(String key, int limit) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, RATE_WINDOW);
        }
        if (count != null && count > limit) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            setRetryAfter(ttl == null || ttl < 0 ? RATE_WINDOW.toSeconds() : ttl);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    // =====================================================================
    // 账号锁定 —— 10005
    // =====================================================================

    /**
     * 判定式③：账号是否处于锁定期，是则抛 {@code 10005} 并在 msg 里带剩余时间。
     *
     * <p><b>放在密码校验之前</b>：锁定期间不该继续消耗密码尝试次数，
     * 否则「锁 15 分钟」会被持续的错误尝试无限续期，实际变成永久锁定。
     */
    public void assertNotLocked(String username) {
        Long ttl = redisTemplate.getExpire(RedisKeys.loginLock(username), TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED_OR_LOCKED, lockedMessage(ttl));
        }
    }

    /**
     * 密码错误后累计；达到 5 次即锁定 15 分钟。
     *
     * <p>计数 key 的 TTL 与锁定时长一致 —— 「连续」的窗口与「锁定」的窗口同长，
     * 是最不需要解释的一种口径。
     */
    public void onPasswordFail(String username) {
        String failKey = RedisKeys.loginFail(username);
        Long times = redisTemplate.opsForValue().increment(failKey);
        if (times != null && times == 1L) {
            redisTemplate.expire(failKey, LOCK_DURATION);
        }
        if (times != null && times >= MAX_FAIL_TIMES) {
            redisTemplate.opsForValue()
                    .set(RedisKeys.loginLock(username), String.valueOf(times), LOCK_DURATION);
            redisTemplate.delete(failKey);
        }
    }

    /** 登录成功后清零连续失败计数（"连续"的语义）。 */
    public void onLoginSuccess(String username) {
        redisTemplate.delete(RedisKeys.loginFail(username));
    }

    /** {@code 10005} 在锁定场景下的 msg（00-通用约定 §8 要求带剩余时间）。 */
    public static String lockedMessage(long remainSeconds) {
        long minutes = (remainSeconds + 59) / 60;
        return "密码连续错误次数过多，账号已锁定，请 " + minutes + " 分钟后再试";
    }

    /**
     * 429 的 {@code Retry-After}（00-通用约定 §9.1：客户端按它退避）。
     *
     * <p>在抛异常之前写响应头 —— 全局异常处理器只负责响应体与状态码，
     * 已经写进 response 的头不会被它抹掉。
     */
    private void setRetryAfter(long seconds) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getResponse() != null) {
            attrs.getResponse().setHeader(HEADER_RETRY_AFTER, String.valueOf(Math.max(seconds, 1)));
        }
    }
}
