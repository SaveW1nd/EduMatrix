package com.edumatrix.org.node.service;

import org.springframework.stereotype.Service;

import com.edumatrix.common.operlog.OperLogWriter;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * F-114 定案三：操作人<b>明确选择「不回收跨管辖授权」</b>时，把这个决定单独记一行。
 *
 * <h2>为什么不能靠 {@code @OperLog} 切面那一行</h2>
 * <p>切面记的是「谁在什么时候调了移动节点这个接口」，{@code action} 是
 * {@code 移动节点}，请求参数进 {@code params}。它<b>确实</b>包含
 * {@code revokeOutOfScopeGrants=false} 这几个字，但：
 * <ul>
 *   <li>那是一次<b>接口调用</b>的记录，而这里要留的是一次<b>决定</b>；</li>
 *   <li>{@code sys_oper_log} 的 DDL 表注释逐字写着「可按时间归档清理」——
 *       将来按 {@code action} 归档时，「移动节点」是最先被清掉的那一类；</li>
 *   <li>翻查时要问的是「哪些跨管辖授权是有人明确要留的」，
 *       那需要按 {@code action} 一条 SQL 查出来，而不是去 {@code params} 里 grep。</li>
 * </ul>
 * <p>与 {@code org/member/service/MemberOperLogWriter} 的「合规留痕 vs 操作日志」
 * 是同一条分工，理由也同源。
 *
 * <h2>为什么用 {@code write} 而不是 {@code writeOrThrow}</h2>
 * <p>与 {@code MemberOperLogWriter} <b>相反</b>，这里<b>不要求同生共死</b>：
 * 监护人同意留痕是法定证据，丢了就拿不出东西；而这一条是<b>运维可追溯性</b>——
 * 授权保留这件事本身在 {@code org_resource_grant} 里有据可查，
 * 响应里也回传了。为它把一次已经成功的树结构移动整个回滚，代价与收益不成比例。
 * <b>这个取舍是有意的，不是照抄漏了。</b>
 */
@Service
public class NodeMoveOperLogWriter {

    /** {@code sys_oper_log.module}，与菜单「组织树管理」同名。 */
    public static final String MODULE = "组织树管理";

    /**
     * {@code sys_oper_log.action}。
     *
     * <p><b>与切面写的 {@code 移动节点} 是两个不同的 action，不要合并</b>——
     * 合并之后就没办法只查「有人明确选了保留」的那些行了。
     */
    public static final String ACTION_KEEP_OUT_OF_SCOPE_GRANTS = "保留跨管辖授权";

    private final OperLogWriter operLogWriter;

    public NodeMoveOperLogWriter(OperLogWriter operLogWriter) {
        this.operLogWriter = operLogWriter;
    }

    /**
     * 记一行「{@code 操作人} 在移动 {@code movingNodeId} 时明确选择保留 {@code count} 条跨管辖授权」。
     *
     * <p>{@code oper_time} 由 DDL 的 {@code DEFAULT CURRENT_TIMESTAMP} 赋值 ——
     * <b>只认数据库这一个时钟</b>（与异动轨迹 {@code change_time} 同口径）。
     *
     * @param count 本次保留的条数；<b>0 也要记</b> —— 「当时确实没有跨管辖授权」
     *              与「没人做过这个决定」是两件事，而事后只有这行日志能把它们分开
     */
    public void keptOutOfScopeGrants(Long movingNodeId, int count, Long tenantId) {
        operLogWriter.write(TenantHelper.getUserId(),
                MODULE, ACTION_KEEP_OUT_OF_SCOPE_GRANTS,
                "PUT /api/v1/org/nodes/" + movingNodeId + "/move#revokeOutOfScopeGrants=false",
                // params 留 null：本接口请求体里只有 toParentId / reason，
                // 没有敏感字段，但脱敏白名单归模块 05 管，本类一个字都不往里写
                null, null, OperLogWriter.STATUS_SUCCESS, null, 0, tenantId);
    }
}
