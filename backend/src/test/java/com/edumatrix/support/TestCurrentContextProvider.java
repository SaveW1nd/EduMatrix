package com.edumatrix.support;

import com.edumatrix.common.tenant.CurrentContextProvider;

/**
 * 测试用的会话上下文来源：可在测试里随意切换「当前是谁」。
 *
 * <p><b>为什么模块 01 要自带一个</b>：契约 §2.9 的原始验收要求用<b>租户 A 的非超管账号</b>
 * 证明两件事同时成立 —— 读得到 {@code tenant_id = 0} 的内置角色行、读不到租户 B 的自建角色行。
 * 这个验收必须在模块 01 内就能跑，不能等模块 02 做完登录。
 *
 * <p><b>为什么不用 {@code TenantHelper.runWithTenant} 代替</b>：{@code runWithTenant}
 * 表达的是「<b>无会话</b>入口显式携带租户」（Job / 事件消费 / Worker，契约 §2.8），
 * 而这条验收要的是「一个<b>普通用户会话</b>」。语义不同：会话路径上还带着
 * {@code isSuperAdmin} 与 {@code nodeId}，而那正是超管整体放行与数据权限的输入。
 * 用错了会验出一个"看起来通过、但走的不是同一条路"的结果。
 */
public class TestCurrentContextProvider implements CurrentContextProvider {

    private Long tenantId;
    private boolean superAdmin;
    private Long userId;
    private Long nodeId;
    private Integer userType;

    /** 模拟一个普通（非超管）用户会话。 */
    public TestCurrentContextProvider asTenantUser(Long tenantId, Long userId, Long nodeId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.nodeId = nodeId;
        this.superAdmin = false;
        return this;
    }

    /** 模拟平台超管会话（跨租户，靠租户插件整体放行）。 */
    public TestCurrentContextProvider asSuperAdmin(Long userId, Long nodeId) {
        this.tenantId = null;
        this.userId = userId;
        this.nodeId = nodeId;
        this.superAdmin = true;
        return this;
    }

    /** 回到「无会话」状态。 */
    public TestCurrentContextProvider asNoSession() {
        this.tenantId = null;
        this.userId = null;
        this.nodeId = null;
        this.superAdmin = false;
        return this;
    }

    @Override
    public Long getTenantId() {
        return tenantId;
    }

    @Override
    public boolean isSuperAdmin() {
        return superAdmin;
    }

    @Override
    public Long getUserId() {
        return userId;
    }

    @Override
    public Long getNodeId() {
        return nodeId;
    }

    /** 模块 12 起需要它分叉「学生播放」与「管理端预览」，并写进 vod_play_auth_log.viewer_type。 */
    public TestCurrentContextProvider withUserType(Integer userType) {
        this.userType = userType;
        return this;
    }

    @Override
    public Integer getUserType() {
        return userType;
    }
}
