package com.edumatrix.system.role.vo;

import java.time.LocalDateTime;

/**
 * 角色列表项（03-01 §3.1 响应示例，字段逐个对齐）。
 *
 * <p><b>没有 {@code dataScope}</b> —— §3.2 的说明逐字写着「响应中无 {@code dataScope} 字段。
 * 该角色的持有者能看到哪些数据，取决于其 {@code sys_user.node_id} 所在子树，与本角色无关」。
 *
 * <p>{@code preset} 是<b>算出来的</b>（{@code tenant_id = 0}），不是表里的列 ——
 * 这正是 VO 与 entity 要分开的典型场景。前端据它把预置角色的编辑/删除按钮置灰。
 */
public class RoleListVO {

    private Long id;
    private String roleName;
    private String roleKey;
    private Integer status;
    /** 是否平台预置角色（{@code tenant_id = 0}）。前端据此置灰写操作入口。 */
    private Boolean preset;
    private LocalDateTime createTime;
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public Boolean getPreset() {
        return preset;
    }

    public void setPreset(Boolean preset) {
        this.preset = preset;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
