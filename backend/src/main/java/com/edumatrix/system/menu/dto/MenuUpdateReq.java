package com.edumatrix.system.menu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改菜单请求（03-01 §4.3）。
 *
 * <p><b>没有 {@code menuType}</b>：§4.3 明写「{@code menuType} 创建后不可修改」。
 * 不放进 DTO 比"放进来再忽略"好 —— 前者让调用方在 400 里立刻知道这个字段不被接受，
 * 后者会让人以为改成功了。
 */
public class MenuUpdateReq {

    /** 父菜单 ID。<b>不可移动到自身或自身后代之下</b>（§4.3，判定在 Service）。 */
    @NotNull(message = "不能为空")
    private Long parentId;

    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "最长 50 字")
    private String menuName;

    /** 权限标识。<b>改动会影响线上鉴权，需与后端注解同步发版</b>（§4.3 原文）。 */
    @Size(max = 100, message = "最长 100 字")
    private String perms;

    @Size(max = 200, message = "最长 200 字")
    private String path;

    @Size(max = 100, message = "最长 100 字")
    private String icon;

    /** 0 隐藏 1 显示。§4.3 参数表里本字段<b>必填</b>。 */
    @NotNull(message = "不能为空")
    private Integer visible;

    /** 同级排序。§4.3 参数表里本字段<b>必填</b>。 */
    @NotNull(message = "不能为空")
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
