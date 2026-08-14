package com.edumatrix.system.role.vo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色详情（03-01 §3.2 响应示例，字段逐个对齐）。
 *
 * <p>比 {@link RoleListVO} 多一个 {@code menuIds} —— §3.2 的响应示例标题就写着
 * 「{@code menuIds} 用于分配菜单弹窗回显」。
 *
 * <p><b>没有 {@code dataScope}</b>，理由同 {@link RoleListVO}。
 */
public class RoleDetailVO {

    private Long id;
    private String roleName;
    private String roleKey;
    private Integer status;
    private Boolean preset;
    /** 该角色当前绑定的菜单 ID 全量数组（分配菜单弹窗据此回显勾选）。 */
    private List<Long> menuIds = new ArrayList<>();
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

    public List<Long> getMenuIds() {
        return menuIds;
    }

    public void setMenuIds(List<Long> menuIds) {
        this.menuIds = menuIds == null ? new ArrayList<>() : menuIds;
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
