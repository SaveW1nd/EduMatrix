package com.edumatrix.auth.session;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.frozen.FrozenNodeCache;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;

import cn.dev33.satoken.context.SaHolder;

/**
 * 每请求鉴权的两段业务校验，由 {@code common/config/SaTokenConfig} 的
 * {@code SaInterceptor} 在 {@code checkLogin()} 之后调用。
 *
 * <p><b>加进那个拦截器而不是另起一套</b>：05-工程结构.md §G1 定死了鉴权入口的位置，
 * {@code SaTokenConfig} 的类注释也预告了「冻结集校验归模块 02，它会往这里的拦截器里加一段」。
 * 在 Sa-Token 之外再建一个鉴权入口，就会出现两个说了算的地方。
 *
 * <h2>① 冻结集校验 —— 停用对已在线的人立即生效</h2>
 * <p>只堵登录是不够的：已下发的 {@code accessToken} 还有最长 2 小时有效期，
 * 而分支冻结的业务意图（分部关停、欠费停服、合规冻结）不接受这个延迟（契约 §2.3）。
 * 判定规则与代价分析见 {@link FrozenNodeCache}，本类只负责把三个输入凑齐：
 * <pre>
 * tenantId ← 会话        nodeId ← 会话        ancestors ← NodeAncestorCache 现取
 * </pre>
 * <b>祖先链现取是硬约束</b>（契约 §2.3）：它绝不可来自 Token，否则节点移动要等 Token 过期才生效。
 *
 * <h2>② 强制改密门禁</h2>
 * <p>PRD F1-1 规则 6 / 03-01 §1.6：{@code pwd_reset_flag = 1} 的账号，
 * 除改密、登出、取本人信息三条外，访问其他业务接口一律 403。
 * 放行清单<b>只有三条</b>，与 03-01 §1.6 的原文逐条对应 —— 多放一条就是让未改密的账号
 * 摸到业务数据，而那正是「初始密码统一、尚未改过」的那批账号。
 */
@Component
public class AuthSessionGuard {

    /**
     * 强制改密期间仍可访问的接口（03-01 §1.6 原文：本接口及 1.4 登出、1.5 获取本人信息）。
     */
    private static final Set<String> PWD_RESET_ALLOWED_PATHS = Set.of(
            "/api/v1/auth/password",
            "/api/v1/auth/logout",
            "/api/v1/auth/me");

    private final FrozenNodeCache frozenNodeCache;
    private final NodeAncestorCache nodeAncestorCache;

    public AuthSessionGuard(FrozenNodeCache frozenNodeCache, NodeAncestorCache nodeAncestorCache) {
        this.frozenNodeCache = frozenNodeCache;
        this.nodeAncestorCache = nodeAncestorCache;
    }

    /** 拦截器入口：{@code checkLogin()} 通过之后跑这两段。 */
    public void check() {
        assertNotFrozen();
        assertPasswordChangedIfRequired();
    }

    /**
     * 本人所在节点或其上级被停用 → {@code 10017}。
     *
     * <p><b>超管直接放行</b>：他不属于任何租户，没有哪个机构管理员能冻结他；
     * 冻结集本身也是按租户分片的（{@code frozen:{tenantId}}）。
     */
    private void assertNotFrozen() {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        Long tenantId = LoginHelper.getTenantId();
        Long nodeId = LoginHelper.getNodeId();
        if (tenantId == null || nodeId == null) {
            return;
        }
        // 祖先链现取（契约 §2.3）；Redis 读不到时 NodeAncestorCache 自己会回源查库
        List<Long> ancestorIds = NodePath.parseAncestorIds(nodeAncestorCache.get(nodeId));
        if (frozenNodeCache.isFrozen(tenantId, nodeId, ancestorIds)) {
            throw new BizException(ErrorCode.NODE_OR_ANCESTOR_DISABLED);
        }
    }

    /** {@code pwd_reset_flag = 1} 且访问的不是三条放行接口 → 403。 */
    private void assertPasswordChangedIfRequired() {
        if (!LoginHelper.needChangePassword()) {
            return;
        }
        String path = SaHolder.getRequest().getRequestPath();
        if (path != null && PWD_RESET_ALLOWED_PATHS.contains(path)) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN, "请先修改初始密码后再访问其他功能");
    }
}
