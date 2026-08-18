package com.edumatrix.org.member.dto;

/**
 * 接口 7 管理员分页列表的查询参数（03-02 §4.1）。
 *
 * <p>{@code nodeId} 不传即以当前登录人所在节点为起点；传入的节点<b>必须在自身子树内</b>，
 * 否则 {@code 10107}（契约 §2.4 三分法：请求参数里显式指定的目标越界 → 业务码，
 * 提示「请重新选择」，而不是静默 404）。
 */
public class AdminPageQuery {

    private Integer pageNum;

    private Integer pageSize;

    /** 查询范围起点节点 ID，默认当前用户所在节点，按其<b>子树</b>过滤。 */
    private Long nodeId;

    /** {@code true} 仅返回 {@code nodeId} 的直接下级管理员；默认 {@code false}（整棵子树）。 */
    private Boolean directOnly;

    /** 姓名，模糊匹配。 */
    private String realName;

    /** 手机号，精确匹配。 */
    private String phone;

    /** <b>节点</b>状态：0 正常 1 停用（不是账号状态）。 */
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

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Boolean getDirectOnly() {
        return directOnly;
    }

    public void setDirectOnly(Boolean directOnly) {
        this.directOnly = directOnly;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
