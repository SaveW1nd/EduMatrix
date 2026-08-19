package com.edumatrix.question.bank.vo;

/** 接口 11 启用/停用题目的响应（03-04 §2.7）。 */
public class QuestionStatusVO {

    private Long id;
    private Integer status;

    public QuestionStatusVO() {
    }

    public QuestionStatusVO(Long id, Integer status) {
        this.id = id;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
