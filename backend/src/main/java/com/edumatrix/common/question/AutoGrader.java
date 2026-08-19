package com.edumatrix.common.question;

import java.util.ArrayList;
import java.util.List;

/**
 * 客观题自动判分（03-04 §4.4 自动判卷规则表，契约 §5）。
 *
 * <h2>本模块【没有生产调用方】—— 这一点主动说明</h2>
 * <p>判卷是模块 15 的事；模块 10 的十二个接口一个都不判分。本类放在这里是因为
 * 04-实施计划.md §B 模块 10「对外产出」把它列为交付物，而且它必须与
 * {@link AnswerJson} 的规范化<b>同一套代码</b> —— 分开写就是两份同源实现。
 * 代价是：<b>在模块 10 里它的正确性完全由单测承担</b>，
 * {@code QuestionSpiWiringIT} 另有一条断言钉住它没被悄悄删掉。
 *
 * <h2>它只回答「对/半对/错」，【不回答分数】</h2>
 * <p>03-04 §4.4 的两条计分规则 —— 多选漏选 ×50%、填空每空 = 题目分值 ÷ 空数
 * （保留 1 位小数，除不尽差额补至最后一空）—— 都需要
 * {@code hw_homework_question.score}，那是模块 15 的表，模块 10 不该知道。
 * {@link Verdict#blankHits()} 存在的唯一理由，就是让模块 15 能算出来
 * 而<b>不必重新解析一遍 JSON</b>。
 *
 * <h2>比较前不再做规范化</h2>
 * <p>多选排序去重、填空按 {@code index} 对齐并去首尾空白，
 * 都已经在 {@link AnswerJson} 的解析出口做完（理由见那里）。
 * 这里只剩比较本身 —— 唯一的额外规则是<b>填空英文忽略大小写</b>（03-04 §4.4）。
 */
public final class AutoGrader {

    private AutoGrader() {
    }

    /** 对应 {@code hw_answer_detail.is_correct}：1 正确 / 2 半对 / 0 错误 / NULL 待批改。 */
    public enum Correctness {
        CORRECT(1),
        PARTIAL(2),
        WRONG(0),
        /** 主观题与材料题父题：{@code is_correct} 写 {@code NULL}，进人工批改。 */
        MANUAL(null);

        private final Integer isCorrect;

        Correctness(Integer isCorrect) {
            this.isCorrect = isCorrect;
        }

        /** 直接落 {@code hw_answer_detail.is_correct} 的值；{@link #MANUAL} 为 {@code null}。 */
        public Integer isCorrect() {
            return isCorrect;
        }
    }

    /**
     * 判定结果。
     *
     * @param correctness 对/半对/错/待批改
     * @param blankHits   填空题逐空是否命中（按 {@code index} 升序）；其余题型为空列表
     */
    public record Verdict(Correctness correctness, List<Boolean> blankHits) {

        public Verdict {
            blankHits = blankHits == null ? List.of() : List.copyOf(blankHits);
        }

        static Verdict of(Correctness correctness) {
            return new Verdict(correctness, List.of());
        }
    }

    /**
     * 判分。签名按 04-实施计划.md §B 模块 10「对外产出」冻结（三个参数、同顺序）。
     *
     * <p>契约 §5：自动判分<b>只在 {@code question_type ∈ {1,2,3,4}} 上执行</b>；
     * 5 简答与 6 材料题父题一律 {@link Correctness#MANUAL}。
     */
    public static Verdict grade(QuestionType type, CorrectAnswer correct, StudentAnswer student) {
        if (type == null || !type.isAutoGraded()) {
            return Verdict.of(Correctness.MANUAL);
        }
        if (student == null || student instanceof StudentAnswer.Unanswered) {
            // 03-04 §4.4 处理流程 3：未作答的客观题记 0 分（is_correct=0）
            return unanswered(type, correct);
        }
        return switch (type) {
            case SINGLE -> gradeSingle((CorrectAnswer.SingleChoice) correct, student);
            case MULTI -> gradeMulti((CorrectAnswer.MultiChoice) correct, student);
            case TRUE_FALSE -> gradeTrueFalse((CorrectAnswer.TrueFalse) correct, student);
            case BLANK -> gradeBlanks((CorrectAnswer.Blanks) correct, student);
            default -> Verdict.of(Correctness.MANUAL);
        };
    }

