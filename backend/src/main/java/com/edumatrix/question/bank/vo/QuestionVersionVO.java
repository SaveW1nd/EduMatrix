package com.edumatrix.question.bank.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

/** 版本快照的内容体（03-04 §2.4 的 {@code version} 对象 / §2.6 的完整快照）。 */
public class QuestionVersionVO {

    private Integer version;
    private JsonNode content;
    private JsonNode correctAnswer;
    private String analysis;
    private BigDecimal scoreDefault;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdTime;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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
