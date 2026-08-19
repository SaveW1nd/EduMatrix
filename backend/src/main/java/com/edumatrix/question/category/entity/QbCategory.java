package com.edumatrix.question.category.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code qb_category} 题库分类树（03-04 §1，02-数据库设计 §4.5.1）。
 *
 * <h2>它【不带 owner_node_id】、【不进 org_resource_grant】</h2>
 * <p>03-04 §1.2 与 PRD F3-1 规则 8：分类树是<b>全租户共享的目录结构</b>，
 * 落在「数据范围由树决定」这条规则的<b>管辖之外</b> ——
 * 树能限制谁看到哪些题目，<b>限制不了谁改分类树</b>。
 * 受管资源是题目本身（{@code resource_type=2}），不是分类。
 *
 * <p>所以本表没有归属列，也不该加。写权限靠 {@code perms} 一道门
 * （F-72 定案：删掉 teacher 的三行绑定，不加角色门）。
 */
@TableName("qb_category")
public class QbCategory extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 顶级分类的 {@code parent_id}（DDL 默认 0）。 */
    public static final long ROOT_PARENT = 0L;

    private Long parentId;

    private String categoryName;

    private Integer sort;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
