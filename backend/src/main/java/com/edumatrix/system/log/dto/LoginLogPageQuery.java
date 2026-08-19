package com.edumatrix.system.log.dto;

/**
 * §8.1 分页查询登录日志的 Query 参数（逐条对齐 03-01 §8.1 的请求参数表）。
 *
 * <p>{@code tenantId} <b>仅 super_admin 可用</b>（§8.1 参数表逐字）。
 * 它是在租户插件已经生效之后的<b>额外收窄</b>，不是租户隔离本身 ——
 * org_admin 传了也越不出自己租户，因为插件的 {@code WHERE tenant_id = ?} 还在。
 * 服务层仍会把非超管传的这个值清空，理由见 {@code LogQueryService}。
 */
public class LoginLogPageQuery {

    private Integer pageNum;
    private Integer pageSize;
    private String username;
    /** 0 成功 1 失败。 */
    private Integer status;
    private String ip;
    /** {@code yyyy-MM-dd HH:mm:ss}。 */
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
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
