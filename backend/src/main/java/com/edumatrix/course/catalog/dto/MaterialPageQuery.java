package com.edumatrix.course.catalog.dto;

/** 接口 17 图文资料分页列表（03-03 §4.1）。 */
public class MaterialPageQuery {

    private Integer pageNum;
    private Integer pageSize;
    /** 标题，模糊匹配。 */
    private String title;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
