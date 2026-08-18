package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 21 批量分配导师的响应（03-02 §6.6）。 */
public class BatchAssignedVO {

    private Integer assignedCount;

    private Long toTeacherNodeId;

    private String toTeacherName;

    private Integer changeType;

    private LocalDateTime changeTime;

    public Integer getAssignedCount() {
        return assignedCount;
    }

    public void setAssignedCount(Integer assignedCount) {
        this.assignedCount = assignedCount;
    }

    public Long getToTeacherNodeId() {
        return toTeacherNodeId;
    }

    public void setToTeacherNodeId(Long toTeacherNodeId) {
        this.toTeacherNodeId = toTeacherNodeId;
    }

    public String getToTeacherName() {
        return toTeacherName;
    }

    public void setToTeacherName(String toTeacherName) {
        this.toTeacherName = toTeacherName;
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
