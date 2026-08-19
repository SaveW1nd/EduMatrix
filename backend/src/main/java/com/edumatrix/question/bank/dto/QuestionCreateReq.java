package com.edumatrix.question.bank.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 6 创建题目（03-04 §2.2）。
 *
 * <h2>{@code content} / {@code correctAnswer} 是 {@link JsonNode}，不是 POJO</h2>
 * <p>六种题型的形状各不相同，用 POJO 绑定要么写六个 DTO、要么写一个宽松的联合体 ——
 * 后者会把「判断题的 answer 是不是布尔」这件事交给 Jackson 的默认强制转换，
 * 而 Jackson 默认<b>会</b>把 {@code "true"} 转成 {@code true}
 * （见 {@code common/question/AnswerJson} 类注释）。
 * 保持 {@code JsonNode} 到校验层，类型判定才有得判。
 *
 * <p><b>{@code ownerNodeId} 不在这里</b>：03-04 §0.1 逐字「由服务端在创建时强制写入
 * 创建者所在节点，请求体不接受该参数」。
 */
public class QuestionCreateReq {

    @NotNull(message = "不能为空")
    private Long categoryId;

    /** 1单选 2多选 3判断 4填空 5简答 6材料题(父题)。 */
    @NotNull(message = "不能为空")
    @Min(value = 1, message = "题型取值 1~6")
    @Max(value = 6, message = "题型取值 1~6")
    private Integer questionType;

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "难度取值 1~5")
    @Max(value = 5, message = "难度取值 1~5")
    private Integer difficulty;

    @NotNull(message = "不能为空")
    private JsonNode content;

    /** 题型 1/2/3/4 必填，5 建议填，6（父题）不填 —— 逐型校验在 {@code AnswerJson}。 */
    private JsonNode correctAnswer;

    @Size(max = 2000, message = "最长 2000 字符")
    private String analysis;

    @NotNull(message = "不能为空")
    @DecimalMin(value = "0.0", message = "建议分值不能为负")
    private BigDecimal scoreDefault;

    /** {@code 0} 草稿 {@code 1} 启用，默认 0；<b>不允许直接创建为 2 停用</b>。 */
    @Min(value = 0, message = "创建时只能是 0 草稿或 1 启用")
    @Max(value = 1, message = "创建时只能是 0 草稿或 1 启用")
    private Integer status;

    /** {@code questionType=6} 时必填且至少 1 个子题。 */
    @Valid
    private List<ChildQuestionReq> childQuestions;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getQuestionType() {
        return questionType;
    }

    public void setQuestionType(Integer questionType) {
        this.questionType = questionType;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<ChildQuestionReq> getChildQuestions() {
        return childQuestions;
    }

    public void setChildQuestions(List<ChildQuestionReq> childQuestions) {
        this.childQuestions = childQuestions;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
