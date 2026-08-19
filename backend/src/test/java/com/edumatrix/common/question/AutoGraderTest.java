package com.edumatrix.common.question;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AutoGrader}：03-04 §4.4 自动判卷规则表逐行。
 *
 * <p><b>本类是模块 10 里 {@link AutoGrader} 的唯一使用者</b> —— 判卷是模块 15 的事，
 * 本模块没有生产调用方（见 {@link AutoGrader} 类注释）。所以这些用例
 * 不是"补充覆盖"，它们<b>是</b>这块代码在本模块的全部验证。
 */
class AutoGraderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AutoGrader.Verdict grade(QuestionType type, String correct, String student) {
        return AutoGrader.grade(type,
                AnswerJson.readCorrect(type, json(correct)),
                AnswerJson.readStudent(type, student == null ? null : json(student)));
    }

    // ==================================================== 多选：顺序无关

    @Test
    @DisplayName("多选 [\"C\",\"A\"] 与 [\"A\",\"C\"] 判分一致（04 §B 模块 10 自检第 3 条）")
    void multiChoiceOrderDoesNotMatter() {
        AutoGrader.Verdict ca = grade(QuestionType.MULTI,
                "{\"answer\":[\"A\",\"C\"]}", "{\"answer\":[\"C\",\"A\"]}");
        AutoGrader.Verdict ac = grade(QuestionType.MULTI,
                "{\"answer\":[\"A\",\"C\"]}", "{\"answer\":[\"A\",\"C\"]}");
        assertEquals(AutoGrader.Correctness.CORRECT, ca.correctness());
        assertEquals(ac, ca);
    }

    @Test
    @DisplayName("多选重复项去重后仍是全对：[\"A\",\"A\",\"C\"] ≡ [\"A\",\"C\"]")
    void multiChoiceDuplicatesAreDeduped() {
        assertEquals(AutoGrader.Correctness.CORRECT, grade(QuestionType.MULTI,
                "{\"answer\":[\"A\",\"C\"]}", "{\"answer\":[\"A\",\"A\",\"C\"]}").correctness());
    }

    @Test
    @DisplayName("多选漏选 → 半对（2）；含任一错选 → 错（0）")
    void multiChoicePartialAndWrong() {
        assertEquals(AutoGrader.Correctness.PARTIAL, grade(QuestionType.MULTI,
                "{\"answer\":[\"A\",\"B\",\"D\"]}", "{\"answer\":[\"A\",\"B\"]}").correctness());
        assertEquals(AutoGrader.Correctness.WRONG, grade(QuestionType.MULTI,
                "{\"answer\":[\"A\",\"B\"]}", "{\"answer\":[\"A\",\"C\"]}").correctness());
        assertEquals(AutoGrader.Correctness.WRONG, grade(QuestionType.MULTI,
                "{\"answer\":[\"A\",\"B\"]}", "{\"answer\":[\"A\",\"B\",\"C\"]}").correctness(),
                "多选了一个错项 —— 不是半对，是 0 分（03-04 §4.4）");
    }

    // ==================================================== 单选 / 判断

    @Test
    @DisplayName("单选与判断：完全一致得满分，否则 0")
    void singleAndTrueFalse() {
        assertEquals(AutoGrader.Correctness.CORRECT,
                grade(QuestionType.SINGLE, "{\"answer\":\"A\"}", "{\"answer\":\"A\"}").correctness());
        assertEquals(AutoGrader.Correctness.WRONG,
                grade(QuestionType.SINGLE, "{\"answer\":\"A\"}", "{\"answer\":\"B\"}").correctness());
        assertEquals(AutoGrader.Correctness.CORRECT,
                grade(QuestionType.TRUE_FALSE, "{\"answer\":true}", "{\"answer\":true}").correctness());
        assertEquals(AutoGrader.Correctness.WRONG,
                grade(QuestionType.TRUE_FALSE, "{\"answer\":true}", "{\"answer\":false}").correctness());
    }

    // ==================================================== 填空

    @Test
    @DisplayName("填空按 index 对齐 —— 学生数组顺序颠倒不影响判分")
    void blanksAlignByIndexNotArrayOrder() {
        AutoGrader.Verdict verdict = grade(QuestionType.BLANK,
                "{\"blanks\":[{\"index\":1,\"accepts\":[\"北京\"]},"
                        + "{\"index\":2,\"accepts\":[\"扬子江\"]}]}",
                "{\"blanks\":[{\"index\":2,\"text\":\"扬子江\"},{\"index\":1,\"text\":\"北京\"}]}");
        assertEquals(AutoGrader.Correctness.CORRECT, verdict.correctness());
        assertEquals(List.of(true, true), verdict.blankHits());
    }

    @Test
    @DisplayName("填空去首尾空白后比较；英文忽略大小写（03-04 §4.4）")
    void blanksStripAndIgnoreCase() {
        assertEquals(AutoGrader.Correctness.CORRECT, grade(QuestionType.BLANK,
                "{\"blanks\":[{\"index\":1,\"accepts\":[\"Beijing\"]}]}",
                "{\"blanks\":[{\"index\":1,\"text\":\"  beijing  \"}]}").correctness());
    }

    @Test
    @DisplayName("填空同义答案集：命中 accepts 任一项即该空得分（F-70）")
    void blanksAcceptSynonyms() {
        assertEquals(AutoGrader.Correctness.CORRECT, grade(QuestionType.BLANK,
                "{\"blanks\":[{\"index\":1,\"accepts\":[\"北京\",\"北京市\"]}]}",
                "{\"blanks\":[{\"index\":1,\"text\":\"北京市\"}]}").correctness());
    }

    @Test
    @DisplayName("填空部分对 → 半对，blankHits 逐空给出命中情况供模块 15 算分")
    void blanksPartial() {
        AutoGrader.Verdict verdict = grade(QuestionType.BLANK,
                "{\"blanks\":[{\"index\":1,\"accepts\":[\"北京\"]},"
                        + "{\"index\":2,\"accepts\":[\"扬子江\"]}]}",
                "{\"blanks\":[{\"index\":1,\"text\":\"北京\"},{\"index\":2,\"text\":\"黄河\"}]}");
        assertEquals(AutoGrader.Correctness.PARTIAL, verdict.correctness());
        assertEquals(List.of(true, false), verdict.blankHits());
    }

    @Test
    @DisplayName("填空只填了一个空 —— 缺的那空算错，不算未作答")
    void blanksMissingOneIsWrongForThatBlank() {
        AutoGrader.Verdict verdict = grade(QuestionType.BLANK,
                "{\"blanks\":[{\"index\":1,\"accepts\":[\"北京\"]},"
                        + "{\"index\":2,\"accepts\":[\"扬子江\"]}]}",
                "{\"blanks\":[{\"index\":1,\"text\":\"北京\"}]}");
        assertEquals(AutoGrader.Correctness.PARTIAL, verdict.correctness());
        assertEquals(List.of(true, false), verdict.blankHits());
    }

    // ==================================================== 主观题 / 未作答

    @Test
    @DisplayName("简答与材料题父题不自动判分 → MANUAL，is_correct 落 NULL")
    void subjectiveAndMaterialAreManual() {
        assertEquals(AutoGrader.Correctness.MANUAL,
                AutoGrader.grade(QuestionType.SUBJECTIVE, null, null).correctness());
        assertEquals(AutoGrader.Correctness.MANUAL,
                AutoGrader.grade(QuestionType.MATERIAL, null, null).correctness());
        assertNull(AutoGrader.Correctness.MANUAL.isCorrect(),
                "MANUAL 必须映射成 NULL —— 落成 0 就变成「判错」而不是「待批改」");
    }

    @Test
    @DisplayName("未作答的客观题记 0 分（03-04 §4.4 处理流程 3）")
    void unansweredObjectiveIsWrong() {
        assertEquals(AutoGrader.Correctness.WRONG,
                grade(QuestionType.SINGLE, "{\"answer\":\"A\"}", null).correctness());
        AutoGrader.Verdict blanks = grade(QuestionType.BLANK,
                "{\"blanks\":[{\"index\":1,\"accepts\":[\"x\"]},"
                        + "{\"index\":2,\"accepts\":[\"y\"]}]}", null);
        assertEquals(AutoGrader.Correctness.WRONG, blanks.correctness());
        assertEquals(List.of(false, false), blanks.blankHits(),
                "未作答也要给出逐空命中，否则模块 15 算分时要为这一种情况另写分支");
    }

    @Test
    @DisplayName("is_correct 取值与契约 §5 一致：1 正确 / 2 半对 / 0 错误 / NULL 待批改")
    void correctnessCodes() {
        assertEquals(1, AutoGrader.Correctness.CORRECT.isCorrect());
        assertEquals(2, AutoGrader.Correctness.PARTIAL.isCorrect());
        assertEquals(0, AutoGrader.Correctness.WRONG.isCorrect());
        assertNull(AutoGrader.Correctness.MANUAL.isCorrect());
    }
}
