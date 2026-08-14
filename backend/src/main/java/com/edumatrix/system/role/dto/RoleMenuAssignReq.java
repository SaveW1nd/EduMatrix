package com.edumatrix.system.role.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * 为角色分配菜单请求（03-01 §3.6）。
 *
 * <p>{@code menuIds} 是<b>全量数组</b>（含目录 M、菜单 C、按钮 F 三级勾选结果），
 * 写入方式是全量覆盖。{@code @NotNull} 而不是 {@code @NotEmpty}：
 * §3.6 明写「传 {@code []} 表示<b>清空</b>该角色全部菜单」——
 * 用 {@code @NotEmpty} 会把一个合法操作挡在 400 上。
 */
public class RoleMenuAssignReq {

    @NotNull(message = "不能为空（传 [] 表示清空该角色全部菜单）")
    private List<Long> menuIds;

    public List<Long> getMenuIds() {
        return menuIds;
    }

    public void setMenuIds(List<Long> menuIds) {
        this.menuIds = menuIds;
    }
}
