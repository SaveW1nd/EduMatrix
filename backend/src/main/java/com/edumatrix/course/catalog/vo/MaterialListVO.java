package com.edumatrix.course.catalog.vo;

import java.time.LocalDateTime;

/** 接口 17 图文资料分页列表的行（03-03 §4.1）。 */
public class MaterialListVO {

    private Long id;

    private String title;

    private Integer attachmentCount;

    /** 引用该资料的<b>未删除</b>课时数；&gt; 0 时删除被拒（{@code 20010}）。 */
    private Integer refLessonCount;

    private Long createBy;

    private String createByName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getAttachmentCount() {
        return attachmentCount;
    }

    public void setAttachmentCount(Integer attachmentCount) {
        this.attachmentCount = attachmentCount;
    }

    public Integer getRefLessonCount() {
        return refLessonCount;
    }

    public void setRefLessonCount(Integer refLessonCount) {
        this.refLessonCount = refLessonCount;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public String getCreateByName() {
        return createByName;
    }

    public void setCreateByName(String createByName) {
        this.createByName = createByName;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
