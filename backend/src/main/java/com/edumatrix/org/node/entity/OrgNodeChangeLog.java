package com.edumatrix.org.node.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code org_node_change_log} 节点异动轨迹（契约 §5 {@code change_type}）。
 *
 * <h2>只增不改不删</h2>
 * <p>PRD F1-9 规则 2：「轨迹只增不改不删，系统不提供任何编辑/删除接口」。
 * DDL 的 {@code deleted_at} 注释也逐字写着「轨迹业务上禁止删除，恒为 0」。
 *
 * <h2>本模块写哪几种 change_type</h2>
 * <p>移动接口按 03-02 §3.4 的映射表<b>自动推断</b>，只可能是 2/3/4/8 四种之一
 * （见 {@code NodeMoveService#inferChangeType}）。1 建档归模块 03/07；
 * 5 毕业归档 / 6 归档恢复 / 7 退课归模块 07。
 *
 * <p><b>教师调岗只写 1 条 {@code change_type = 4}</b>，随行学员<b>不</b>逐条写
 * {@code change_type = 2}（PRD F1-4 规则 6、04-实施计划.md 模块 06 规则 10）——
 * DDL 对 {@code node_id} 的列注释也逐字写着「教师调岗时为教师节点，
 * 其学员子树跟随移动但不逐个记录」。
 */
@TableName("org_node_change_log")
public class OrgNodeChangeLog extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 1 建档（模块 03/07 写，本模块不写）。 */
    public static final int CHANGE_TYPE_CREATE = 1;
    /** 2 分配导师：学生（3）→ 教师（2）。 */
    public static final int CHANGE_TYPE_ASSIGN_TEACHER = 2;
    /** 3 转交管理员：学生（3）→ 管理员（1）。 */
    public static final int CHANGE_TYPE_TRANSFER_ADMIN = 3;
    /** 4 教师调岗：教师（2）→ 管理员（1）。其名下学员子树整体跟随，但只写这一条。 */
    public static final int CHANGE_TYPE_TEACHER_REASSIGN = 4;
    /** 8 节点移动：管理员（1）→ 管理员（1），即整个分支换上级。 */
    public static final int CHANGE_TYPE_NODE_MOVE = 8;

    private Long nodeId;

    private Integer changeType;

    private Long fromParentId;

    private Long toParentId;

    /**
     * 异动时间。<b>不做 Java 侧填充</b>：DDL 是 {@code DEFAULT CURRENT_TIMESTAMP}，
     * 由数据库这一个时钟赋值（理由见 {@code common/entity/AuditFieldHandler}）。
     * §3.4 响应里的 {@code changeTime} 是插入后<b>读回来</b>的，不是 Java 侧算的。
     */
    private LocalDateTime changeTime;

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

    public LocalDateTime getChangeTime() {
        return changeTime;
    }

    public void setChangeTime(LocalDateTime changeTime) {
        this.changeTime = changeTime;
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
