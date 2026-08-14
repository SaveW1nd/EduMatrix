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
     * <p>不传则默认为当前登录人所在节点的子树；<b>传入的节点若不在自身子树内返回 403</b>
     * （§2.1 参数表逐字如此）。
     *
     * <p><b>这一处与契约 §2.4 的越界三分法不同</b>：三分法对「请求参数中显式指定的目标」
     * 规定的是 {@code 10107}，而 §2.1 明写 403。按权威顺序（分册 &gt; 契约的通用条款
     * 在具体接口上的例外）照分册实现，并在此登记差异，免得下一个人当成 bug 去"修"。
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
