package com.edumatrix.system.user.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建用户请求（03-01 §2.2）。<b>仅 {@code super_admin} 可调</b>。
 *
 * <p>§2 导语解释过为什么机构侧不能走这条路：本接口<b>不写
 * {@code org_teacher} / {@code org_student} 档案</b>，经它建出的教师在
 * {@code /org/teachers} 列表里查不到、无工号无科目 —— 正是 PRD F1-3 规则 1
 * 明令禁止的孤儿数据。机构侧建人一律走 02-组织机构分册接口 8 / 12 / 17（三写一事务）。
 */
public class UserCreateReq {

    /** 用户名，4~30 位字母数字下划线，租户内与平台级均不可重复（{@code 10001}）。 */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^\\w{4,30}$", message = "须为 4~30 位字母、数字或下划线")
    private String username;

    /**
     * 初始密码，8~20 位且同时含字母与数字。
     *
     * <p><b>本接口的密码由调用方指定、不回显</b>（与 §2.5 不传 {@code newPassword} 时
     * 服务端随机生成并回显一次是两条不同的分支）。「同时含字母与数字」是跨字符的判定，
     * 用正则表达要上先行断言，可读性差 —— 判定放在 Service，与 §2.5 共用一处。
     */
    @NotBlank(message = "不能为空")
    @Size(min = 8, max = 20, message = "长度须为 8~20 位")
    private String password;

    /** 真实姓名，最长 30 字。<b>同时作为新建 {@code org_node} 的 {@code node_name}</b>。 */
    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String realName;

    /**
     * 1 管理员 2 教师 3 学生。
     *
     * <p><b>新建节点的 {@code node_type} 与本值恒等，不做任何映射</b>（契约 §5）。
     * §2.2 参数表对此有一段醒目警告 —— 「userType 加一」那种写法会把教师建成
     * 学生类型的节点，后果链条见 {@code SysUser#getUserType()} 的注释。
     *
     * <p>下界取 1 而不是 0：{@code user_type = 0} 是平台超管，
     * 而平台超管的节点是全表唯一一行 {@code id = 0}（契约 §2.1），不可再建第二个。
     */
    @NotNull(message = "不能为空")
    @Min(value = 1, message = "只能是 1 管理员 / 2 教师 / 3 学生")
    @Max(value = 3, message = "只能是 1 管理员 / 2 教师 / 3 学生")
    private Integer userType;

    /**
     * 挂载的父节点 ID，必须在当前登录人子树内（含自身节点）。
     *
     * <p>不传返回 400，越界返回 {@code 10107}。super_admin 创建平台级账号时可传 {@code "0"}。
     */
    @NotNull(message = "不能为空")
    private Long parentNodeId;

    @Pattern(regexp = "^$|^\\d{11}$", message = "须为 11 位数字")
    private String phone;

    /** 头像 URL（先经 §7.1 文件上传获得）。 */
    @Size(max = 500, message = "最长 500 字")
    private String avatar;

    /** 0 正常（默认）1 停用。 */
    @Min(value = 0, message = "只能是 0 正常 / 1 停用")
    @Max(value = 1, message = "只能是 0 正常 / 1 停用")
    private Integer status;

    /**
     * 角色 ID 数组（写入 {@code sys_user_role}）。§2.2 参数表标为<b>必填</b>。
     *
     * <p>用 {@code @NotEmpty} 而非 {@code @NotNull}：建一个零角色的账号等于建一个
     * 登录进来什么都看不到的账号 —— 它不会报错，只会变成一张工单。
     */
    @NotEmpty(message = "不能为空")
    private List<Long> roleIds;

    @Size(max = 500, message = "最长 500 字")
    private String remark;

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

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public Integer getUserType() {
        return userType;
    }

    public void setUserType(Integer userType) {
        this.userType = userType;
    }

    public Long getParentNodeId() {
        return parentNodeId;
    }

    public void setParentNodeId(Long parentNodeId) {
        this.parentNodeId = parentNodeId;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<Long> getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(List<Long> roleIds) {
        this.roleIds = roleIds;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
