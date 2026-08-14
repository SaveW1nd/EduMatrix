package com.edumatrix.auth.session;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;

/**
 * Sa-Token 会话的读写门面（04 §B 模块 02「对外产出」第 2 行）。
 *
 * <h2>会话里放什么 —— 四项，一项不多</h2>
 * <pre>
 * loginId      = userId          （Sa-Token 自带）
 * userType     0 超管 / 1 管理员 / 2 教师 / 3 学生
 * nodeId       数据权限的唯一锚点（契约 §2.4）
 * tenantId     租户硬边界（契约 §2.1）
 * pwdResetFlag 强制改密门禁（PRD F1-1 规则 6）
 * </pre>
 *
 * <h2>{@code ancestors} 绝不进会话，更不进 Token</h2>
 * <p>契约 §2.3 写死。它随节点移动而变，固化进 Token 就成了发证时刻的快照 ——
 * 任何一次移动都要等 Token 过期（最长 2 小时）才生效，而契约 §2.4 承诺的是
 * <b>「学员被移走后，原上级<u>立即</u>失去访问权」</b>。
 * 祖先链一律现取，走 {@code NodeAncestorCache}（Redis {@code node:anc:{nodeId}}，
 * 节点移动时失效）。
 *
 * <p>{@code pwdResetFlag} 放进会话是安全的，理由与 {@code ancestors} 相反：
 * 它只由<b>本人改密</b>（03-01 §1.6）与<b>管理员重置密码</b>（03-01 §2.5，模块 03）改变，
 * 而后者按 PRD §7.3「改密即时失效对应 Token」本就要作废该账号的会话 —— 会话没了，
 * 快照也就不存在了。
 *
 * <h2>所有取值方法都必须在「无会话」时安全返回 {@code null}</h2>
 * <p>它们被 {@link SaTokenCurrentContextProvider} 调用，而那个 SPI 会在
 * <b>免登录接口、定时任务、事件消费</b>等没有 Sa-Token 上下文的地方被触到。
 * 那些场景下 {@code StpUtil} 会抛异常而不是返回 null，所以这里一律兜住。
 */
public final class LoginHelper {

    /** 会话键：用户类型。键名是模块 02 的内部细节，模块 01 通过 SPI 取值而不认识它。 */
    public static final String SESSION_USER_TYPE = "userType";
    /** 会话键：所在组织树节点。 */
    public static final String SESSION_NODE_ID = "nodeId";
    /** 会话键：租户。 */
    public static final String SESSION_TENANT_ID = "tenantId";
    /** 会话键：是否须强制改密。 */
    public static final String SESSION_PWD_RESET_FLAG = "pwdResetFlag";

    /**
     * <b>Token 会话</b>键：本次会话绑定的 refreshToken 哈希。
     *
     * <p>放在 Token 会话（一 Token 一份）而不是账号会话（一账号一份）：
     * 03-01 §1.4 要求登出只作废<b>当前会话</b>的两个 Token，
     * 账号会话是多端共享的，放那里会把别的设备的 refreshToken 一起删掉。
     */
    public static final String TOKEN_SESSION_REFRESH_HASH = "refreshHash";

    private LoginHelper() {
    }

    // =====================================================================
    // 写
    // =====================================================================

    /** 登录成功后写入会话四项。 */
    public static void writeSession(Long userId, Integer userType, Long nodeId,
                                    Long tenantId, boolean needChangePassword) {
        SaSession session = StpUtil.getSessionByLoginId(userId);
        session.set(SESSION_USER_TYPE, userType);
        session.set(SESSION_NODE_ID, nodeId);
        session.set(SESSION_TENANT_ID, tenantId);
        session.set(SESSION_PWD_RESET_FLAG, needChangePassword ? 1 : 0);
    }

    /** 改密成功后清除强制改密标记，使本次会话立刻能访问其他接口。 */
    public static void clearPwdResetFlag(Long userId) {
        StpUtil.getSessionByLoginId(userId).set(SESSION_PWD_RESET_FLAG, 0);
    }

    // =====================================================================
    // 读（无会话时一律 null / false）
    // =====================================================================

    public static Long getUserId() {
        return safely(() -> {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId == null ? null : Long.valueOf(loginId.toString());
        });
    }

    public static Integer getUserType() {
        return safely(() -> toInteger(attribute(SESSION_USER_TYPE)));
    }

    public static Long getNodeId() {
        return safely(() -> toLong(attribute(SESSION_NODE_ID)));
    }

    public static Long getTenantId() {
        return safely(() -> toLong(attribute(SESSION_TENANT_ID)));
    }

    /** 平台超管（{@code user_type = 0}）—— 租户插件对其整体放行（契约 §2.9）。 */
    public static boolean isSuperAdmin() {
        Integer userType = getUserType();
        return userType != null && userType == 0;
    }

    /** {@code pwd_reset_flag = 1}：未改密前不得访问其他接口（PRD F1-1 规则 6）。 */
    public static boolean needChangePassword() {
        Integer flag = safely(() -> toInteger(attribute(SESSION_PWD_RESET_FLAG)));
        return flag != null && flag == 1;
    }

    // =====================================================================

    private static Object attribute(String key) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        return session == null ? null : session.get(key);
    }

    /**
     * 兜住「没有 Sa-Token 上下文」的场景。
     *
     * <p>定时任务 / 事件消费 / 异步 Worker 里调 {@code StpUtil} 会抛
     * {@code SaTokenContextException}，而 {@link SaTokenCurrentContextProvider}
     * 的契约是「无会话时返回 null」（{@code CurrentContextProvider} 类注释）。
     * 让异常穿出去会把一个「本来就没有会话」的正常情况变成 500。
     */
    private static <T> T safely(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.longValue() : Long.valueOf(value.toString());
    }

    private static Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.intValue() : Integer.valueOf(value.toString());
    }
}
