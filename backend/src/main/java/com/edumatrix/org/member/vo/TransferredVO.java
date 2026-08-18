package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 接口 22 转交给其他管理员的响应（03-02 §6.7）。 */
public class TransferredVO {

    private Integer transferredCount;

    private Long toNodeId;

    private String toNodeName;

    private Integer toNodeType;

    private Integer changeType;

    private LocalDateTime changeTime;

    private List<DetachedTeacherVO> detachedTeachers;

    public Integer getTransferredCount() {
        return transferredCount;
    }

    public void setTransferredCount(Integer transferredCount) {
        this.transferredCount = transferredCount;
    }

    public Long getToNodeId() {
        return toNodeId;
    }

    public void setToNodeId(Long toNodeId) {
        this.toNodeId = toNodeId;
    }

    public String getToNodeName() {
        return toNodeName;
    }

    public void setToNodeName(String toNodeName) {
        this.toNodeName = toNodeName;
    }

    public Integer getToNodeType() {
        return toNodeType;
    }

    public void setToNodeType(Integer toNodeType) {
        this.toNodeType = toNodeType;
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

    public List<DetachedTeacherVO> getDetachedTeachers() {
        return detachedTeachers;
    }

    public void setDetachedTeachers(List<DetachedTeacherVO> detachedTeachers) {
        this.detachedTeachers = detachedTeachers;
    }
}
