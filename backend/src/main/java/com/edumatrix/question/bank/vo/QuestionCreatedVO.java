package com.edumatrix.question.bank.vo;

import java.util.List;

/**
 * 接口 6 创建题目的响应（03-04 §2.2）。
 *
 * <p>普通题型 {@code childIds} 为<b>空数组</b>而不是 {@code null} ——
 * 前端不必为两种形状各写一个分支。
 */
public class QuestionCreatedVO {

    private Long id;
    private Integer currentVersion;
    private List<Long> childIds;

    public QuestionCreatedVO() {
    }

    public QuestionCreatedVO(Long id, Integer currentVersion, List<Long> childIds) {
        this.id = id;
        this.currentVersion = currentVersion;
        this.childIds = childIds;
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

    public List<Long> getChildIds() {
        return childIds;
    }

    public void setChildIds(List<Long> childIds) {
        this.childIds = childIds;
    }
}
