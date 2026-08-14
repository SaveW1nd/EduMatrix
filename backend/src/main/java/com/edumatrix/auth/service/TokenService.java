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
     * <b>本方法有副作用：取值即删除。</b>校验 refreshToken 并<b>原子地消费掉它</b>，
     * 返回它绑定的 {@code userId} 与 {@code tenantId}；无效/过期/已被消费一律 {@code 10006}。
     *
     * <h2>为什么必须原子</h2>
     * <p>00-通用约定 §2.2 规则 3：refreshToken <b>一次性使用，防重放</b>。
     * 若「取值」与「删除」分成两步，中间隔着 {@code selectById} 与
     * {@code assertLoginable}（租户 / 学籍 / 节点停用三项校验，至少三次数据库往返），
     * 两个并发刷新会双双取到有效值、双双通过校验、双双签发新会话：
     * <pre>
     * 请求 1  GET → 有效 ┐
     * 请求 2  GET → 有效 ┘ 还没人删
     *         两者都通过 assertLoginable，都 DEL（第二次 no-op），都签发新令牌
     * </pre>
     *
     * <p><b>这不是理论问题</b>：accessToken 过期的那一刻，前端若有两个业务请求在飞，
     * <b>两个都会触发刷新</b>；弱网超时重发同理。这是刷新令牌机制最常见的并发形态。
     *
     * <p><b>后果分两层，第二层才是重点</b>：
     * <ol>
     *   <li><b>轻</b>：一个旧令牌换出两个新的，会话数翻倍，索引集合膨胀；
     *   <li><b>重</b>：「一次性使用」的设计意图是 —— <b>令牌被盗时，攻击者与用户谁先用谁拿到新的，
     *       另一个立刻失效，泄露由此暴露</b>。两边都能成功的话，<b>泄露永远不会被发现</b>。
     *       这是个<b>安全属性</b>，不是性能问题。
     * </ol>
     *
     * <p>实现用 Redis 的 {@code GETDEL}（{@code opsForValue().getAndDelete}，Redis 6.2+；
     * 本项目 {@code deploy/docker-compose.dev.yml} 钉的是 {@code redis:7-alpine}）——
     * 一次往返里取值并删除，<b>竞争的胜负就在这一行分出</b>：并发的两个请求只有一个拿得到非 null。
     * <b>不用 {@code WATCH/MULTI} 重试循环</b>：那会把一个两行的问题变成一段需要单独测的状态机。
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
    public RefreshTokenRecord consumeRefreshToken(String refreshToken) {
        if (refreshToken == null || !TOKEN_PATTERN.matcher(refreshToken).matches()) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        String tokenHash = hash(refreshToken);
        // 竞争在这一行分胜负：GETDEL 一次往返取值并删除，并发者只有一个拿得到非 null
        String value = redisTemplate.opsForValue().getAndDelete(RedisKeys.refreshToken(tokenHash));
        if (value == null) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            // 旧格式或被人工改过的值：按无效处理，而不是猜一个租户（契约 §2.8 规则 3）。
            //
            // 【已知边界，不是漏写】这一支<b>无法</b>从索引集合里 SREM —— userId 就在这个坏值里，
            // 解析不出来就没有 indexKey 可用。于是集合里会留一个孤儿 hash。无害：
            // ① revokeOtherSessions 遍历到它时 delete 一个不存在的 key，是 no-op；
            // ② 索引集合有 7 天 TTL（每次 issueRefreshToken 都会 expire 刷新），孤儿最终随集合过期消失。
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        Long userId = Long.valueOf(parts[0]);
        // 索引集合的清理跟着走。它<b>不需要</b>与 GETDEL 原子 —— 那个 hash 此刻已经被删，
        // 谁都换不出新令牌了；这一步只是别让集合里留下已失效的成员
        forget(userId, tokenHash);
        return new RefreshTokenRecord(userId, Long.valueOf(parts[1]));
    }

    /** refreshToken 在 Redis 里绑定的两项。 */
    public record RefreshTokenRecord(Long userId, Long tenantId) {
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
     * <p><b>这里不做任何存在性断言，理由是「失效 Token 根本到不了这里」</b> ——
     * {@code /auth/logout} 不在免登录白名单里（00-通用约定 §2.3 穷举四条），
     * Sa-Token 拦截器的 {@code checkLogin()} 先于本方法执行，
     * Token 已失效或缺失时返回 <b>HTTP 401</b>（§1.4，F-18 定案）。
     *
     * <p>（§1.4 原先写的是「对已失效 Token 调用同样返回成功」，与 §2.3 的白名单穷举互斥；
     * F-18 定案改的是措辞、<b>白名单一条未加</b>，行为本就是现在这样。）
     */
    public void closeCurrentSession() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            return;
        }
        String tokenHash = currentRefreshHash();
        if (tokenHash != null) {
            forget(userId, tokenHash);
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

        Set<String> hashes = redisTemplate.opsForSet().members(RedisKeys.refreshTokenUserIndex(userId));
        if (hashes == null) {
            return;
        }
        for (String tokenHash : hashes) {
            if (!tokenHash.equals(currentHash)) {
                forget(userId, tokenHash);
            }
        }
    }

    /**
     * 作废该账号<b>全部</b>在线会话 —— <b>含调用者自己那一个</b>（若调用者就是本人）。
     *
     * <p>由 {@code common/account/SessionRevoker} 暴露给 {@code system}（模块 03）与
     * {@code org}（模块 07）：03-01 §2.4 删除用户 / §2.5 重置密码 / §2.6 停用，
     * 03-02 §3.6 重置人员密码 / §4.4 / §5.4 / §6.4 删除人员，
     * 七处逐字写着「作废（该用户全部）在线 Token」。
     *
     * <h2>与 {@link #revokeOtherSessions} 的唯一区别：不保留当前会话</h2>
     * <p>那个是「本人改密 → 踢掉自己的其他设备」，当前这台不该被踢下去；
     * 本方法的作用对象永远是<b>别人</b> —— 03-01 §2.3/§2.4/§2.6 都有 {@code 10012}
     * 挡住「对当前登录账号执行该操作」，而 §2.5 的原文就是「该用户<b>全部</b>在线会话强制下线」。
     * 若将来出现「管理员重置自己的密码」这种调用，被一起踢下线正是期望行为。
     *
     * <p><b>这与「不得为让停用生效而遍历子树逐个 logout」不冲突</b>：那条禁令针对的是
     * 「停用一个节点 → 为其子树 1.1 万人各写两次」，规模由<b>受影响人数</b>决定；
     * 这里是「处置一个账号 → 踢他自己的几个设备」，规模是个位数且不随组织规模增长。
     * 与 {@link #revokeOtherSessions} 的类注释同一条理由。
     *
     * <p>{@code userId} 为 {@code null} 时静默返回：调用方通常刚从库里读出那一行，
     * 让它再判一次 null 只会在七个调用点各写一遍同样的 if。
     */
    public void revokeAllSessions(Long userId) {
        if (userId == null) {
            return;
        }
        // Sa-Token 侧：该账号名下的全部 Token 一律登出。
        // 用 logoutByTokenValue 逐个而不是 StpUtil.logout(userId)：两者效果相同，
        // 但逐个走与 revokeOtherSessions 同一条路径，将来要加审计日志时只有一处形态
        for (String token : StpUtil.getTokenValueListByLoginId(userId)) {
            if (token != null) {
                StpUtil.logoutByTokenValue(token);
            }
        }

        // refreshToken 侧：按 auth:refresh:uid:{userId} 索引删，不做 KEYS 扫描（生产禁止）
        Set<String> hashes = redisTemplate.opsForSet().members(RedisKeys.refreshTokenUserIndex(userId));
        if (hashes == null) {
            return;
        }
        for (String tokenHash : hashes) {
            forget(userId, tokenHash);
        }
    }

    // =====================================================================

    /**
     * 让一个 refreshToken 从此不存在：删它的 key，并把它从该账号的索引集合里摘掉。
     *
     * <p><b>抽成一处不是为了少写几行</b>，而是这对动作有三个调用方
     * （消费旋转 / 登出 / 改密踢其他会话），将来要改它时 —— 加审计日志、
     * 索引集合换结构 —— <b>只有一个地方要改</b>。漏改一处的表现是「索引集合里留着已失效的 hash」，
     * 不报错、也不影响功能，因此不会有人发现。
     *
     * <p>与 {@code TenantHelper.ignore()} 收敛到 1 处是同一个道理：
     * <b>会静默出错的动作，出现次数越少越好。</b>
     */
    private void forget(Long userId, String tokenHash) {
        redisTemplate.delete(RedisKeys.refreshToken(tokenHash));
        redisTemplate.opsForSet().remove(RedisKeys.refreshTokenUserIndex(userId), tokenHash);
    }

    // =====================================================================

    private String issueRefreshToken(Long userId, Long tenantId) {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hash(token);

        // 【平台超管的 tenantId 就是 0，这不是「用 0 代替 null」】
        //   sys_user.tenant_id 是 BIGINT NOT NULL DEFAULT 0，DDL 注释逐字写着「平台超管为 0」。
        //   同一个 0 在另外两处互相印证：契约 §2.1 平台根节点 org_node.tenant_id = 0；
        //   sys_user_role 的「平台超管绑定记录为 0」。
        //   所以超管刷新时按 tenant_id = 0 过滤查到的正是他自己那一行 —— 是设计，不是巧合。
        //
        //   下面的 `== null` 分支因此是<b>纯防御性</b>的：从库里读出来的 AuthUser 永远走不到它
        //   （列 NOT NULL）。留着是为了万一有人手工构造实体。
        //
        //   【将来若改成用 NULL 表示「不属于任何租户」的平台账号】——那要先改 DDL 的 NOT NULL，
        //   而且<b>这里与 LoginService#refresh 必须一起改</b>：refresh 会把这个值喂给
        //   runWithTenant，null 进去会直接抛 TenantContextMissingException，
        //   而 0 进去会变成「按租户 0 过滤」——两种都不是那时想要的语义。
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
