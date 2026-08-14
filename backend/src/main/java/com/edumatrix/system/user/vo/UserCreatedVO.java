package com.edumatrix.system.user.vo;

import java.time.LocalDateTime;

/**
 * 创建用户的响应体（03-01 §2.2 响应示例 + 字段说明表，逐个对齐）。
 *
 * <p>{@code nodeType} 与 {@code userType} <b>两字段并列仅为对照</b>（§2.2 字段说明表原文），
 * 取值恒等，<b>不允许出现不一致的行</b>。把两者都放进响应，正是为了让一次
 * "映射错了"的实现在验收时立刻暴露 —— 而不是等到那个教师分配不到学员时才被发现。
 */
public class UserCreatedVO {

    private Long id;
    private String username;
    private String realName;
    private Integer userType;
    private Integer status;

    /** 同事务内新建的组织树节点 ID，已回写至 {@code sys_user.node_id}。 */
    private Long nodeId;

    /** <b>与 {@code userType} 恒等</b>（契约 §5）：1 管理员、2 教师、3 学生。 */
    private Integer nodeType;

    private Long parentNodeId;

    /** 新节点的路径面包屑（自机构根节点起，{@code /} 拼接）。 */
    private String nodePath;

    private LocalDateTime createTime;

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

    public Long getParentNodeId() {
        return parentNodeId;
    }

    public void setParentNodeId(Long parentNodeId) {
        this.parentNodeId = parentNodeId;
    }

    public String getNodePath() {
        return nodePath;
    }

    public void setNodePath(String nodePath) {
        this.nodePath = nodePath;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
