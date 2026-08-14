package com.edumatrix.system.user.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改用户请求（03-01 §2.3）。<b>仅 {@code super_admin} 可调</b>。
 *
 * <h2>四个字段刻意不在这里</h2>
 * <ul>
 *   <li>{@code username} / {@code userType} —— §2.3 明写创建后不可经本接口修改；
 *   <li>{@code password} —— 不经本接口修改，用 §2.5 重置；
 *   <li>{@code nodeId} / {@code parentNodeId} —— <b>变更所在节点 = 移动树节点</b>
 *       （分配导师、转交管理员、教师调岗），一律走 02-组织机构分册的节点移动接口，
 *       以保证与子树 {@code ancestors} 重算同事务、同行锁（00-通用约定 §7.4）。
 *       §2.3 说的是「本接口传入 {@code nodeId} / {@code parentNodeId} 一律被忽略」——
 *       <b>不放进 DTO 比放进来再忽略好</b>：前者让调用方在 400 里立刻知道这个字段不被接受，
 *       后者会让人以为节点改成功了，而那是一次静默的"没生效"。
 * </ul>
 */
public class UserUpdateReq {

    /** 真实姓名。<b>同步更新其 {@code org_node.node_name}</b>（§2.3 参数表）。 */
    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String realName;

    @Pattern(regexp = "^$|^\\d{11}$", message = "须为 11 位数字")
    private String phone;

    @Size(max = 500, message = "最长 500 字")
    private String avatar;

    /**
     * 角色 ID 数组，<b>全量覆盖</b>（先删后插 {@code sys_user_role}）。
     *
     * <p>§2.3 参数表原文：「角色只决定操作权限，<b>不影响数据范围</b>」——
     * 想改某人的可见范围要移动他的节点，不是改角色。
     *
     * <p>对当前登录账号执行「移除自己的管理员角色」→ {@code 10012}（§2.3 错误码表）。
     */
    @NotEmpty(message = "不能为空")
    private List<Long> roleIds;

    @Size(max = 500, message = "最长 500 字")
    private String remark;

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
