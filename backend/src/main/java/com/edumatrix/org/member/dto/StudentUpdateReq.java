package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 接口 18 修改学生（03-02 §6.3）。<b>仅改档案与账号基础信息。</b>
 *
 * <p>归属变更走接口 20 / 21 / 22；学籍状态变更走接口 23 / 24 / 25。
 * 已退课或已归档的学生不可修改 → {@code 10203}。
 */
public class StudentUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String realName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "\\d{11}", message = "须为 11 位手机号")
    private String phone;

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String studentNo;

    @Size(max = 30, message = "最长 30 字符")
    private String guardianName;

    @Pattern(regexp = "^$|\\d{11}", message = "须为 11 位手机号")
    private String guardianPhone;

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

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo;
    }

    public String getGuardianName() {
        return guardianName;
    }

    public void setGuardianName(String guardianName) {
        this.guardianName = guardianName;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }

    public void setGuardianPhone(String guardianPhone) {
        this.guardianPhone = guardianPhone;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
