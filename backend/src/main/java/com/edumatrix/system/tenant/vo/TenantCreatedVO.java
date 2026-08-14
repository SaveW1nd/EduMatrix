package com.edumatrix.system.tenant.vo;

import java.time.LocalDateTime;

/**
 * 开通机构的响应（03-01 §5.3），<b>体现三步联动的结果</b>。
 *
 * <h2>{@code id} == {@code rootNodeId} == {@code adminNodeId}</h2>
 * <p>三个字段是<b>同一个值</b>（§5.3 响应示例里三者都是 {@code 1953827104412590084}，
 * 而 {@code adminUserId} 是另一个）。并列返回不是冗余，而是让调用方按语义取用；
 * 对实现来说，它们并列<b>正是为了让一次不相等当场暴露</b>——
 * 与 03-01 §2.2 把 {@code nodeType} 与 {@code userType} 并列返回是同一手法。
 *
 * <h2>{@link #initialPassword} 仅此一次</h2>
 * <p>服务端只存 BCrypt 散列，<b>明文不落库、不可再查</b>（PRD F1-1 规则 6、§5.3 字段说明）。
 * 由超管转交机构联系人；该管理员首次登录 {@code needChangePassword = true}，
 * 经 §1.6 强制改密后方可使用其他功能。
 */
public class TenantCreatedVO {

    private Long id;
    private String name;

    /** 机构根节点 ID，已回写至 {@code sys_tenant.root_node_id}；<b>= {@link #id} = {@link #adminNodeId}</b>。 */
    private Long rootNodeId;

    private LocalDateTime expireTime;
    private Integer status;
    private Integer maxStudentCount;

    /** 机构最高管理员账号 ID（{@code sys_user.id}，{@code user_type = 1}）。<b>与 {@link #id} 不同。</b> */
    private Long adminUserId;

    private String adminUsername;
    private String adminRealName;

    /** 该管理员的<b>节点</b> ID，即 {@code sys_user.node_id}。<b>与 {@link #rootNodeId} 恒为同一个值。</b> */
    private Long adminNodeId;

    /** 节点路径面包屑。根节点即机构名，故<b>只有一段</b>。 */
    private String adminNodePath;

    /** 系统生成的初始密码，<b>仅本次响应一次性返回</b>。 */
    private String initialPassword;

    private LocalDateTime createTime;

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

    public String getAdminRealName() {
        return adminRealName;
    }

    public void setAdminRealName(String adminRealName) {
        this.adminRealName = adminRealName;
    }

    public Long getAdminNodeId() {
        return adminNodeId;
    }

    public void setAdminNodeId(Long adminNodeId) {
        this.adminNodeId = adminNodeId;
    }

    public String getAdminNodePath() {
        return adminNodePath;
    }

    public void setAdminNodePath(String adminNodePath) {
        this.adminNodePath = adminNodePath;
    }

    public String getInitialPassword() {
        return initialPassword;
    }

    public void setInitialPassword(String initialPassword) {
        this.initialPassword = initialPassword;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
