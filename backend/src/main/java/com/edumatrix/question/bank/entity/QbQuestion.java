package com.edumatrix.question.bank.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code qb_question} 题目主表（03-04 §2，02-数据库设计 §4.5.2）。
 *
 * <h2>物理 ID 恒定，内容在版本表</h2>
 * <p>PRD F3-2 逐字：题目使用雪花全局唯一物理 ID，<b>永不复用</b>；内容存于
 * {@link QbQuestionVersion}，编辑 = 写新版本 + 更新 {@link #currentVersion}，ID 不变。
 * 所以本表<b>没有 content / correct_answer / analysis / score_default</b> ——
 * 那四列只在版本表里，且写入后不可变。
 *
 * <h2>归属只有 {@code owner_node_id} 一个真相源</h2>
 * <p>契约 §4「资源归属唯一化」：{@code crs_course} / {@code qb_question} /
 * {@code vod_video} 一律以 {@code owner_node_id} 表示归属，<b>不保留独立的
 * teacher_id / creator 归属字段</b>；作者署名用通用字段 {@code create_by}。
 * 与 {@code course/catalog/entity/CrsCourse} 同型，<b>将来也不要加</b>。
 *
 * <h2>材料题：父子同 owner，但授权只挂父题</h2>
 * <p>03-04 §0.1：「材料题的父题与子题写入同一 {@code owner_node_id}」，
 * 且「材料题以<b>父题</b>为授权粒度：授权父题即连带其全部子题」。
 * 两句合起来意味着 —— 子题的 <b>owner 判定</b>与父题同结果，但子题的
 * <b>授权判定</b>必须折算到父题（{@code org_resource_grant} 里没有子题的行）。
 * 折算写在 {@code question/bank/service/QuestionVisibilityProvider}，不在这里。
 */
@TableName("qb_question")
public class QbQuestion extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** {@code 0} 草稿。 */
    public static final int STATUS_DRAFT = 0;
    /** {@code 1} 启用（仅此状态可被选入作业，PRD F3-1 规则 1）。 */
    public static final int STATUS_ENABLED = 1;
    /** {@code 2} 停用。 */
    public static final int STATUS_DISABLED = 2;

    /** 普通题与材料题父题的 {@code parent_id} 取值（DDL 默认 0）。 */
    public static final long NO_PARENT = 0L;

    /** 归属节点（创建时写入创建者 {@code sys_user.node_id}），<b>请求体不接受</b>（03-04 §0.1）。 */
    private Long ownerNodeId;

    private Long categoryId;

    /** 题型 1单选 2多选 3判断 4填空 5简答 6材料题(父题)（契约 §5）。 */
    private Integer questionType;

    /** 材料题子题指向父题 ID；普通题与父题为 {@link #NO_PARENT}。 */
    private Long parentId;

    /** 难度 1~5。 */
    private Integer difficulty;

    /** 当前版本号，从 1 起。每次内容变更 +1，历史留在 {@link QbQuestionVersion}。 */
    private Integer currentVersion;

    /** 题干纯文本摘要，供列表检索展示。PRD F3-1 规则 5：≤200 字符（DDL 留 500 余量）。 */
    private String stemPreview;

    /** {@code 0} 草稿 {@code 1} 启用 {@code 2} 停用。 */
    private Integer status;

    // 【本表没有 sort 列】——子题顺序的唯一真相源是父题版本 content 里的 childOrder
    // 数组（03-04 §2.2/§2.3）。在这里加一个 sort 就是第二份顺序，两者必然会漂。

    public boolean isChild() {
        return parentId != null && parentId != NO_PARENT;
    }

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Integer getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Integer difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getStemPreview() {
        return stemPreview;
    }

    public void setStemPreview(String stemPreview) {
        this.stemPreview = stemPreview;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
