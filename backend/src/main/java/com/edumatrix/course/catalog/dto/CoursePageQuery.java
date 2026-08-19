package com.edumatrix.course.catalog.dto;

/**
 * 接口 1 课程分页列表（03-03 §1.1）。
 *
 * <p><b>没有 {@code ownerNodeId} 参数</b>：§1.1 原写「按归属节点精确筛选（须在本人子树内，
 * 否则 403）」，而按 §0.2 的资源可见性口径（{@code owner_node_id} <b>精确等于</b>我的节点，
 * 或被显式授权给我的节点），该参数<b>没有任何可用取值</b> ——
 * 子树内其它节点的课程本就不可见（下级无法向上授权，契约 §2.5 规则 2），
 * 而被授权课程的 {@code ownerNodeId} 在祖先侧、会被那条「须在本人子树内」的 403 拦掉，
 * 与同接口的 {@code grantType=2} 直接打架。本轮已从 §1.1 参数表删除。
 */
public class CoursePageQuery {

    private Integer pageNum;
    private Integer pageSize;
    /** 课程名称，模糊匹配。 */
    private String courseName;
    /** 科目，精确匹配。 */
    private String subject;
    /** 0 草稿 1 已上架 2 已下架。 */
    private Integer status;
    /** 来源筛选：1 仅自有 2 仅被授权；不传返回两者并集。 */
    private Integer grantType;

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }
}
