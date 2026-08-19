package com.edumatrix.common.subtree;

/**
 * 「我在树上的哪个节点」—— 数据权限的唯一入参（契约 §2.4）。
 *
 * <p><b>接口在 {@code common/}、实现在 {@code org/}、消费方按接口注入</b> ——
 * 与 {@code common/account/PasswordHasher} + {@code auth/session/AuthAccountProvider}
 * 同型。模块 08（{@code course} 领域）需要它，而唯一的实现
 * {@code org/node/service/CurrentNodeResolver} 在 {@code org} 领域里，
 * 直接注入会命中 {@code scripts/check_backend_conventions.sh} 的检查③。
 *
 * <h2>为什么不直接用 {@code CurrentContextProvider#getNodeId()}</h2>
 * <p>那条路在<b>集成测试里是假的</b>：模块 01 的 {@code TestSupportConfiguration}
 * 注册了 {@code @Primary} 的测试 provider，于是注入进来的 Bean 恒是它；
 * 而验真实登录的 IT 只把 {@code TenantHelper} 的<b>静态门面</b>切到 Sa-Token。
 * 实现方 {@code CurrentNodeResolver} 走的是「静态门面取 {@code userId} →
 * 反查 {@code sys_user.node_id}」，两条路在生产等价、在 IT 里只有它是真的。
 * 完整论证见 {@code CurrentNodeResolver} 的类注释。
 *
 * <p><b>本接口只声明能力，不承载实现</b>：新增第二个实现即为缺陷 ——
 * 「我在哪个节点」是全系统唯一口径，两份实现迟早写歧。
 */
public interface CurrentNodeProvider {

    /** 当前登录人所在的节点；无会话或账号已删除时返回 {@code null}。 */
    Long currentNodeId();

    /**
     * 同上，取不到时抛 {@code 400}。
     *
     * <p><b>绝不退化为「不加过滤」</b>：契约 §7.1 把「数据权限过滤条件为空集」定为 ERROR 级
     * —— 那意味着过滤逻辑写漏了，正在返回全量数据。
     */
    Long requireCurrentNodeId();
}
