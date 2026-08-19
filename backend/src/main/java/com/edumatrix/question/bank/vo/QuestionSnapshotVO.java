package com.edumatrix.question.bank.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

/** 接口 10 指定版本快照的响应（03-04 §2.6）。版本不存在 → {@code 30007}。 */
public class QuestionSnapshotVO {

    private Long questionId;
    private Integer version;
    private Integer questionType;
    private JsonNode content;
    private JsonNode correctAnswer;
    private String analysis;
    private BigDecimal scoreDefault;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdTime;

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getQuestionType() {
        return questionType;
    }

    public void setQuestionType(Integer questionType) {
        this.questionType = questionType;
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

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedByName() {
        return createdByName;
    }

    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
}
