package com.edumatrix.org.node.service;

import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.CurrentContextProvider;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.mapper.NodeAccountMapper;

/**
 * 「我在树上的哪个节点」——数据权限的唯一入参（契约 §2.4）。
 *
 * <h2>为什么走 {@link TenantHelper} 静态门面 + {@code sys_user} 反查，
 * 而不是注入 {@link CurrentContextProvider} Bean</h2>
 * <p>与 {@code SysUserService#currentNodeId} <b>同法</b>，不是另起一套。理由：
 * <ul>
 *   <li><b>生产里两条路等价</b>：模块 02 注册了 {@code SaTokenCurrentContextProvider}，
 *       {@code TenantConfig} 的默认实现标了 {@code @ConditionalOnMissingBean} 会让位，
 *       静态门面与 Bean 指向同一个对象；
 *   <li><b>集成测试里只有静态门面是真的</b>：模块 01 的 {@code TestSupportConfiguration}
 *       注册了 {@code @Primary} 的 {@code TestCurrentContextProvider}（它要在没有登录接口时
 *       也能模拟会话），于是<b>注入进来的 Bean 恒是那个测试实现</b>；
 *       而验真实会话的 IT 是把<b>静态门面</b>切到 Sa-Token 那个
 *       （{@code AuthIntegrationTestBase} 的做法，它的类注释解释了为什么不能新建上下文）。
 * </ul>
 * <p>注入 Bean 的写法在生产上跑得通、在 IT 里<b>恒取到空会话</b> ——
 * 而那种测试要么全红（本模块第一次跑就是），要么更糟：<b>假装通过</b>。
 *
 * <p>多一次 {@code sys_user} 点查是这条路的代价，走主键，可接受。
 */
@Service
public class CurrentNodeResolver {

    private final NodeAccountMapper accountMapper;

    public CurrentNodeResolver(NodeAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    /** 当前登录人所在的节点；无会话或账号已删除时返回 {@code null}。 */
    public Long currentNodeId() {
        Long userId = TenantHelper.getUserId();
        return userId == null ? null : accountMapper.selectNodeIdByUserId(userId);
    }

    /**
     * 同上，取不到时抛 {@code 400}。
     *
     * <p><b>绝不退化为「不加过滤」</b>：契约 §7.1 把「数据权限过滤条件为空集」定为 ERROR 级
     * ——「那意味着过滤逻辑写漏了，正在返回全量数据」。取不到「我在哪」时，
     * 正确的处置是拒绝这次请求，而不是当作没有限制。
     */
    public Long requireCurrentNodeId() {
        Long nodeId = currentNodeId();
        if (nodeId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前会话没有节点，无法判定数据权限");
        }
        return nodeId;
    }
}
