package com.edumatrix.common.tenant;

/**
 * 当前会话上下文的来源（SPI）。
 *
 * <p><b>它存在的理由是依赖方向</b>：租户插件与 {@link TenantHelper} 需要「当前会话的
 * tenantId / 是否超管 / userId」，而登录、Sa-Token Session 与 {@code LoginHelper}
 * 是<b>模块 02 的产出</b>（04-实施计划.md 模块 02「对外产出」表第 2 行）。模块 01 是公共层，
 * 不能反过来依赖模块 02。于是这里只定接口，模块 01 给一个「无会话时全部返回空」的默认实现，
 * 模块 02 用读 Sa-Token Session 的实现覆盖它。
 *
 * <p><b>为什么不直接在 TenantHelper 里调 {@code StpUtil.getSession().get("tenantId")}</b>：
 * 那个会话键名全套文档没有定义。用一个没人登记的魔法字符串把模块 01 和 02 耦在一起，
 * 就等于新造了一处约定 —— 而本项目的纪律是文档没定义的不发明。变成接口后，
 * 键名是模块 02 的内部细节，模块 01 不需要知道。
 *
 * <p><b>实现方注意</b>：本接口的三个方法都在<b>每请求鉴权路径</b>上被调用，必须是
 * 内存级操作（读会话对象），不得查库。
 */
public interface CurrentContextProvider {

    /**
     * 当前会话的租户 ID；无会话（定时任务、事件消费、异步 Worker、免登录接口）时返回 {@code null}。
     *
     * <p>返回 {@code null} <b>不代表可以放行</b> —— 取值优先级与兜底见
     * {@link TenantHelper#requireTenantId()}：四条路径全落空时抛异常并记 ERROR，绝不"猜一个"，
     * 也绝不退化为忽略租户条件（契约 §2.8 规则 3）。
     */
    Long getTenantId();

    /**
     * 当前会话是否为平台超管（{@code user_type = 0}）。
     *
     * <p>超管的跨租户访问靠<b>租户插件对超管整体放行</b>（契约 §2.9 / 02-数据库设计 §3.2）。
     * 这是与 {@code sys_role} / {@code sys_role_menu} 的 {@code OR tenant_id = 0}
     * <b>完全不同的第二条通道，两者不得混用</b>：前者放的是「超管能看所有租户的数据」，
     * 后者放的是「所有租户都能读到平台内置角色」。把超管的账号、手机号、登录轨迹、
     * 操作日志暴露给每个租户管理员，正是混用会导致的后果。
     */
    boolean isSuperAdmin();

    /**
     * 当前登录用户 ID；无会话时返回 {@code null}。
     *
     * <p>用于幂等键 {@code idem:{userId}:{requestId}}（00-通用约定 §7.2）与
     * 越权拒绝日志（契约 §7.1 要求带 {@code traceId + userId + 目标对象 ID}）。
     */
    Long getUserId();

    /**
     * 当前登录用户所在的组织树节点 ID（{@code sys_user.node_id}）；无会话时返回 {@code null}。
     *
     * <p>数据权限的唯一入参 —— 全系统只有一条规则：<b>你能看到的数据 = 你所在节点的子树</b>
     * （契约 §2.4）。{@code SubtreeScopeHelper} 靠它把「我」定位到树上。
     *
     * <p><b>只放 nodeId，不放 ancestors</b>：02-数据库设计 §3.2 说登录时把 {@code node_id}
     * 与 {@code ancestors} 一并放进会话，但契约 §2.3 同时写死了
     * <b>{@code ancestors} 绝不可写进 JWT</b> —— 它随节点移动而变，固化进 Token 就成了
     * 发证时刻的快照，任何移动都要等 Token 过期才生效。所以 ancestors 一律现取，
     * 走 {@code NodeAncestorCache}（Redis {@code node:anc:{nodeId}}，节点移动时失效）。
     */
    Long getNodeId();

    /**
     * 当前账号类型：{@code 1} 管理员 / {@code 2} 教师 / {@code 3} 学生（{@code sys_user.user_type}）。
     *
     * <p>模块 12 需要它来分叉「学生播放」与「管理端预览」两条路径，并写进
     * {@code vod_play_auth_log.viewer_type}。会话里本来就有这个值（{@code LoginHelper.SESSION_USER_TYPE}），
     * 从 SPI 取比每次回查 {@code sys_user} 少一次查询，也避免了在 {@code vod} 里 import {@code system} 领域。
     *
     * @return 无会话时 {@code null}
     */
    Integer getUserType();
}
