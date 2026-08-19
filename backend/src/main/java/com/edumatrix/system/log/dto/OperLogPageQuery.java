package com.edumatrix.system.log.dto;

/**
 * §8.2 分页查询操作日志的 Query 参数（逐条对齐 03-01 §8.2 的请求参数表）。
 *
 * <p><b>{@code module} 模糊、{@code action} 精确</b> —— §8.2 参数表逐字如此，不是笔误。
 */
public class OperLogPageQuery {

    private Integer pageNum;
    private Integer pageSize;
    /** 功能模块，模糊匹配（如「用户管理」）。 */
    private String module;
    /** 操作类型，<b>精确</b>匹配（如「新增」「导出」）。 */
    private String action;
    /** 操作人用户名，模糊匹配。 */
    private String username;
    private String beginTime;
    private String endTime;
    /** 仅 super_admin 可用。 */
    private Long tenantId;

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

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(String beginTime) {
        this.beginTime = beginTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