    private static Verdict unanswered(QuestionType type, CorrectAnswer correct) {
        if (type == QuestionType.BLANK && correct instanceof CorrectAnswer.Blanks blanks) {
            return new Verdict(Correctness.WRONG,
                    blanks.blanks().stream().map(b -> Boolean.FALSE).toList());
        }
        return Verdict.of(Correctness.WRONG);
    }

    private static Verdict gradeSingle(CorrectAnswer.SingleChoice correct, StudentAnswer student) {
        if (!(student instanceof StudentAnswer.SingleChoice picked)) {
            return Verdict.of(Correctness.WRONG);
        }
        return Verdict.of(correct.key().equals(picked.key()) ? Correctness.CORRECT : Correctness.WRONG);
    }

    /**
     * 多选（03-04 §4.4）：全对 1；<b>所选均正确但不全</b>（漏选）2；<b>含任一错选</b> 0。
     *
     * <p>两侧的 {@code keys} 都已排序去重，所以「全对」就是 {@code equals}。
     */
    private static Verdict gradeMulti(CorrectAnswer.MultiChoice correct, StudentAnswer student) {
        if (!(student instanceof StudentAnswer.MultiChoice picked)) {
            return Verdict.of(Correctness.WRONG);
        }
        List<String> right = correct.keys();
        List<String> chosen = picked.keys();
        if (chosen.isEmpty()) {
            return Verdict.of(Correctness.WRONG);
        }
        if (right.equals(chosen)) {
            return Verdict.of(Correctness.CORRECT);
        }
        boolean anyWrongPick = chosen.stream().anyMatch(key -> !right.contains(key));
        return Verdict.of(anyWrongPick ? Correctness.WRONG : Correctness.PARTIAL);
    }

    private static Verdict gradeTrueFalse(CorrectAnswer.TrueFalse correct, StudentAnswer student) {
        if (!(student instanceof StudentAnswer.TrueFalse answered)) {
            return Verdict.of(Correctness.WRONG);
        }
        return Verdict.of(correct.value() == answered.value() ? Correctness.CORRECT : Correctness.WRONG);
    }

    /**
     * 填空（03-04 §4.4）：逐空比对，<b>命中同义答案集 {@code accepts} 任一项即该空得分</b>。
     *
     * <p>去首尾空格已在解析出口做完；这里只剩「英文忽略大小写」。
     * 全对 1、部分对 2、全错 0。
     */
    private static Verdict gradeBlanks(CorrectAnswer.Blanks correct, StudentAnswer student) {
        List<StudentAnswer.FilledBlank> filled = student instanceof StudentAnswer.Blanks blanks
                ? blanks.blanks() : List.of();
        List<Boolean> hits = new ArrayList<>();
        for (CorrectAnswer.BlankKey key : correct.blanks()) {
            String answer = textAt(filled, key.index());
            hits.add(answer != null && !answer.isEmpty() && matches(key.accepts(), answer));
        }
        long hit = hits.stream().filter(Boolean::booleanValue).count();
        Correctness correctness;
        if (hit == hits.size() && !hits.isEmpty()) {
            correctness = Correctness.CORRECT;
        } else if (hit == 0) {
            correctness = Correctness.WRONG;
        } else {
            correctness = Correctness.PARTIAL;
        }
        return new Verdict(correctness, hits);
    }

    /** 按 {@code index} 对齐 —— 不按数组下标，学生可以只填第 2 空。 */
    private static String textAt(List<StudentAnswer.FilledBlank> filled, int index) {
        for (StudentAnswer.FilledBlank blank : filled) {
            if (blank.index() == index) {
                return blank.text();
            }
        }
        return null;
    }

    private static boolean matches(List<String> accepts, String answer) {
        for (String accept : accepts) {
            if (accept.equalsIgnoreCase(answer)) {
                return true;
            }
        }
        return false;
    }
}
