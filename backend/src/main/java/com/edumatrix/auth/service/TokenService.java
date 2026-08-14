package com.edumatrix.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.edumatrix.auth.entity.AuthUser;
import com.edumatrix.auth.session.LoginHelper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.redis.RedisKeys;
import com.edumatrix.common.response.BizException;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 双 Token 的下发、旋转与作废（00-通用约定 §2.1 / §2.2）。
 *
 * <table border="1">
 *   <caption>§2.1</caption>
 *   <tr><th>Token</th><th>有效期</th><th>用途</th></tr>
 *   <tr><td>{@code accessToken}</td><td>2 小时</td><td>业务接口访问凭证（Sa-Token 签发与校验）</td></tr>
 *   <tr><td>{@code refreshToken}</td><td>7 天</td><td><b>仅</b>用于 {@code POST /auth/refresh}</td></tr>
 * </table>
 *
 * <h2>refreshToken 为什么是不透明随机串而不是 JWT</h2>
 * <p>§2.2 规则 3/4 要求「每次刷新下发新的，<b>旧的立即失效</b>」。JWT 是自证的 ——
 * 服务端不存它也能验，代价正是<b>无法让某一个已签发的 JWT 提前失效</b>；
 * 要做到就得再维护一份黑名单，那还不如从一开始就用「Redis 里有记录才算数」的不透明串：
 * 旋转 = 删旧写新，作废 = 删。
 *
 * <p>顺带记一处<b>示例与实现的口径差</b>：03-01 §1.2/§1.3 的响应示例里
 * {@code refreshToken} 长得像 JWT（{@code eyJhbGciOi...}）。前端<b>不要按 JWT 解析它</b> ——
 * 它是不透明串，除了原样回传没有任何可读结构。已记入 04-实施计划.md §E 的 F-17。
 *
 * <h2>Redis 里存哈希不存原文</h2>
 * <p>不是防 Redis 被攻破（那时已全线失守），是防运维在 {@code redis-cli} 里
 * {@code KEYS auth:refresh:*} 一眼看到全部有效令牌 —— 那等于把所有人的会话拿在手上。
 */
@Service
public class TokenService {

