package com.edumatrix.system.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建菜单请求（03-01 §4.2）。
 *
 * <p>「{@code menuType=F} 时 {@code perms} 必填」「{@code M/C} 时 {@code path} 必填」
 * 这类<b>跨字段</b>约束不写成注解 —— Bean Validation 表达它要么上自定义校验器、
 * 要么上 {@code @AssertTrue}，两者的报错信息都不如 Service 里一条明确的 400 好读。
 * 判定统一在 {@code SysMenuService}，与「按钮不可有子节点」等层级校验同处一地。
 */
public class MenuCreateReq {

    /** 父菜单 ID，根节点传 {@code "0"}。 */
    @NotNull(message = "不能为空")
    private Long parentId;

    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "最长 50 字")
    private String menuName;

    /** M 目录 / C 菜单 / F 按钮（契约 §5 权威值）。 */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[MCF]$", message = "只能是 M 目录 / C 菜单 / F 按钮")
    private String menuType;

    /**
     * 权限标识，全局唯一。{@code menuType=F} 时必填。
     *
     * <p>格式由契约 §3.1 定死：{@code {路由前缀}:{对象}:{动作}}，第一段只能取自
     * 契约 §6.2 的路由前缀（其中 {@code auth} <b>不产生 perms</b> —— 那六个接口要么在
     * 认证白名单里、要么只作用于登录人自身，不经过 {@code @SaCheckPermission}），
     * 第三段只能取自 §3.1 的动作词表。<b>格式校验在 Service，不在这里</b> ——
     * 词表是一张会扩充的表，写进正则就等于把它复制了一份。
     */
    @Size(max = 100, message = "最长 100 字")
    private String perms;

    /** 前端路由地址；{@code menuType=M/C} 时必填，按钮传 {@code null}。 */
    @Size(max = 200, message = "最长 200 字")
    private String path;

    @Size(max = 100, message = "最长 100 字")
    private String icon;

    /** 0 隐藏 1 显示，默认 1（对齐 DDL {@code sys_menu.visible} DEFAULT 1）。 */
    private Integer visible;

    /** 同级排序，默认 0。 */
    private Integer sort;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getMenuType() {
        return menuType;
    }

    public void setMenuType(String menuType) {
        this.menuType = menuType;
    }

    public String getPerms() {
        return perms;
    }

    public void setPerms(String perms) {
        this.perms = perms;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getVisible() {
        return visible;
    }

    public void setVisible(Integer visible) {
        this.visible = visible;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
