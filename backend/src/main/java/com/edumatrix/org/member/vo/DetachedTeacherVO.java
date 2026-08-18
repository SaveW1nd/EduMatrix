package com.edumatrix.org.member.vo;

/** 接口 22 响应里的 {@code detachedTeachers} 行。 */
public class DetachedTeacherVO {

    private Long teacherNodeId;

    private String teacherName;

    /** 本次转交导致脱离该导师的学员数。<b>原先无导师的学员不出现在此列表</b>。 */
    private Integer detachedCount;

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

    public Integer getDetachedCount() {
        return detachedCount;
    }

    public void setDetachedCount(Integer detachedCount) {
        this.detachedCount = detachedCount;
    }
}
