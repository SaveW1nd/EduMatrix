package com.edumatrix.course.catalog.dto;

/**
 * 接口 3 创建课程（03-03 §1.3）。
 *
 * <p><b>不接受 {@code ownerNodeId}</b>：由服务端强制写入创建者所在节点
 * （§1.3 说明「请求体不接受该参数（传入即忽略）」）。
 * <b>不接受 {@code teacherId}</b>：归属唯一由 {@code owner_node_id} 表示，
 * 创建人以通用字段 {@code create_by} 记录（契约 §4 资源归属唯一化）。
 * <b>不接受 {@code status}</b>：新建恒为 0 草稿，流转走接口 6。
 */
public class CourseCreateReq {

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
