package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/**
 * 接口 26 学生异动轨迹的行（03-02 §6.11）。
 *
 * <p>轨迹<b>只增不改不删</b>，系统不提供任何编辑/删除接口（PRD F1-9 规则 2）——
 * 所以本 VO 没有对应的写入 DTO。
 */
public class StudentChangeLogVO {

    private Long id;

    private Long nodeId;

    /** 1 建档 2 分配导师 3 转交管理员 4 教师调岗 5 毕业归档 6 归档恢复 7 退课 8 节点移动。 */
    private Integer changeType;

    private String changeTypeName;

    /** 变更前父节点；<b>建档时为 {@code null}</b>。 */
    private Long fromParentId;

    private String fromParentName;

    /** 变更后父节点；退课（7）与毕业归档（5）<b>不移动节点</b>，与 {@code fromParentId} 相同。 */
    private Long toParentId;

    private String toParentName;

    private LocalDateTime changeTime;

    private Long operatorId;

    private String operatorName;

    private String reason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getChangeTypeName() {
        return changeTypeName;
    }

    public void setChangeTypeName(String changeTypeName) {
        this.changeTypeName = changeTypeName;
    }

    public Long getFromParentId() {
        return fromParentId;
    }

    public void setFromParentId(Long fromParentId) {
        this.fromParentId = fromParentId;
    }

    public String getFromParentName() {
        return fromParentName;
    }

    public void setFromParentName(String fromParentName) {
        this.fromParentName = fromParentName;
    }

    public Long getToParentId() {
        return toParentId;
    }

    public void setToParentId(Long toParentId) {
        this.toParentId = toParentId;
    }

    public String getToParentName() {
        return toParentName;
    }

    public void setToParentName(String toParentName) {
        this.toParentName = toParentName;
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

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
