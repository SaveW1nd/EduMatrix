package com.edumatrix.system.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改角色请求（03-01 §3.4 参数表：{@code roleName} 必填、{@code status} 必填、{@code remark} 选填）。
 *
 * <p><b>没有 {@code roleKey}</b>：§3.4 的参数表里就没有它。角色标识是权限判定的锚点，
 * 改了会让此前按它写的一切（初始化数据、前端硬编码、将来的按角色分支）指向落空。
 *
 * <p><b>没有 {@code dataScope}</b>：§3.4 原文「若需调整某人的可见范围，
 * 改的是他在组织树中的位置（02-组织机构分册的节点移动接口），不是这里」。
 */
public class RoleUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String roleName;

    /**
     * 0 正常 1 停用。
     *
     * <p>对预置角色而言这是三个字段里危害最大的一个：停用平台预置的 {@code teacher}
     * 会让全平台所有租户的教师瞬间失权（§3.4 原文）。拦截见 {@code PresetRoleGuard}。
     */
    @NotNull(message = "不能为空")
    private Integer status;

    @Size(max = 500, message = "最长 500 字")
    private String remark;

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
