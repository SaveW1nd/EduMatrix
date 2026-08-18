package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 25 归档恢复的响应（03-02 §6.10）。 */
public class StudentUnarchivedVO {

    private Long studentId;

    private Long nodeId;

    private String realName;

    /** 恒为 0 在读。 */
    private Integer status;

    private Long fromParentId;

    private String fromParentName;

    private Long toParentId;

    private String toParentName;

    private String newAncestors;

    /** 恒为 6 归档恢复。 */
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
