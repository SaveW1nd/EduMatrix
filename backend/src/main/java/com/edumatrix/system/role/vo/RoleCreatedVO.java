package com.edumatrix.system.role.vo;

import java.time.LocalDateTime;

/** 创建角色的响应体（03-01 §3.3 响应示例，字段逐个对齐）。 */
public class RoleCreatedVO {

    private Long id;
    private String roleName;
    private String roleKey;
    private Integer status;
    private LocalDateTime createTime;

    public RoleCreatedVO() {
    }

    public RoleCreatedVO(Long id, String roleName, String roleKey, Integer status,
                         LocalDateTime createTime) {
        this.id = id;
        this.roleName = roleName;
        this.roleKey = roleKey;
        this.status = status;
        this.createTime = createTime;
    }

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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
