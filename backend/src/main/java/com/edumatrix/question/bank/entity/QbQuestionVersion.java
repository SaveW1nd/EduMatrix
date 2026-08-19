package com.edumatrix.question.bank.entity;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code qb_question_version} 题目版本快照（03-04 §2.5/§2.6，02-数据库设计 §4.5.3）。
 *
 * <h2>只增不改，而且是【机制上】写不进去</h2>
 * <p>契约 §4 版本规则与 PRD F3-2 规则 3 都写「历史版本不可修改、不可删除」
 * 「无任何更新入口（含管理员）」。本项目对「不可修改」的落实<b>不是一句注释</b>：
 * <ul>
 *   <li><b>编译期</b>：{@code QbQuestionVersionMapper} <b>不 extends BaseMapper</b>，
 *       没有 {@code updateById} / {@code deleteById} 这两个方法<b>存在</b>，
 *       写出来编译不过；
 *   <li><b>脚本</b>：{@code scripts/check_backend_conventions.sh} 检查 ⑦ 三条 grep
 *       （不得 extends BaseMapper、不得有 Update/Delete 注解、全库不得出现
 *       {@code UPDATE/DELETE ... qb_question_version}）防复发。
 * </ul>
 * <p>库级触发器<b>不加</b>（F-73 定案）：它会把数据修复通道一并焊死，
 * 而契约 §7.3 没有为触发器定运维口径。
 *
 * <p>本类因此<b>只被读与插入</b>。它仍继承 {@link TenantEntity} 是为了让实体与表逐列对应
 * （05-工程结构 §F2），{@code updateBy} / {@code updateTime} / {@code deletedAt} 三列
 * 在业务上恒为初值。
 */
@TableName("qb_question_version")
public class QbQuestionVersion extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 首个版本号（PRD F3-2 规则 1：创建即 version=1）。 */
    public static final int FIRST_VERSION = 1;

    private Long questionId;

    /** 版本号，从 {@link #FIRST_VERSION} 起递增。{@code UK(question_id, version, deleted_at)}。 */
    private Integer version;

    /** 题目内容快照 JSON（题干/选项/blankCount/childOrder）。写入后不可变。 */
    private String content;

    /** 标准答案 JSON（契约 §5 逐题型形状；材料题父题为 {@code null}）。写入后不可变。 */
    private String correctAnswer;

    private String analysis;

    private BigDecimal scoreDefault;

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public void setCorrectAnswer(String correctAnswer) {
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
