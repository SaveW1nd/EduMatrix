package com.edumatrix.system.tenant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改租户请求（03-01 §5.4）。
 *
 * <p><b>没有 {@code expireTime}</b>：§5.4 原文「到期时间调整请使用 5.6 续期接口，
 * 本接口不修改 {@code expireTime}」。<b>没有 {@code rootNodeId}</b>：只读字段，
 * 改动即等同于跨租户搬迁。<b>没有 {@code status}</b>：启停用走 §5.7。
 */
public class TenantUpdateReq {

    /** 机构名称。<b>同步更新机构根节点的 {@code node_name}</b>，随之影响全机构的 {@code nodePath} 展示。 */
    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "最长 50 字")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String contactName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^\\d{11}$", message = "须为 11 位数字")
    private String contactPhone;

    /** 学生数上限，<b>不得低于该租户当前在读学生数</b>（低于返回 400）。 */
    @NotNull(message = "不能为空")
    @Min(value = 1, message = "须 ≥ 1")
    private Integer maxStudentCount;

    @Size(max = 500, message = "最长 500 字")
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public Integer getMaxStudentCount() {
        return maxStudentCount;
    }

    public void setMaxStudentCount(Integer maxStudentCount) {
        this.maxStudentCount = maxStudentCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
