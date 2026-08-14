package com.edumatrix.system.role.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建角色请求（03-01 §3.3）。
 *
 * <p><b>无 {@code dataScope} 参数</b>（§3.3 原文：「{@code sys_role} 已移除
 * {@code data_scope} 字段。请求中携带该字段将被忽略」）——
 * 新角色持有者的可见范围一律由其所在节点子树决定。
 */
public class RoleCreateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String roleName;

    /**
     * 角色标识，字母下划线，租户内唯一，<b>不得使用预置值</b>
     * （super_admin / org_admin / teacher / student，判定在 {@code PresetRoleGuard}）。
     */
    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "最长 50 字")
    @Pattern(regexp = "^[A-Za-z_]+$", message = "只能包含字母与下划线")
    private String roleKey;

    /** 0 正常（默认）1 停用。 */
    private Integer status;

    /**
     * 初始菜单权限，<b>等价于创建后调用 §3.6</b>（§3.3 参数表原文）。
     *
     * <p>因此它同样受防提权约束：org_admin 传入的菜单不得超出自身拥有的集合
     * （§3.3 数据权限原文）—— 否则这条就成了绕过 §3.6 的后门。
     */
    private List<Long> menuIds;

    @Size(max = 500, message = "最长 500 字")
    private String remark;

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getRoleKey() {
        return roleKey;
    }

    public void setRoleKey(String roleKey) {
        this.roleKey = roleKey;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<Long> getMenuIds() {
        return menuIds;
    }

    public void setMenuIds(List<Long> menuIds) {
        this.menuIds = menuIds;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
