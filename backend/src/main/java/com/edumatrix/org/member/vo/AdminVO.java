package com.edumatrix.org.member.vo;

import java.time.LocalDateTime;

/** 接口 7 管理员分页列表的行（03-02 §4.1）。 */
public class AdminVO {

    /** 管理员节点 ID（{@code org_node.id}），<b>本节其余接口的 {@code {id}}</b>。 */
    private Long nodeId;

    private Long userId;

    private String username;

    private String realName;

    private String phone;

    private String nodeName;

    private Long parentNodeId;

    private String parentNodeName;

    /** 从租户根到本节点的名称路径，{@code /} 分隔。 */
    private String nodePath;

    private Integer sort;

    /** <b>节点</b>状态：0 正常 1 停用。 */
    private Integer status;

    /** 其子树内下级管理员数量。 */
    private Integer subAdminCount;

    /** 其子树内教师数量。 */
    private Integer teacherCount;

    /** 其子树内<b>在读</b>学生数量（{@code org_student.status=0}）。 */
    private Integer studentCount;

    private LocalDateTime lastLoginTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Long getParentNodeId() {
        return parentNodeId;
    }

    public void setParentNodeId(Long parentNodeId) {
        this.parentNodeId = parentNodeId;
    }

    public String getParentNodeName() {
        return parentNodeName;
    }

    public void setParentNodeName(String parentNodeName) {
        this.parentNodeName = parentNodeName;
    }

    public String getNodePath() {
        return nodePath;
    }

    public void setNodePath(String nodePath) {
        this.nodePath = nodePath;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getSubAdminCount() {
        return subAdminCount;
    }

    public void setSubAdminCount(Integer subAdminCount) {
        this.subAdminCount = subAdminCount;
    }

    public Integer getTeacherCount() {
        return teacherCount;
    }

    public void setTeacherCount(Integer teacherCount) {
        this.teacherCount = teacherCount;
    }

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
