package com.edumatrix.auth.session;

import org.springframework.stereotype.Component;

import com.edumatrix.common.tenant.CurrentContextProvider;

/**
 * {@link CurrentContextProvider} 的会话实现 —— 模块 02 对模块 01 那个 SPI 的兑现。
 *
 * <p>注册成 Bean 即可：{@code common/config/TenantConfig} 的默认实现标了
 * {@code @ConditionalOnMissingBean}，本 Bean 一在，那个「无会话时全部返回空」的默认实现
 * <b>自动让位</b>，模块 01 一个字不用改。这是设计上就留好的接缝。
 *
 * <h2>为什么模块 01 要定这个接口而不是直接读 Session</h2>
 * <p>依赖方向：租户插件与 {@code TenantHelper} 需要「当前会话的 tenantId / 是否超管 /
 * userId / nodeId」，而登录与 Sa-Token Session 是模块 02 的产出。公共层不能反过来依赖模块 02。
 * 会话键名（{@code userType} / {@code nodeId} / …）因此是模块 02 的内部细节，
 * 全部关在 {@link LoginHelper} 里，模块 01 不需要知道。
 *
 * <h2>四个方法都在每请求鉴权路径上，必须是内存级操作</h2>
 * <p>{@code CurrentContextProvider} 类注释的原话。这里全部走 Sa-Token Session 的读取，
 * <b>没有一处查库</b> —— 特别是 {@link #getNodeId()}：它只回会话里的那一个 id，
 * 祖先链是<b>用它去 {@code NodeAncestorCache} 现取</b>的，不在这里展开。
 */
@Component
public class SaTokenCurrentContextProvider implements CurrentContextProvider {

    @Override
    public Long getTenantId() {
        return LoginHelper.getTenantId();
    }

    @Override
    public boolean isSuperAdmin() {
        return LoginHelper.isSuperAdmin();
    }

    @Override
    public Long getUserId() {
        return LoginHelper.getUserId();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>只放 nodeId，不放 ancestors</b> —— 接口注释里那条硬约束（契约 §2.3）：
     * {@code ancestors} 随节点移动而变，写进 Token 就固化成发证时刻的快照，
     * 任何移动都要等 Token 过期才生效。
     */
    @Override
    public Long getNodeId() {
        return LoginHelper.getNodeId();
    }
}
