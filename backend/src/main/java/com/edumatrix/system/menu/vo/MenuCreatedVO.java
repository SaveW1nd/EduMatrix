package com.edumatrix.system.menu.vo;

import java.time.LocalDateTime;

/**
 * 创建菜单的响应体（03-01 §4.2 响应示例，字段逐个对齐）。
 */
public class MenuCreatedVO {

    private Long id;
    private Long parentId;
    private String menuName;
    private String menuType;
    private String perms;
    private LocalDateTime createTime;

    public MenuCreatedVO() {
    }

    public MenuCreatedVO(Long id, Long parentId, String menuName, String menuType,
                         String perms, LocalDateTime createTime) {
        this.id = id;
        this.parentId = parentId;
        this.menuName = menuName;
        this.menuType = menuType;
        this.perms = perms;
        this.createTime = createTime;
    }

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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