    /** 00-通用约定 §2.1：refreshToken 有效期 7 天。 */
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);

    /** 03-01 §1.2：{@code tokenType} 固定 Bearer。 */
    public static final String TOKEN_TYPE = "Bearer";

    /** 不透明串：32 字节随机数的 URL-safe Base64（无填充），43 字符。 */
    private static final int RAW_TOKEN_BYTES = 32;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    public TokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // =====================================================================
    // 下发
    // =====================================================================

    /**
     * 登录/刷新成功后建立会话并下发双 Token。
     *
     * <p>会话里只放 {@code userId / userType / nodeId / tenantId}（+ 强制改密标记），
     * <b>{@code ancestors} 一律不进</b>（契约 §2.3）。
     *
     * @return 新的 refreshToken 原文（只在本次响应里出现一次，服务端此后只存哈希）
     */
    public String openSession(AuthUser user) {
        StpUtil.login(user.getId());
        LoginHelper.writeSession(user.getId(), user.getUserType(), user.getNodeId(),
                user.getTenantId(), user.needChangePassword());
        return issueRefreshToken(user.getId(), user.getTenantId());
    }

    /** 当前 accessToken 值（{@code StpUtil} 从请求上下文取）。 */
    public String currentAccessToken() {
        return StpUtil.getTokenValue();
    }

    /** 当前 accessToken 的剩余有效秒数（03-01 §1.2 的 {@code expiresIn}）。 */
    public long currentAccessTokenExpiresIn() {
        long timeout = StpUtil.getTokenTimeout();
        return timeout > 0 ? timeout : 0;
    }

    /** {@code refreshExpiresIn}（03-01 §1.2）。 */
    public long refreshTokenExpiresIn() {
        return REFRESH_TOKEN_TTL.toSeconds();
    }

    // =====================================================================
    // 旋转
    // =====================================================================

    /**
     * 校验 refreshToken 并返回它绑定的 {@code userId} 与 {@code tenantId}；
     * 无效/过期/已被旋转作废一律 {@code 10006}。
     *
     * <p>格式先行校验：不合形状的串直接拒，不去 Redis 转一圈 ——
     * 与 {@code CaptchaService} 校验 {@code captchaKey} 同源，
     * 别让外部输入决定要去读哪个 key。
     *
     * <p><b>为什么值里要带 {@code tenantId}</b>：刷新是白名单接口、<b>没有会话</b>，
     * 而后续要读 {@code sys_user} / {@code org_node} 等带 {@code tenant_id} 的表。
     * 令牌自带租户后，那条链路可以走 {@code TenantHelper.runWithTenant}（契约 §2.8 规则 1
     * 「从数据显式取」），<b>不必再开一处 {@code ignore()} 逃生舱</b> ——
     * 全系统的 {@code ignore()} 因此仍然只有「登录按用户名查 sys_user」一处，
     * 与 {@code TenantHelper} 类注释里那句「现有的正当理由只有一类」保持一致。
     */
    public RefreshTokenRecord resolveRefreshToken(String refreshToken) {
        if (refreshToken == null || !TOKEN_PATTERN.matcher(refreshToken).matches()) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        String value = redisTemplate.opsForValue().get(RedisKeys.refreshToken(hash(refreshToken)));
        if (value == null) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            // 旧格式或被人工改过的值：按无效处理，而不是猜一个租户（契约 §2.8 规则 3）
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        return new RefreshTokenRecord(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
    }

    /** refreshToken 在 Redis 里绑定的两项。 */
    public record RefreshTokenRecord(Long userId, Long tenantId) {
    }

    /**
     * 作废一个 refreshToken（旋转时对旧令牌调用）。
     *
     * <p><b>先删旧再发新</b>：反过来的话，两个令牌会同时有效一小段时间，
     * 而 §2.2 规则 3 的原话是「一次性使用，防重放」。
     */
    public void revokeRefreshToken(Long userId, String refreshToken) {
        String tokenHash = hash(refreshToken);
        redisTemplate.delete(RedisKeys.refreshToken(tokenHash));
        redisTemplate.opsForSet().remove(RedisKeys.refreshTokenUserIndex(userId), tokenHash);
    }

    // =====================================================================
    // 作废
    // =====================================================================

    /**
     * 登出：<b>只作废当前会话</b>的 accessToken 与 refreshToken（03-01 §1.4）。
     *
     * <p>不是 {@code StpUtil.logout(userId)} —— 那会把该账号<b>全部设备</b>踢下线，
     * 而 §1.4 说的是「该用户当前会话的 accessToken 与 refreshToken 同时作废」。
     * 手机上登出顺手把电脑上也登出，是另一件事。
     *
     * <p>对已失效 Token 调用同样返回成功（§1.4 原文），所以这里不做任何存在性断言。
     */
    public void closeCurrentSession() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return;
        }
        String tokenHash = currentRefreshHash();
        if (tokenHash != null) {
            redisTemplate.delete(RedisKeys.refreshToken(tokenHash));
            redisTemplate.opsForSet().remove(RedisKeys.refreshTokenUserIndex(userId), tokenHash);
        }
        StpUtil.logout();
    }

    /**
     * 改密后作废该账号<b>除当前会话外</b>的全部会话（03-01 §1.6 / PRD §7.3 安全条款 2）。
     *
     * <p>两侧都要清：Sa-Token 侧逐个 {@code logoutByTokenValue}，refreshToken 侧按
     * {@code auth:refresh:uid:{userId}} 这个索引删 —— 没有索引就只能 {@code KEYS} 扫描，
     * 而那在生产上是禁止的。
     *
     * <p><b>这与「不得为让停用生效而遍历子树逐个 logout」不冲突</b>：那条禁令针对的是
     * 「停用一个节点 → 为其子树 1.1 万人各写两次」，规模由<b>受影响人数</b>决定；
     * 这里是「一个人改自己的密码 → 踢他自己的几个设备」，规模是个位数且不随组织规模增长。
     */
    public void revokeOtherSessions(Long userId) {
        String currentToken = StpUtil.getTokenValue();
        String currentHash = currentRefreshHash();

        List<String> tokens = StpUtil.getTokenValueListByLoginId(userId);
        for (String token : tokens) {
            if (token != null && !token.equals(currentToken)) {
                StpUtil.logoutByTokenValue(token);
            }
        }

        String indexKey = RedisKeys.refreshTokenUserIndex(userId);
        Set<String> hashes = redisTemplate.opsForSet().members(indexKey);
        if (hashes == null) {
            return;
        }
        for (String tokenHash : hashes) {
            if (!tokenHash.equals(currentHash)) {
                redisTemplate.delete(RedisKeys.refreshToken(tokenHash));
                redisTemplate.opsForSet().remove(indexKey, tokenHash);
            }
        }
    }

    // =====================================================================

    private String issueRefreshToken(Long userId, Long tenantId) {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hash(token);

        redisTemplate.opsForValue().set(RedisKeys.refreshToken(tokenHash),
                userId + ":" + (tenantId == null ? 0L : tenantId), REFRESH_TOKEN_TTL);
        String indexKey = RedisKeys.refreshTokenUserIndex(userId);
        redisTemplate.opsForSet().add(indexKey, tokenHash);
        redisTemplate.expire(indexKey, REFRESH_TOKEN_TTL);

        // 绑定到本次会话的 Token Session（一 Token 一份），登出时据此只删自己那一个
        StpUtil.getTokenSession().set(LoginHelper.TOKEN_SESSION_REFRESH_HASH, tokenHash);
        return token;
    }

    private String currentRefreshHash() {
        try {
            Object value = StpUtil.getTokenSession().get(LoginHelper.TOKEN_SESSION_REFRESH_HASH);
            return value == null ? null : value.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明 JRE 被裁剪坏了
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
