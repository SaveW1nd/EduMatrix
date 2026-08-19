package com.edumatrix.course.catalog.vo;

import java.time.LocalDateTime;

/**
 * 接口 2 课程详情（03-03 §1.2）。
 *
 * <p><b>作者署名用 {@code createBy} / {@code createByName}；归属用 {@code ownerNodeId} /
 * {@code ownerNodeName}</b>（§1.2 尾注）。两者语义不同：创建人调岗后 {@code createBy} 不变，
 * 归属仍看节点。<b>没有 {@code teacherId} / {@code teacherName}</b> ——
 * 契约 §4 资源归属唯一化把它们彻底移除了。
 *
 * <p>{@code coverUrl} 同 {@link CourseListVO}：现签地址，不是对象键。
 */
public class CourseDetailVO {

    private Long id;

    private String courseName;

    private Long coverFileId;

    private String coverUrl;

    private String subject;

    private String description;

    private Long ownerNodeId;

    private String ownerNodeName;

    private Integer grantType;

    private Integer status;

    private Integer lessonCount;

    private Integer totalDuration;

    private String remark;

    private Long createBy;

    private String createByName;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public Long getCoverFileId() {
        return coverFileId;
    }

    public void setCoverFileId(Long coverFileId) {
        this.coverFileId = coverFileId;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public String getOwnerNodeName() {
        return ownerNodeName;
    }

    public void setOwnerNodeName(String ownerNodeName) {
        this.ownerNodeName = ownerNodeName;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getLessonCount() {
        return lessonCount;
    }

    public void setLessonCount(Integer lessonCount) {
        this.lessonCount = lessonCount;
    }

    public Integer getTotalDuration() {
        return totalDuration;
    }

    public void setTotalDuration(Integer totalDuration) {
        this.totalDuration = totalDuration;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public String getCreateByName() {
        return createByName;
    }

    public void setCreateByName(String createByName) {
        this.createByName = createByName;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(Long updateBy) {
        this.updateBy = updateBy;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
