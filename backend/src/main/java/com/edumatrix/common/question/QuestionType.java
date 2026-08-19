package com.edumatrix.common.question;

import java.util.Optional;

/**
 * 题型（契约 §5 核心枚举 {@code question_type}，03-04 §0.2 原样引用）。
 *
 * <p>取值与 {@code qb_question.question_type} 的 DDL 注释逐字一致，
 * 不得另造（契约 §5「文档中不得另造值」）。
 */
public enum QuestionType {

    /** 1 单选：{@code {"answer":"B"}}。自动判分。 */
    SINGLE(1, true),

    /** 2 多选：{@code {"answer":["A","C"]}}，<b>比较前排序去重</b>。自动判分。 */
    MULTI(2, true),

    /** 3 判断：{@code {"answer":true}}，<b>JSON 布尔字面量、不是字符串</b>。自动判分。 */
    TRUE_FALSE(3, true),

    /** 4 填空：按 {@code index} 对齐、去首尾空白。自动判分。 */
    BLANK(4, true),

    /** 5 简答：进人工批改。 */
    SUBJECTIVE(5, false),

    /** 6 材料题（父题）：父题不存答案、不判分，逐子题按其自身题型判。 */
    MATERIAL(6, false);

    private final int code;
    private final boolean autoGraded;

    QuestionType(int code, boolean autoGraded) {
        this.code = code;
        this.autoGraded = autoGraded;
    }

    public int code() {
        return code;
    }

    /** 契约 §5：「自动判分只在 {@code question_type ∈ {1,2,3,4}} 上执行」。 */
    public boolean isAutoGraded() {
        return autoGraded;
    }

    /** 材料题的子题只允许 1~5（03-04 §2.2）——子题不能再是材料题。 */
    public boolean canBeChild() {
        return this != MATERIAL;
    }

    public static Optional<QuestionType> of(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        for (QuestionType type : values()) {
            if (type.code == code) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
