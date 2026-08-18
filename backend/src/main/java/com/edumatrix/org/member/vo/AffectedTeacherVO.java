package com.edumatrix.org.member.vo;

/** 接口 24 响应里的 {@code affectedTeachers} 行。 */
public class AffectedTeacherVO {

    private Long teacherNodeId;

    private String teacherName;

    private Integer archivedCount;

    public Long getTeacherNodeId() {
        return teacherNodeId;
    }

    public void setTeacherNodeId(Long teacherNodeId) {
        this.teacherNodeId = teacherNodeId;
    }

    public String getTeacherName() {
        return teacherName;
    }

    public void setTeacherName(String teacherName) {
        this.teacherName = teacherName;
    }

    public Integer getArchivedCount() {
        return archivedCount;
    }

    public void setArchivedCount(Integer archivedCount) {
        this.archivedCount = archivedCount;
    }
}
