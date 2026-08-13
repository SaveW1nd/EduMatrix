package com.edumatrix.common.entity;

import org.apache.ibatis.reflection.MetaObject;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 署名字段自动填充：插入填 {@code create_by} + {@code update_by}，更新填 {@code update_by}。
 *
 * <p>取值走 {@code TenantHelper.getUserId()}，它最终来自
 * {@code CurrentContextProvider}（模块 02 实现）。放在公共层是因为
 * <b>它依赖的接口就是模块 01 定的</b> —— 推给第一个写实体的模块，那时面对同一个选择
 * 而工单里没有依据可引。
 *
 * <h2>取不到操作人时填 {@code null}，绝不填 0</h2>
 * <p>契约 §2.2 把 {@code create_by} / {@code update_by} 定为 {@code BIGINT NULL}。
 * 填 {@code 0} 会造出一条「用户 0 创建的」假记录 —— 而 {@code 0} 在本系统里<b>不是空值，
 * 是平台根节点的 ID</b>（{@code org_node.id = 0} 的哨兵行），所以这条假记录还会
 * 指向一个真实存在的东西。
 *
 * <p>这正是契约 §2.2 末尾那条<b>同源原则</b>的又一个实例：
 * <b>不要让「没发生」和「发生过又被抹掉」落在同一个取值上。</b>
 * 逻辑删除用毫秒时间戳而不是 0/1 是它，删除请求脱敏用掩码而不是 NULL 是它，
 * 这里也是 —— 无会话入口（定时任务、事件消费、异步 Worker）<b>本来就没有操作人</b>，
 * {@code null} 是它的真实状态，不是"缺了个值"。
 *
 * <h2>{@code create_time} / {@code update_time} 【不填】</h2>
 * <p>DDL 已经是 {@code DEFAULT CURRENT_TIMESTAMP} 与
 * {@code ON UPDATE CURRENT_TIMESTAMP}，由数据库赋值。两侧都填会把<b>应用服务器时钟
 * 与数据库时钟的差异静默写进数据</b> —— 而契约 §6「服务器、数据库、接口三层都在东八区」
 * 防的正是这件事。更具体地说，这个差异会直接落到几处按时间判定的口径上：
 * {@code stat_*} 的自然日结算边界、作业 {@code deadline} 的逾期判定、
 * {@code vod_heartbeat_log} 的按月分区边界。让它们全部只认数据库这一个时钟，
 * 就少一个会漂的量。
 *
 * <p>反过来说，如果将来要在 Java 侧填时间，前提是先把「以哪个时钟为准」写进契约，
 * 而不是因为"顺手"。
 */
public class AuditFieldHandler implements MetaObjectHandler {

    /** {@link BaseEntity} 里的字段名（驼峰），与列名 {@code create_by} / {@code update_by} 对应。 */
    private static final String CREATE_BY = "createBy";
    private static final String UPDATE_BY = "updateBy";

    @Override
    public void insertFill(MetaObject metaObject) {
        Long userId = currentUserId();
        if (userId == null) {
            // 无会话入口没有操作人 —— 保持 null，不要用 strictInsertFill 填 0
            return;
        }
        strictInsertFill(metaObject, CREATE_BY, Long.class, userId);
        strictInsertFill(metaObject, UPDATE_BY, Long.class, userId);
    }

    /**
     * 更新时<b>无条件覆盖</b> {@code update_by}，故意不用 {@code strictUpdateFill}。
     *
     * <p>MyBatis-Plus 的 {@code strictUpdateFill} 走 {@code strictFillStrategy}，
     * 它<b>只在字段当前为 null 时才填</b>。而更新的常见形态是「从库里读出实体 → 改几个字段
     * → {@code updateById}」，此时 {@code updateBy} 上带着<b>上一次修改人</b>的值，
     * 非 null，于是这次的操作人被静默丢弃 —— 记录上写着"最后修改人是别人"。
     *
     * <p>这是一条<b>假审计记录</b>，而 {@code sys_oper_log} 与本列是排查越权、
     * 对账责任的两条线索之一。宁可覆盖，也不要留一个看起来完整、内容却是错的署名。
     *
     * <p>{@code create_by} 不适用本条：它的语义是"首次署名，此后不变"，
     * 所以插入侧仍用 {@code strictInsertFill}（只在为 null 时填），
     * 显式指定创建人的场景（数据迁移、代建账号）也因此得以保留。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        Long userId = currentUserId();
        if (userId == null) {
            // 无会话入口没有操作人。这里【不清空】已有值：
            // 抹掉一个真实存在过的署名，比留着它更糟
            return;
        }
        setFieldValByName(UPDATE_BY, userId, metaObject);
    }

    /**
     * 当前操作人。
     *
     * <p>包一层是为了让「取不到就是 null」这件事只写一处 ——
     * {@code TenantHelper.getUserId()} 在无会话时返回 {@code null}，
     * 这里<b>不做任何兜底转换</b>。
     */
    private static Long currentUserId() {
        return TenantHelper.getUserId();
    }
}
