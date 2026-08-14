package com.edumatrix.auth.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * 登录链路专用的 {@code sys_user} 实体。
 *
 * <h2>定案（模块 03）：保留为读模型，与 {@code system/user/entity/SysUser} 并存</h2>
 * <p>本类原留有「模块 03 落地后须重新评估：合并还是保留」的标记。<b>那次评估已经发生，
 * 结论是保留</b>，三条理由如下 —— 写在这里是为了让下一个人<b>不必再评估一次</b>。
 *
 * <ol>
 *   <li><b>合并会把全库唯一的 {@code TenantHelper.ignore()} 调用点搬进 {@code system}，
 *       或者变成两处。</b>登录链路的核心查询是
 *       {@code TenantHelper.ignore(() -> authUserMapper.selectByUsername(username))} ——
 *       它必须跨租户，因为<b>那一刻还不知道租户是谁，租户恰恰是这次查询的结果</b>。
 *       要合并，就得让 {@code system/user} 的 Service 暴露一个「按用户名查、带 BCrypt 密文、
 *       跨租户」的方法，那个 {@code ignore()} 就跟着搬家。而
 *       {@code TokenService} 的类注释正把「全系统的 {@code ignore()} 因此仍然只有一处」
 *       当作<b>已兑现的承诺</b>在引用 —— 合并等于当场作废那句话。
 *   <li><b>两者的形状本来就不同。</b>本类是 11 列<b>只读</b>窄实体，且必须读
 *       {@code password}；{@code SysUser} 是写模型，而它<b>任何路径下都不读
 *       {@code password}</b>（03-01 §2.3 明写「密码不经本接口修改」，§2.5 只写不读）。
 *       合并的结果是一个既把密文暴露给写侧、又给读侧背上 8 个用不到的列的实体。
 *   <li><b>合并后 {@code auth} 反而更脆。</b>登录是免登录白名单接口，依赖越少越好；
 *       让它转而依赖 {@code system} 领域的 Service，等于把登录链路挂到一个会随
 *       模块 03/04/07 频繁演化的包上。
 * </ol>
 *
 * <p>（§A1 第三条硬约束<b>禁止领域包互相 import</b>，{@code scripts/check_backend_conventions.sh}
 * 的检查③会 grep 出 {@code auth} 里的 {@code import com.edumatrix.system.*} ——
 * 它排除了「直接共用实体」这条路，但不是本次定案的主要理由：即便没有它，上面三条依然成立。）
 *
 * <p><b>跨领域的账号能力走 SPI</b>：{@code system} / {@code org} 需要 {@code auth} 的
 * 会话作废与口令哈希时，注入 {@code common/account} 下的 {@code SessionRevoker} /
 * {@code PasswordHasher}（实现是 {@code auth/session/AuthAccountProvider}），
 * 不碰本类，也不碰 {@code AuthUserMapper}。
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
