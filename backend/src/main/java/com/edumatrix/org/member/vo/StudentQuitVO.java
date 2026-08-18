package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 23 学生退课的响应（03-02 §6.8）。 */
public class StudentQuitVO {

    private Long studentId;

    private Long nodeId;

    private String realName;

    /** 恒为 1 已退课。 */
    private Integer status;

    private LocalDateTime quitTime;

    private String quitReason;

    /** 恒为 7 退课。 */
    private Integer changeType;

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

    public LocalDateTime getQuitTime() {
        return quitTime;
    }

    public void setQuitTime(LocalDateTime quitTime) {
        this.quitTime = quitTime;
    }

    public String getQuitReason() {
        return quitReason;
    }

    public void setQuitReason(String quitReason) {
        this.quitReason = quitReason;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }
}
