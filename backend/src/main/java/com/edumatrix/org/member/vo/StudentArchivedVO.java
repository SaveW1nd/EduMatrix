package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 接口 24 批量毕业归档的响应（03-02 §6.9）。 */
public class StudentArchivedVO {

    private Integer archivedCount;

    private LocalDateTime archiveTime;

    /** 恒为 5 毕业归档。 */
    private Integer changeType;

    /** 本次归档原因：1 正常毕业 2 因监护人删除请求（<b>启动 30 日脱敏倒计时</b>）。 */
    private Integer archiveReason;

    private List<AffectedTeacherVO> affectedTeachers;

    public Integer getArchivedCount() {
        return archivedCount;
    }

    public void setArchivedCount(Integer archivedCount) {
        this.archivedCount = archivedCount;
    }

    public LocalDateTime getArchiveTime() {
        return archiveTime;
    }

    public void setArchiveTime(LocalDateTime archiveTime) {
        this.archiveTime = archiveTime;
    }

    public Integer getChangeType() {
        return changeType;
    }

    public void setChangeType(Integer changeType) {
        this.changeType = changeType;
    }

    public Integer getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(Integer archiveReason) {
        this.archiveReason = archiveReason;
    }

    public List<AffectedTeacherVO> getAffectedTeachers() {
        return affectedTeachers;
    }

    public void setAffectedTeachers(List<AffectedTeacherVO> affectedTeachers) {
        this.affectedTeachers = affectedTeachers;
    }
}
