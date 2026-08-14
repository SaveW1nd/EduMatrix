package com.edumatrix.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * 登录链路专用的 {@code sys_user} 实体。
 *
 * <h2>⚠ 这是一个窄实体，模块 03 落地后须重新评估它的去留</h2>
 * <p>05-工程结构.md §D 写的是「模块 02 …… 同时触及 {@code system/user/entity}」，
 * 但 §A1 的第三条硬约束<b>禁止领域包互相 import</b>（{@code scripts/check_backend_conventions.sh}
 * 的检查③会 grep 出 {@code auth} 里的 {@code import com.edumatrix.system.*}），
 * 而模块 03 尚不存在、没有对方的 Service 可调。两条只能满足一条时，
 * <b>选那条能被脚本验证的</b>，因此本类落在 {@code auth/}。
 *
 * <p><b>后果是模块 03 建 {@code system/user/} 时会出现第二个 {@code sys_user} 实体。</b>
 * 届时的处置有两条，需要那时决定而不是现在猜：
 * <ol>
 *   <li><b>合并</b> —— 若 {@code system/user} 愿意通过 Service 暴露登录所需的查询，
 *       本类删除，{@code auth} 改调对方 Service（那时检查③仍禁止直接 import 实体，
 *       所以对方 Service 要返回自己的 DTO）；
 *   <li><b>保留为读模型</b> —— 明确本类是登录链路的窄读模型，与 {@code system/user}
 *       的写模型并存，各自演化。
 * </ol>
 * <b>留这段注释是为了让那次评估真的发生</b>，而不是靠人想起来。
 *
 * <h2>字段只取登录链路要用的那些</h2>
 * <p>{@code sys_user} 有 19 列，这里只声明 11 列 —— 缺的列（如 {@code remark}）
 * 登录用不到，MyBatis-Plus 也就不会把它们放进 SELECT。通用字段与
 * {@code tenant_id} 由 {@link TenantEntity} 继承而来（05-工程结构.md §E：
 * 35 张业务表继承它），逻辑删除的 {@code deleted_at = 0} 条件因此自动注入。
 */
@TableName("sys_user")
public class AuthUser extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 登录账号（全平台唯一，{@code uk_username(username, deleted_at)}）。 */
    private String username;

    /** BCrypt 密文（PRD §7.3 安全条款 1：cost ≥ 10，永不明文存储）。 */
    private String password;

    /** 0 平台超管 1 管理员 2 教师 3 学生（契约 §5，与 {@code org_node.node_type} 取值一致）。 */
    private Integer userType;

    private String realName;

    private String phone;

    private String avatar;

    /**
     * 所在组织树节点（{@code → org_node.id}），数据权限的唯一锚点（契约 §2.4）。
     * 与 {@code org_node.ref_user_id} 互为反向引用。
     */
    private Long nodeId;

    /**
     * <b>账号级封禁</b>：0 正常 1 停用。
     *
     * <p>契约 §2.3 定死：本列<b>有且只有一种语义</b> —— 与组织无关的安全风控，<b>仅超管可置</b>，
     * 命中返回 {@code 10005}。组织侧的停用是 {@code org_node.status}（{@code 10017}），
     * <b>两者不联动</b>：机构管理员停用节点时若顺手把本列也置 1，启用时无人复位
     * （启用只改回 {@code org_node.status}），该账号就永久登不进来，而机构侧没有任何接口能修复它。
     *
     * <p><b>本模块只读本列，任何路径下都不写。</b>
     */
    private Integer status;

    /** 是否须强制修改密码：0 否 1 是（PRD F1-1 规则 6；对应响应字段 {@code needChangePassword}）。 */
    private Integer pwdResetFlag;

    private LocalDateTime lastLoginTime;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getUserType() {
        return userType;
    }

    public void setUserType(Integer userType) {
        this.userType = userType;
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

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getPwdResetFlag() {
        return pwdResetFlag;
    }

    public void setPwdResetFlag(Integer pwdResetFlag) {
        this.pwdResetFlag = pwdResetFlag;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    /** 平台超管（{@code user_type = 0}）—— 租户插件对其整体放行（契约 §2.9）。 */
    public boolean isSuperAdmin() {
        return userType != null && userType == 0;
    }

    /** 学生（{@code user_type = 3}）—— 只有学生才需要判学籍归档（{@code 10015}）。 */
    public boolean isStudent() {
        return userType != null && userType == 3;
    }

    /** {@code pwd_reset_flag = 1} → 响应里的 {@code needChangePassword}。 */
    public boolean needChangePassword() {
        return pwdResetFlag != null && pwdResetFlag == 1;
    }
}
