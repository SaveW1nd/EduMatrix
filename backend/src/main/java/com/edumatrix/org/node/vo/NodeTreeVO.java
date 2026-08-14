package com.edumatrix.org.node.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 组织树节点（03-02 §3.1）。
 *
 * <p>{@code children} 恒为数组、不为 {@code null}：懒加载形态下每个节点的
 * {@code children} 都是 {@code []}，前端据此判断「还没展开」而不是「没有子节点」——
 * 后者看 {@code childCount}。
 */
public class NodeTreeVO {

    private Long id;
    private Long parentId;

    /** 祖级路径逗号串，含根哨兵 {@code 0}，不含本节点。 */
    private String ancestors;

    private String nodeName;

    /** 0 平台超管 1 管理员 2 教师 3 学生。 */
    private Integer nodeType;

    /** 关联账号 {@code sys_user.id}，<b>恒非空</b>（每个节点都是一个人）。 */
    private Long refUserId;

    /** 关联账号真实姓名，<b>恒非空</b>。 */
    private String refUserName;

    private Integer sort;

    /** 0 正常 1 停用。 */
    private Integer status;

    /** 直接子节点数（冗余字段）。 */
    private Integer childCount;

    /** <b>整棵子树内</b>在读学生数（冗余字段，{@code org_student.status=0} 口径）。 */
    private Integer studentCount;

    private List<NodeTreeVO> children = new ArrayList<>();

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

    public String getAncestors() {
        return ancestors;
    }

    public void setAncestors(String ancestors) {
        this.ancestors = ancestors;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }

    public Long getRefUserId() {
        return refUserId;
    }

    public void setRefUserId(Long refUserId) {
        this.refUserId = refUserId;
    }

    public String getRefUserName() {
        return refUserName;
    }

    public void setRefUserName(String refUserName) {
        this.refUserName = refUserName;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getChildCount() {
        return childCount;
    }

    public void setChildCount(Integer childCount) {
        this.childCount = childCount;
    }

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }

    public List<NodeTreeVO> getChildren() {
        return children;
    }

    public void setChildren(List<NodeTreeVO> children) {
        this.children = children == null ? new ArrayList<>() : children;
    }
}
