package com.edumatrix.course.catalog.dto;

/**
 * 接口 4 修改课程（03-03 §1.4）。
 *
 * <p>仅改基本信息，不涉及状态流转（上下架走接口 6）。
 * <b>{@code ownerNodeId} 不可通过本接口变更</b>（归属转移属组织操作，走 03-02）。
 */
public class CourseUpdateReq {

    @jakarta.validation.constraints.NotBlank(message = "不能为空")
    @jakarta.validation.constraints.Size(min = 1, max = 100, message = "长度须为 1~100 字符")
    private String courseName;

    /** 封面文件 ID（{@code sys_file.id}，bizType 须为 {@code course_cover}）。 */
    private Long coverFileId;

    @jakarta.validation.constraints.Size(max = 50, message = "最长 50 字符")
    private String subject;

    @jakarta.validation.constraints.Size(max = 2000, message = "最长 2000 字符")
    private String description;

    @jakarta.validation.constraints.Size(max = 500, message = "最长 500 字符")
    private String remark;

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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
