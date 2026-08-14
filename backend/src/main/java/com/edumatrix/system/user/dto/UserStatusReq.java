package com.edumatrix.system.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用/停用用户请求（03-01 §2.6）。<b>仅 {@code super_admin} 可调</b>。
 *
 * <h2>它改的是「账号级封禁」，不是机构侧的停用</h2>
 * <p>契约 §2.3：{@code org_node.status} 是停用的<b>唯一权威</b>，
 * {@code sys_user.status} 仅用于与组织无关的账号级封禁（安全风控）。
 * 本接口属于后者，因此收归超管做安全风控；机构侧的停用走 02-组织机构分册
 * 接口 5（节点停用/启用）。<b>两者不联动</b>，混用会出现「节点正常但账号登不进」
 * 这类无法解释的状态。
 *
 * <p>这也正是「不允许停用节点时顺手写 {@code sys_user.status}」的原因：
 * 机构管理员没有任何接口能把它改回来（本接口是超管专用），只能提工单改库。
 */
public class UserStatusReq {

    /** 0 正常 1 停用。停用后该用户在线 Token 立即作废，登录返回 {@code 10005}。 */
    @NotNull(message = "不能为空")
    @Min(value = 0, message = "只能是 0 正常 / 1 停用")
    @Max(value = 1, message = "只能是 0 正常 / 1 停用")
    private Integer status;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
