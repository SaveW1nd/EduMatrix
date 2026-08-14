package com.edumatrix.system.menu.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点（03-01 §4.1 响应）。
 *
 * <p>字段集<b>逐字对齐 §4.1 的响应示例与字段说明表</b>：
 * {@code id / parentId / menuName / menuType / perms / path / visible / sort / children}。
 * <b>不带 {@code status} 与 {@code icon}</b> —— §4.1 的示例与说明表里都没有它们，
 * VO 与 entity 分开的意义正在于此（05-工程结构.md §C2 对 {@code vo/} 的定义）。
 *
 * <p>{@code children} 恒为数组（叶子是 {@code []}，不是 {@code null}）——
 * 与 §4.1 示例里按钮行的 {@code "children": []} 一致，前端无需判空。
 */
public class MenuTreeVO {

    private Long id;
    private Long parentId;
    private String menuName;
    private String menuType;
    private String perms;
    private String path;
    private Integer visible;
    private Integer sort;
    private List<MenuTreeVO> children = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public List<MenuTreeVO> getChildren() {
        return children;
    }

    public void setChildren(List<MenuTreeVO> children) {
        this.children = children == null ? new ArrayList<>() : children;
    }
}
