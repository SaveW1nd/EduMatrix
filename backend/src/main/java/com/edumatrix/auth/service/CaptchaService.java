package com.edumatrix.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.edumatrix.auth.vo.CaptchaVO;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.response.BizException;

/**
 * 图形验证码（03-01 §1.1 / §1.2）。
 *
 * <p>有效期 300 秒、4 位、不区分大小写、<b>一次性</b>（校验后立即删除，
 * 无论对错）—— 不删的话，同一个验证码可以被无限次用于撞库，验证码就等于不存在。
 */
@Service
public class CaptchaService {

    /** 03-01 §1.1：{@code expireSeconds} 有效期 300 秒。 */
    public static final int EXPIRE_SECONDS = 300;

    /** 03-01 §1.2：{@code captchaCode} 4 位。 */
    private static final int CODE_LENGTH = 4;

    /**
     * {@code captchaKey} 的合法形状：{@code cap:} + 20 位小写十六进制
     * （对齐 03-01 §1.1 示例 {@code "cap:9f8e7d6c5b4a3f2e1d0c"}）。
     *
     * <p><b>这不是格式洁癖，是必要的输入校验</b>：{@code captchaKey} 由前端原样回传，
     * 直接拿去 {@code GET} + {@code DELETE} 就等于把「删除任意 Redis key」的能力暴露出去 ——
     * 传 {@code auth:refresh:xxx} 就能删别人的刷新令牌，传 {@code frozen:{tenantId}}
     * 就能<b>把整个租户的冻结集删掉</b>，停用功能当场失效。
     */
    private static final Pattern CAPTCHA_KEY_PATTERN = Pattern.compile("^cap:[0-9a-f]{20}$");

    private final StringRedisTemplate redisTemplate;
    private final CaptchaImageRenderer renderer;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.renderer = new CaptchaImageRenderer();
    }

    /** 生成验证码并写入 Redis，返回 03-01 §1.1 的三个字段。 */
    public CaptchaVO generate() {
        String code = renderer.randomCode(CODE_LENGTH);
        String captchaKey = RedisKeys.captcha(randomHex());
        redisTemplate.opsForValue().set(captchaKey, code, Duration.ofSeconds(EXPIRE_SECONDS));
        return new CaptchaVO(captchaKey, renderer.renderAsDataUri(code), EXPIRE_SECONDS);
    }

    /**
     * 校验并作废验证码；不匹配或已过期一律 {@code 10004}。
     *
     * <p>不区分「验证码错」与「验证码已过期」—— 00-通用约定 §9.2 给 {@code 10004}
     * 的定义本就是二者合一。
     */
    public void verifyAndConsume(String captchaKey, String captchaCode) {
        if (captchaKey == null || captchaCode == null
                || !CAPTCHA_KEY_PATTERN.matcher(captchaKey).matches()) {
            throw new BizException(ErrorCode.CAPTCHA_WRONG_OR_EXPIRED);
        }
        String expected = redisTemplate.opsForValue().get(captchaKey);
        // 一次性：无论对错都作废，避免同一个码被反复用于撞库
        redisTemplate.delete(captchaKey);
        if (expected == null || !expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BizException(ErrorCode.CAPTCHA_WRONG_OR_EXPIRED);
        }
    }

    /** 20 位小写十六进制，与 03-01 §1.1 的示例同形。 */
    private String randomHex() {
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(20);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
