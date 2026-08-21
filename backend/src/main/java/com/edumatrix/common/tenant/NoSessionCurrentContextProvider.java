package com.edumatrix.common.tenant;

/**
 * {@link CurrentContextProvider} 的默认实现：<b>当作没有会话</b>。
 *
 * <p>模块 01 里还没有登录，本实现让「路径④ 会话」恒定落空，从而把租户上下文完全交给
 * 路径①（{@link TenantHelper#runWithTenant}）与路径②（{@link TenantHelper#ignore}）。
 *
 * <p><b>模块 02 必须用读 Sa-Token Session 的实现覆盖它</b>：注册一个 {@link CurrentContextProvider}
 * 类型的 Bean 即可 —— {@code TenantConfig} 里对本默认实现标了 {@code @ConditionalOnMissingBean}，
 * 有了真实现自动让位。
 *
 * <p><b>它返回 null 不等于放行。</b>四条路径全落空时 {@link TenantHelper#requireTenantId()}
 * 抛异常并记 ERROR（契约 §2.8 规则 3：无法确定租户的写入一律拒绝并告警，绝不"猜一个"
 * 或退化为忽略租户条件）。退化的后果是全库裸奔，比零权限严重得多 ——
 * <b>零权限看得见，跨租户泄漏看不见。</b>
 */
public class NoSessionCurrentContextProvider implements CurrentContextProvider {

    @Override
    public Long getTenantId() {
        return null;
    }

    @Override
    public boolean isSuperAdmin() {
        return false;
    }

    @Override
    public Long getUserId() {
        return null;
    }

    @Override
    public Long getNodeId() {
        return null;
    }

    @Override
    public Integer getUserType() {
        return null;
    }
}
