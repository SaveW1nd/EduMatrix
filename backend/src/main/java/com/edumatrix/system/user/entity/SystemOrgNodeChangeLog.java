package com.edumatrix.system.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code org_node_change_log} 的窄写模型，<b>只服务 03-01 §2.2 的建档轨迹</b>。
 *
 * <h2>⚠ 临时构件，与 {@link SystemOrgNode} 同批交接给模块 06</h2>
 * <p>处置与交接方式逐条见 {@link SystemOrgNode} 的类注释。
 *
 * <h2>本模块只写 {@code change_type = 1}，一处</h2>
 * <p>§2.2 的副作用逐字是「同时记录一条 {@code org_node_change_log}
 * （{@code change_type=1} 建档）」。<b>§2.4 删除用户不写轨迹</b> ——
 * 它的原文只有「同时逻辑删除其 {@code org_node} 节点并作废该用户在线 Token」，
 * 03-02 的三个删除接口（接口 10/14/19）同样只写节点逻辑删除、不写轨迹。
 * <b>不要在删除路径上补一条，那是发明规则。</b>
 *
 * <p>其余 7 种 {@code change_type}（2 分配导师 / 3 转交管理员 / 4 教师调岗 /
 * 5 毕业归档 / 6 归档恢复 / 7 退课 / 8 节点移动）全部归模块 06/07，本模块一条不写。
 */
@TableName("org_node_change_log")
public class SystemOrgNodeChangeLog extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 异动类型 1：建档（契约 §5 change_type）。本模块唯一会写的值。 */
    public static final int CHANGE_TYPE_CREATE = 1;

    private Long nodeId;

    private Integer changeType;

    /** 原父节点。{@code change_type=1} 建档时为 {@code null}（DDL 注释逐字如此）。 */
    private Long fromParentId;

    private Long toParentId;

    /** 操作人 {@code user_id}。 */
    private Long operatorId;

    private String reason;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }

    public Long getFromParentId() {
        return fromParentId;
    }

    public void setFromParentId(Long fromParentId) {
        this.fromParentId = fromParentId;
    }

    public Long getToParentId() {
        return toParentId;
    }

    public void setToParentId(Long toParentId) {
        this.toParentId = toParentId;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
