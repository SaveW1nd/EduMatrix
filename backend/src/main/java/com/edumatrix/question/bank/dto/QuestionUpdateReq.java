package com.edumatrix.question.bank.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 接口 7 修改题目（03-04 §2.3）。全部可选，只改传了的。
 *
 * <p><b>{@code questionType} 不在这里</b>：PRD F3-2 规则 2「{@code question_id}、
 * {@code category_id}、{@code question_type} 不随版本变；<b>题型不可改</b>，
 * 需换题型应新建题目」。改题型会让已固化该题的历史作业按新题型渲染旧答案 ——
 * 那是接口 200、字段齐全、结果错。
 */
public class QuestionUpdateReq {

    private Long categoryId;

    @Min(value = 1, message = "难度取值 1~5")
    @Max(value = 5, message = "难度取值 1~5")
    private Integer difficulty;

    /** 父题可含 {@code childOrder}（子题 ID 数组）。 */
    private JsonNode content;

    private JsonNode correctAnswer;

    @Size(max = 2000, message = "最长 2000 字符")
    private String analysis;

    @DecimalMin(value = "0.0", message = "建议分值不能为负")
    private BigDecimal scoreDefault;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Integer difficulty) {
        this.difficulty = difficulty;
    }

    public JsonNode getContent() {
        return content;
    }

    public void setContent(JsonNode content) {
        this.content = content;
    }

    public JsonNode getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(JsonNode correctAnswer) {
        this.correctAnswer = correctAnswer;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public BigDecimal getScoreDefault() {
        return scoreDefault;
    }

    public void setScoreDefault(BigDecimal scoreDefault) {
        this.scoreDefault = scoreDefault;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
