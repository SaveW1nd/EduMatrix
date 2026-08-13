package com.edumatrix.common.tenant;

/**
 * 租户上下文缺失：{@link TenantHelper} 的四条取值路径全部落空。
 *
 * <p>抛出它意味着有一处写入或查询<b>不知道自己属于哪个租户</b>。契约 §2.8 规则 3 写死了此时的处置：
 * <b>一律拒绝并告警，绝不"猜一个"或退化为忽略租户条件。</b>
 *
 * <p>为什么不能退化：忽略租户条件就是全库裸奔。它比契约 §2.9 那个「系统开箱即不可用」的
 * 零权限故障严重得多 —— <b>零权限看得见</b>（按钮全隐、接口 403，用户当天就会报障），
 * <b>跨租户泄漏看不见</b>（接口 200、数据更多，没有任何人会觉得不对）。
 *
 * <p>见到它的正确反应是找出那个入口漏了 {@link TenantHelper#runWithTenant} 包裹，
 * 而不是给它加一个默认租户。
 */
public class TenantContextMissingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public TenantContextMissingException(String message) {
        super(message);
    }
}
