package com.edumatrix.system.tenant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启用/停用租户请求（03-01 §5.7）。
 *
 * <p>停用后该租户全员在线 Token 作废、登录返回 {@code 10007}；重新启用即时恢复。
 * <b>停用的是 {@code sys_tenant.status}，不是机构根节点</b>——机构根节点不可停用
 * （PRD F1-1 规则 7），那是 {@code org_node.status} 的语义（{@code 10017}，找机构管理员），
 * 与本接口（{@code 10007}，找平台）是两个不同的原因、两拨不同的责任人，不得混用。
 */
public class TenantStatusReq {

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
