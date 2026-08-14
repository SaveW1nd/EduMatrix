package com.edumatrix.system.user.dto;

/**
 * 分页查询用户的查询参数（03-01 §2.1）。
 *
 * <p>本组<b>唯一对 {@code org_admin} 开放的接口</b>（§2 导语：「只有 2.1 分页查询保留
 * org_admin，供平台与机构两侧共用一个只读账号视图」）。
 */
public class UserPageQuery {

    private Integer pageNum;
    private Integer pageSize;

    /** 用户名，模糊匹配。 */
    private String username;

    /** 真实姓名，模糊匹配。 */
    private String realName;

    /** 手机号，精确匹配。 */
    private String phone;

    /** 1 管理员 2 教师 3 学生。 */
    private Integer userType;

    /** 0 正常 1 停用。 */
    private Integer status;

    /**
     * 限定查询范围为<b>该节点及其子树</b>内的账号。
     *
     * <p>不传则默认为当前登录人所在节点的子树；<b>传入的节点若不在自身子树内返回
     * {@code 10107}</b>。
     *
     * <h2>这里取的是三分法的 {@code 10107}，而不是 §2.1 参数表写的 403</h2>
     * <p><b>因为这不是「分册与契约冲突」，是分册自身的不一致</b>：契约 §2.4 的越界三分法
     * 是<b>全系统统一</b>的语义 —— 「请求参数/请求体中<b>显式指定的目标对象</b>越界 →
     * {@code 10107}（业务码，HTTP 200）」，理由是「用户主动选了越界对象，
     * 需明确提示"请重新选择"，而非静默 404」。
     *
     * <p>而本字段与 <b>§2.2 的 {@code parentNodeId} 是同一形状</b> ——
     * 都是调用方在请求里显式选定的一个节点 —— <b>§2.2 用的就是 {@code 10107}</b>
     * （其错误码表逐字列着「{@code 10107} 目标节点不在您的管辖范围内」）。
     * 同一个分册里，同形状的两个参数给了两种码，其中一个必然是笔误。
     *
     * <p>已登记为 04-实施计划.md §E 的 <b>F-23</b>（未定案：是否订正 §2.1 参数表）。
     * <b>分册未改</b> —— 那是文档改动，等定案。
     */
    private Long nodeId;

    /** 创建时间起（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String beginTime;

    /** 创建时间止（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String endTime;

    /**
     * 仅 {@code super_admin} 可用，指定查询租户。
     *
     * <p>超管不传时查<b>平台级账号</b>（{@code tenant_id = 0}，§2.1 数据权限原文）。
     * {@code org_admin} 传了也无效 —— 他的租户由插件按会话注入，业务层不读本字段。
     */
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
