package com.edumatrix.system.user.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code sys_user} 的<b>写模型</b>（03-01 §2）。
 *
 * <h2>与 {@code auth/entity/AuthUser} 并存，这是定案不是遗留</h2>
 * <p>{@code AuthUser} 是登录链路的 11 列<b>只读窄实体</b>；本类是 §2.2~§2.6 的写模型。
 * 模块 03 已就"合并还是保留"做过评估，结论是<b>保留</b>，三条理由逐条写在
 * {@code AuthUser} 的类注释里，其中最硬的一条是：合并会把全库唯一的
 * {@code TenantHelper.ignore()} 调用点搬进 {@code system} 或变成两处。
 *
 * <h2>{@code password} 只写不读：{@code @TableField(select = false)}</h2>
 * <p>本类<b>任何路径下都不读 {@code password}</b>（§2.3 明写「密码不经本接口修改」，
 * §2.5 只写不读），但它<b>必须声明</b> —— {@code sys_user.password} 是
 * {@code VARCHAR(100) NOT NULL} 且无默认值，不声明就插不进去
 * （{@code Field 'password' doesn't have a default value}）。
 *
 * <p>所以用 {@code select = false}：INSERT 带上它，<b>而每一次 SELECT 都不带</b>。
 * 少一次密文出库就少一次泄露面 —— §2.1 的列表接口一页 100 行，
 * 若把 BCrypt 密文一并查出来，它就进了日志、进了慢查询记录、进了每一个中间对象。
 * 后续改密走 {@code SysUserMapper#resetPassword} 的定向 UPDATE，同样不读。
 */
@TableName("sys_user")
public class SysUser extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 0 平台超管 1 管理员 2 教师 3 学生（契约 §5，与 {@code org_node.node_type} <b>取值恒等</b>）。 */
    public static final int USER_TYPE_PLATFORM = 0;
    public static final int USER_TYPE_ADMIN = 1;
    public static final int USER_TYPE_TEACHER = 2;
    public static final int USER_TYPE_STUDENT = 3;

    /** 账号状态：0 正常 1 停用。 */
    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_DISABLED = 1;

    /** 登录账号，全平台唯一（{@code uk_username(username, deleted_at)}，{@code 10001} 依据）。 */
    private String username;

    /**
     * BCrypt 密文（PRD §7.3 安全条款 1：cost ≥ 10，永不明文存储）。
     *
     * <p>{@code select = false}：只参与 INSERT，<b>不出现在任何 SELECT 里</b>（理由见类注释）。
     * 哈希由 {@code common/account/PasswordHasher} 生成（实现在 {@code auth}），
     * 本模块不自己 {@code new BCryptPasswordEncoder} —— cost 必须全库同值。
     */
    @TableField(select = false)
    private String password;

    /**
     * 用户类型。<b>创建后不可经本组接口修改</b>（§2.3 数据权限原文）。
     *
     * <p>§2.2 参数表对它有一段异常醒目的警告，照抄要点：<b>新建节点的
     * {@code node_type} 与本值恒等，不做任何映射</b>。「userType 加一」那种写法是
     * 一次已完成的节点类型重编号留下的残留，按它实现会把教师建成学生类型的节点，
     * 导致该教师永远分配不到学员（{@code 10106}）、{@code stat_*.teacher_node_id} 恒为 NULL、
     * 导师看板恒空。
     */
    private Integer userType;

    /** 真实姓名。同时作为其 {@code org_node.node_name}（§2.2 / §2.3 参数表）。 */
    private String realName;

    private String phone;

    private String avatar;

    /**
     * 所在组织树节点（{@code → org_node.id}），数据权限的唯一锚点（契约 §2.4）。
     *
     * <p><b>一经创建不可经本组接口修改</b>（§2.2 导语 / §2.3）：变更所在节点 = 移动树节点，
     * 一律走 02-组织机构分册的节点移动接口，以保证与子树 {@code ancestors} 重算同事务、同行锁。
     * §2.3 传入 {@code nodeId} / {@code parentNodeId} 一律被忽略 ——
     * 所以 {@code UserUpdateReq} 里根本没有这两个字段。
     */
    private Long nodeId;

    /**
     * <b>账号级封禁</b>：0 正常 1 停用。契约 §2.3 定死本列<b>有且只有这一种语义</b> ——
     * 与组织无关的安全风控，<b>仅超管可置</b>（§2.6 收敛为超管专用），命中登录返回 {@code 10005}。
     *
     * <p>组织侧的停用是 {@code org_node.status}（{@code 10017}），<b>两者不联动</b>。
     * §2.4 的删除<b>不得</b>借用本列表达「已删除」，一律写 {@code deleted_at}
     * （契约 §2.3 那段「人员已删除不得借用 status」）。
     */
    private Integer status;

    /** 是否须强制修改密码：0 否 1 是。§2.5 重置密码后置 1（PRD F1-3 规则 3）。 */
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
}
