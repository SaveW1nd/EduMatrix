package com.edumatrix.system.tenant.vo;

import java.time.LocalDateTime;

/**
 * 租户详情（03-01 §5.2）。
 *
 * <h2>{@code id} / {@code rootNodeId} / {@code adminNodeId} 三者恒为同一个值</h2>
 * <p>契约 §2.1 与 §5.0 的树形图：<b>机构根节点就是机构最高管理员本人</b>，
 * 树上不存在不绑账号的节点，因此不存在"机构节点 + 挂在它下面的初始管理员节点"这种两层结构。
 * §5.3 的响应字段说明逐字：{@code adminNodeId}「与 {@code rootNodeId} <b>恒为同一个值</b>，
 * 两个字段并列只为调用方按语义取用」。
 *
 * <p>§5.2 的响应示例给的是三个不同的值，且 {@code adminNodeId} 的字段说明写着
 * 「挂在 {@code rootNodeId} 之下」——那是<b>已被废弃的两层结构的残留</b>，
 * 已登记为 04-实施计划.md §E 的 <b>F-24</b>。实现按契约，分册待订正。
 */
public class TenantDetailVO {

    private Long id;
    private String name;
    private Long rootNodeId;
    private String contactName;
    private String contactPhone;
    private LocalDateTime expireTime;
    private Integer status;
    private Integer maxStudentCount;
    private Long currentStudentCount;

    /** 该机构根节点子树内的节点总数（含各级管理员/教师/学生，不含已删除），供平台侧粗略了解规模。 */
    private Long nodeCount;

    /** 初始机构管理员账号 ID（{@code sys_user.id}，{@code user_type = 1}）。 */
    private Long adminUserId;

    private String adminUsername;

    /** 初始机构管理员所在节点 ID，<b>= {@link #rootNodeId} = {@link #id}</b>。 */
    private Long adminNodeId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getRootNodeId() {
        return rootNodeId;
    }

    public void setRootNodeId(Long rootNodeId) {
        this.rootNodeId = rootNodeId;
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

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getMaxStudentCount() {
        return maxStudentCount;
    }

    public void setMaxStudentCount(Integer maxStudentCount) {
        this.maxStudentCount = maxStudentCount;
    }

    public Long getCurrentStudentCount() {
        return currentStudentCount;
    }

    public void setCurrentStudentCount(Long currentStudentCount) {
        this.currentStudentCount = currentStudentCount;
    }

    public Long getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(Long nodeCount) {
        this.nodeCount = nodeCount;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public void setAdminUserId(Long adminUserId) {
        this.adminUserId = adminUserId;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public Long getAdminNodeId() {
        return adminNodeId;
    }

    public void setAdminNodeId(Long adminNodeId) {
        this.adminNodeId = adminNodeId;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
