package com.edumatrix.system.user.vo;

import java.time.LocalDateTime;

/**
 * 用户列表项（03-01 §2.1 响应示例 + 字段说明表，逐个对齐）。
 *
 * <p><b>没有 {@code password}</b>，也没有 {@code tenantId} —— 前者永不出库
 * （{@code SysUser} 写模型压根没声明该列），后者是插件的事、不该出现在响应里。
 */
public class UserListVO {

    private Long id;
    private String username;
    private String realName;
    private Integer userType;
    private String phone;
    private String avatar;
    private Integer status;

    /** 该账号所在组织树节点 ID（{@code sys_user.node_id}）。 */
    private Long nodeId;

    /** 0 平台超管 1 管理员 2 教师 3 学生（§2.1 字段说明表）。 */
    private Integer nodeType;

    /**
     * 节点路径面包屑，<b>自机构根节点起</b>、以 {@code /} 拼接。
     *
     * <p>不含平台根哨兵：契约 §2.9「{@code nodePath} 的口径是『自租户根到自身』，
     * 平台根不属于任何租户，出现在租户的面包屑里反而是越界」。
     */
    private String nodePath;

    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public Integer getUserType() {
        return userType;
    }

    public void setUserType(Integer userType) {
        this.userType = userType;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }

    public String getNodePath() {
        return nodePath;
    }

    public void setNodePath(String nodePath) {
        this.nodePath = nodePath;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
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
