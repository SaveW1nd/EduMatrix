package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 20 分配导师的响应（03-02 §6.5）。 */
public class StudentAssignedVO {

    private Long studentId;

    private Long nodeId;

    private String realName;

    private Long fromParentId;

    private String fromParentName;

    private Integer fromParentType;

    private Long toParentId;

    private String toParentName;

    private Integer toParentType;

    private String newAncestors;

    /** 2 分配导师（由 {@code NodeMoveService} 按父子类型推断）。 */
    private Integer changeType;

    private LocalDateTime changeTime;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
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

    public Integer getFromParentType() {
        return fromParentType;
    }

    public void setFromParentType(Integer fromParentType) {
        this.fromParentType = fromParentType;
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

    public Integer getToParentType() {
        return toParentType;
    }

    public void setToParentType(Integer toParentType) {
        this.toParentType = toParentType;
    }

    public String getNewAncestors() {
        return newAncestors;
    }

    public void setNewAncestors(String newAncestors) {
        this.newAncestors = newAncestors;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }

    public LocalDateTime getChangeTime() {
        return changeTime;
    }

    public void setChangeTime(LocalDateTime changeTime) {
        this.changeTime = changeTime;
    }
}
