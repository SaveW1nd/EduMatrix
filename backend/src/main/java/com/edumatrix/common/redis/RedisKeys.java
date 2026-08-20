package com.edumatrix.common.redis;

/**
 * 全系统 Redis key 前缀的<b>唯一登记处</b>。
 *
 * <h2>为什么要有它</h2>
 * <p>模块 01 落地后，key 前缀已经分散在三处：{@code node:anc:}（{@code common/subtree}）、
 * {@code idem:}（{@code common/idempotent}）、{@code frozen:}（契约 §2.3，模块 02 才有实现）。
 * 模块 02 再加五组，就会出现「模块 09 / 13 / 16 各造各的」——而<b>撞名是不报错的</b>：
 * 两个模块用同一个前缀存不同结构的值，表现为偶发的反序列化失败或数据莫名被覆盖，
 * 排查时没有任何一处能看到全部 key 的清单。
 *
 * <h2>三类前缀，来源不同</h2>
 * <table border="1">
 *   <caption>登记表</caption>
 *   <tr><th>前缀</th><th>来源</th><th>能不能改</th></tr>
 *   <tr><td>{@link #FROZEN_SET_PREFIX}、{@link #NODE_ANCESTOR_PREFIX}、{@link #IDEMPOTENT_PREFIX}</td>
 *       <td>契约 §2.3 / 00-通用约定 §7.2 <b>逐字规定</b></td>
 *       <td><b>不能</b>。改它等于改契约</td></tr>
 *   <tr><td>{@link #CAPTCHA_PREFIX}</td>
 *       <td>03-01 §1.1 的响应示例 {@code "cap:9f8e7d6c5b4a3f2e1d0c"} 透出</td>
 *       <td>不改。示例即口径 —— {@code captchaKey} 原样回传，前端会看见它</td></tr>
 *   <tr><td>其余五组</td><td>文档未定义，模块 02 提出并经确认</td><td>可改，但要先改这里</td></tr>
 * </table>
 *
 * <p><b>新增 key 前必须先在本类登记</b>，与错误码「先在 §9 登记再使用」同源。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    // =====================================================================
    // 一、文档逐字规定的三组（不可改）
    // =====================================================================

    /**
     * 冻结集：{@code frozen:{tenantId}} → SET，存该租户当前被停用的节点 id（契约 §2.3 逐字规定）。
     *
     * <p>规模由<b>被停用的节点数</b>决定（通常个位数），不由受影响的人数决定 ——
     * 这正是它胜过「遍历子树踢 Token」的地方，同一业务效果代价差四个数量级。
     */
    public static final String FROZEN_SET_PREFIX = "frozen:";

    /** 祖先链缓存：{@code node:anc:{nodeId}} → STRING（契约 §2.3 逐字规定）。 */
    public static final String NODE_ANCESTOR_PREFIX = "node:anc:";

    /** 幂等键：{@code idem:{userId}:{requestId}}（00-通用约定 §7.2 逐字规定）。 */
    public static final String IDEMPOTENT_PREFIX = "idem:";

    // =====================================================================
    // 二、由接口响应示例透出的一组
    // =====================================================================

    /**
     * 图形验证码：{@code cap:{随机串}} → STRING，值为验证码原文，TTL 300s。
     *
     * <p>03-01 §1.1 的响应示例把它作为 {@code captchaKey} 直接回给前端
     * （{@code "captchaKey": "cap:9f8e7d6c5b4a3f2e1d0c"}），登录时原样带回 ——
     * <b>所以这个前缀是对外可见的，不是内部实现细节</b>。
     */
    public static final String CAPTCHA_PREFIX = "cap:";

    // =====================================================================
    // 三、模块 02 提出、经确认的五组
    // =====================================================================

    /**
     * refreshToken：{@code auth:refresh:{sha256(token)}} → STRING，TTL 7 天（00-通用约定 §2.1）。
     *
     * <p><b>存哈希而不是原文</b>：不是防 Redis 被攻破（那时已全线失守），
     * 是防运维在 {@code redis-cli} 里 {@code KEYS auth:refresh:*} 时把有效令牌看个遍。
     */
    public static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";

    /**
     * 某账号当前全部 refreshToken 的索引：{@code auth:refresh:uid:{userId}} → SET，
     * 成员是各 refreshToken 的哈希。
     *
     * <p>存在的唯一理由是 03-01 §1.6：改密后<b>除当前会话外</b>其余会话全部作废 ——
     * 没有这个索引就只能靠 {@code KEYS} 扫描，那在生产上是禁止的。
     */
    public static final String REFRESH_TOKEN_USER_INDEX_PREFIX = "auth:refresh:uid:";

    /** 连续密码错误计数：{@code auth:fail:{username}}，TTL 15 分钟（00-通用约定 §8）。 */
    public static final String LOGIN_FAIL_PREFIX = "auth:fail:";

    /**
     * 账号锁定标记：{@code auth:lock:{username}}，TTL 15 分钟（00-通用约定 §8）。
     *
     * <p>{@code 10005} 要在 {@code msg} 里提示剩余时间，该时间直接取本 key 的 TTL ——
     * 不另存一个「解锁时刻」，两份时间早晚会不一致。
     */
    public static final String LOGIN_LOCK_PREFIX = "auth:lock:";

    /** 登录 IP 限流：{@code rate:login:{ip}}，同一 IP 60 秒内最多 10 次（00-通用约定 §8）。 */
    public static final String RATE_LIMIT_LOGIN_PREFIX = "rate:login:";

    /** 验证码 IP 限流：{@code rate:captcha:{ip}}，同一 IP 60 秒内最多 20 次（00-通用约定 §8）。 */
    public static final String RATE_LIMIT_CAPTCHA_PREFIX = "rate:captcha:";

    /**
     * 文件上传频次闸：{@code rate:file:upload:{userId}}，同一用户 60 秒内最多 20 次（D-5 定案）。
     *
     * <p><b>按 userId 而不是 IP</b>：登录与验证码是<b>未登录</b>接口，只能按 IP；
     * 上传接口一定有会话，按 userId 更准 —— 校园网整栋楼一个出口 IP，
     * 按 IP 限流会让一个人刷爆导致全班传不了作业。
     *
     * <p>本项是 00-通用约定 §8 限流表在模块 05 新增的一行（D-5 定案）。
     */
    public static final String RATE_LIMIT_FILE_UPLOAD_PREFIX = "rate:file:upload:";

    /**
     * 授权健康度巡检<b>最近一轮完成时刻</b>（按租户），值是 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p>03-02 §9.6 的 {@code detectedTime}。需方定案 F-83：巡检结果<b>不落快照</b>，
     * 接口与 Job 调<b>同一个 Mapper 方法</b>实时算 —— 快照方案有一整类
     * 「快照丢了 → 页面显示 0 条悬挂 → 与真健康无法区分」的静默故障，
     * 而这个指标的告警线恰好是 {@code > 0}。
     *
     * <p>于是 Redis 里只存<b>一个时间戳</b>：它丢了，{@code detectedTime} 回 {@code null}，
     * <b>清单本身照常是准的</b> —— 丢的是「上次什么时候跑的」，不是「有没有问题」。
     */
    public static final String GRANT_HEALTH_LAST_RUN_PREFIX = "grant:health:lastrun:";

    // =====================================================================
    // 拼接助手 —— 让「前缀 + 变量」这件事也只有一种写法
    // =====================================================================

    public static String frozenSet(Long tenantId) {
        return FROZEN_SET_PREFIX + tenantId;
    }

    public static String nodeAncestors(Long nodeId) {
        return NODE_ANCESTOR_PREFIX + nodeId;
    }

    public static String captcha(String captchaId) {
        return CAPTCHA_PREFIX + captchaId;
    }

    public static String refreshToken(String tokenHash) {
        return REFRESH_TOKEN_PREFIX + tokenHash;
    }

    public static String refreshTokenUserIndex(Long userId) {
        return REFRESH_TOKEN_USER_INDEX_PREFIX + userId;
    }

    public static String loginFail(String username) {
        return LOGIN_FAIL_PREFIX + username;
    }

    public static String loginLock(String username) {
        return LOGIN_LOCK_PREFIX + username;
    }

    /** {@link #GRANT_HEALTH_LAST_RUN_PREFIX} + 租户 ID。 */
    public static String grantHealthLastRun(Long tenantId) {
        return GRANT_HEALTH_LAST_RUN_PREFIX + tenantId;
    }

    public static String rateLimitLogin(String ip) {
        return RATE_LIMIT_LOGIN_PREFIX + ip;
    }

    public static String rateLimitCaptcha(String ip) {
        return RATE_LIMIT_CAPTCHA_PREFIX + ip;
    }

    public static String rateLimitFileUpload(Long userId) {
        return RATE_LIMIT_FILE_UPLOAD_PREFIX + userId;
    }
}
