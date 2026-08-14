package com.edumatrix.system.tenant.vo;

import java.time.LocalDateTime;

/**
 * 分页查询租户的行（03-01 §5.1）。
 *
 * <p>{@code id} 与 {@code rootNodeId} <b>恒为同一个值</b>（契约 §2.1、§5.0、§5.3）。
 * §5.1 的响应示例目前给的是两个不同的值，那是"机构节点 + 下挂管理员"两层结构的残留，
 * 已登记为 04-实施计划.md §E 的 <b>F-24</b>——实现按契约。
 */
public class TenantListVO {

    private Long id;
    private String name;
    private Long rootNodeId;
    private String contactName;
    private String contactPhone;
    private LocalDateTime expireTime;
    private Integer status;
    private Integer maxStudentCount;

    /** 当前在读学生数（{@code org_student.status = 0}），与 {@code maxStudentCount} 同口径。 */
    private Long currentStudentCount;

    private LocalDateTime createTime;
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
