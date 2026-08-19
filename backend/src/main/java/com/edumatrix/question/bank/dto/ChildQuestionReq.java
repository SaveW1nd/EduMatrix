package com.edumatrix.question.bank.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 材料题的子题（03-04 §2.2 材料题示例）。
 *
 * <p>子题<b>题型仅允许 1~5</b>（不能再是材料题）；服务端为每个子题生成独立物理 ID
 * 与版本记录，{@code parent_id} 指向父题，顺序记在父题版本的 {@code childOrder} 里。
 *
 * <p>子题<b>没有自己的 {@code categoryId} / {@code difficulty}</b>：随父题。
 * 加上它们就是第二份真相 —— 父题改了分类而子题没改，列表按分类筛就会漏掉半道题。
 */
public class ChildQuestionReq {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "子题题型只能是 1~5")
    @Max(value = 5, message = "子题题型只能是 1~5")
    private Integer questionType;

    /** 子题在父题内的顺序，从 1 起；不传按数组下标。 */
    private Integer sort;

    @NotNull(message = "不能为空")
    private JsonNode content;

    private JsonNode correctAnswer;

    @Size(max = 2000, message = "最长 2000 字符")
    private String analysis;

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.0", message = "建议分值不能为负")
    private BigDecimal scoreDefault;

    public Integer getQuestionType() {
        return questionType;
    }

    public void setQuestionType(Integer questionType) {
        this.questionType = questionType;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
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
}
