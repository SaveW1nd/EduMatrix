package com.edumatrix.system.tenant.dto;

import java.time.LocalDateTime;

/**
 * 分页查询租户的筛选条件（03-01 §5.1）。
 *
 * <p>无租户注入（本组是平台级操作），也<b>无子树过滤</b>——租户不是树上的节点，
 * §0.2 的那条数据权限规则在这一组接口上不适用。
 */
public class TenantPageQuery {

    private Integer pageNum;
    private Integer pageSize;

    /** 机构名称，<b>模糊</b>匹配。 */
    private String name;

    /** 联系电话，<b>精确</b>匹配。 */
    private String contactPhone;

    /** 0 正常 1 停用。 */
    private Integer status;

    /** 到期时间早于该时刻（{@code yyyy-MM-dd HH:mm:ss}），用于筛选临期租户。 */
    private LocalDateTime expireBefore;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getExpireBefore() {
        return expireBefore;
    }

    public void setExpireBefore(LocalDateTime expireBefore) {
        this.expireBefore = expireBefore;
    }
}
