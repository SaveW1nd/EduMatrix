package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 接口 13 修改教师（03-02 §5.3）。
 *
 * <p><b>调岗不走本接口</b>，须使用接口 4（移动节点）——其名下学员子树整体跟随，
 * 且只写 1 条 {@code change_type=4} 轨迹（PRD F1-4）。
 */
public class TeacherUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String realName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "\\d{11}", message = "须为 11 位手机号")
    private String phone;

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String teacherNo;

    /** 留空表示不修改。 */
    @Size(max = 30, message = "最长 30 字符")
    private String username;

    /** 留空表示不修改。 */
    @Size(max = 100, message = "最长 100 字符")
    private String nodeName;

    @Size(max = 50, message = "最长 50 字符")
    private String subject;

    @Size(max = 50, message = "最长 50 字符")
    private String title;

    private String entryDate;

    private Integer sort;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getTeacherNo() {
        return teacherNo;
    }

    public void setTeacherNo(String teacherNo) {
        this.teacherNo = teacherNo;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(String entryDate) {
        this.entryDate = entryDate;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
