package com.edumatrix.org.node.service;

import org.springframework.stereotype.Service;

import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.edumatrix.org.node.mapper.OrgNodeChangeLogMapper;

/**
 * 异动轨迹写入器（04-实施计划.md 模块 06「对外产出」）。
 *
 * <h2>⚠ 必须在<b>调用方的事务内</b>调用</h2>
 * <p>本类<b>刻意不加 {@code @Transactional}</b>：加上去会让它在调用方没有事务时
 * 自己开一个新事务提交掉，于是「移动失败回滚了、轨迹却留下了」——
 * 而轨迹是只增不改不删的（PRD F1-9 规则 2），那条假记录<b>永远删不掉</b>。
 * 不加注解则它天然并入调用方事务：有事务就同生共死，没事务就是一条自动提交的 INSERT
 * （那种调用本身是错的，应当在评审时被发现，而不是被一个注解悄悄兜住）。
 *
 * <p>02-数据库设计 §3.1.3 把它列为移动事务的<b>步骤 7</b>，与前六步同事务。
 *
 * <h2>{@code tenant_id} 显式传入，不靠插件</h2>
 * <p>{@code org_node_change_log.tenant_id} 是 {@code BIGINT NOT NULL}<b>无默认值</b>。
 * 常规会话下插件会注入，但取值一律来自<b>被操作的那一行数据</b>（契约 §2.8 规则 1
 * 「从数据显式取」）而不是会话 —— 这与 {@code PlatformNodeWriter} 建档轨迹的做法一致。
 */
@Service
public class NodeChangeLogWriter {

    private final OrgNodeChangeLogMapper changeLogMapper;

    public NodeChangeLogWriter(OrgNodeChangeLogMapper changeLogMapper) {
        this.changeLogMapper = changeLogMapper;
    }

    /**
     * 写一条异动轨迹并返回它（{@code changeTime} 已读回填好）。
     *
     * @param nodeId       发生异动的节点。<b>教师调岗时是教师节点</b>，
     *                     其学员子树跟随移动但<b>不逐个记录</b>（DDL 列注释、PRD F1-4 规则 6）
     * @param changeType   见 {@link OrgNodeChangeLog} 的常量
     * @param fromParentId 原父节点；建档（1）时为 {@code null}
     * @param toParentId   新父节点；纯状态类异动（5/6/7）且树位置未变时为 {@code null}
     * @param reason       异动原因，最长 500 字符
     * @param tenantId     取自被操作的节点行，<b>不是会话</b>
     */
    public OrgNodeChangeLog write(Long nodeId, int changeType, Long fromParentId,
                                  Long toParentId, String reason, Long tenantId) {
        OrgNodeChangeLog log = new OrgNodeChangeLog();
        log.setNodeId(nodeId);
        log.setChangeType(changeType);
        log.setFromParentId(fromParentId);
        log.setToParentId(toParentId);
        log.setOperatorId(TenantHelper.getUserId());
        log.setReason(reason);
        log.setTenantId(tenantId);
        changeLogMapper.insert(log);

        // change_time 是 DDL 的 DEFAULT CURRENT_TIMESTAMP，INSERT 后实体里还是 null。
        // 【读回来而不是在 Java 侧算一个】：AuditFieldHandler 的类注释立过这条规矩 ——
        // 时间只认数据库这一个时钟。§3.4 响应里的 changeTime 要的就是落库的那个值，
        // 从应用服务器取会把两台机器的时钟差写进响应
        OrgNodeChangeLog persisted = changeLogMapper.selectById(log.getId());
        if (persisted != null) {
            log.setChangeTime(persisted.getChangeTime());
        }
        return log;
    }
}
