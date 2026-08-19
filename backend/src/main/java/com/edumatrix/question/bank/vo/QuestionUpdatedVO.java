package com.edumatrix.question.bank.vo;

/**
 * 接口 7 修改题目的响应（03-04 §2.3）。
 *
 * <p>{@code versionCreated=false} 表示本次仅更新了分类/难度/备注，未产生新版本。
 */
public class QuestionUpdatedVO {

    private Long id;
    private Integer currentVersion;
    private Boolean versionCreated;

    public QuestionUpdatedVO() {
    }

    public QuestionUpdatedVO(Long id, Integer currentVersion, Boolean versionCreated) {
        this.id = id;
        this.currentVersion = currentVersion;
        this.versionCreated = versionCreated;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Boolean getVersionCreated() {
        return versionCreated;
    }

    public void setVersionCreated(Boolean versionCreated) {
        this.versionCreated = versionCreated;
    }
}
