package com.edumatrix.system.role.dto;

/**
 * 分页查询角色的查询参数（03-01 §3.1）。
 *
 * <p><b>没有 {@code dataScope}</b>，也没有 {@code tenantId}：前者已从 {@code sys_role}
 * 移除（契约 §3），后者的过滤由租户插件按会话自动注入
 * —— org_admin 得到「本租户 + 平台预置」，super_admin 得到全量（契约 §2.9 的两条不同通道）。
 */
public class RolePageQuery {

    private Integer pageNum;
    private Integer pageSize;

    /** 角色名称，模糊匹配。 */
    private String roleName;

    /** 角色标识，精确匹配。 */
    private String roleKey;

    /** 0 正常 1 停用。 */
    private Integer status;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
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
}
