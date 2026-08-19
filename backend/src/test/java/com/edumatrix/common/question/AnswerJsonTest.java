package com.edumatrix.common.question;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnswerJson}：判断题布尔类型校验、多选排序去重、填空 index 对齐去空白，
 * 以及 400 / 30006 的分界线。
 */
class AnswerJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int codeOf(Executable action) {
        BizException error = assertThrows(BizException.class, action::run);
        return error.getErrorCode().getCode();
    }

    @FunctionalInterface
    interface Executable {
        void run();
    }

    // ==================================================== 判断题：布尔 vs 字符串

    @Test
    @DisplayName("判断题收到字符串 \"true\" → 400，不做隐式转换（契约 §5 强调段）")
    void rejectsStringTrueForTrueFalseType() {
        assertEquals(400, codeOf(() ->
                AnswerJson.readCorrect(QuestionType.TRUE_FALSE, json("{\"answer\":\"true\"}"))));
        assertEquals(400, codeOf(() ->
                AnswerJson.readCorrect(QuestionType.TRUE_FALSE, json("{\"answer\":\"false\"}"))));
        assertEquals(400, codeOf(() ->
                AnswerJson.readStudent(QuestionType.TRUE_FALSE, json("{\"answer\":\"true\"}"))),
                "学生侧也必须拦 —— 只拦一侧等于没拦：另一侧写进库的就是字符串");
    }

    @Test
    @DisplayName("判断题布尔字面量 true / false 正常通过")
    void acceptsBooleanLiteral() {
        assertEquals(new CorrectAnswer.TrueFalse(true),
                AnswerJson.readCorrect(QuestionType.TRUE_FALSE, json("{\"answer\":true}")));
        assertEquals(new CorrectAnswer.TrueFalse(false),
                AnswerJson.readCorrect(QuestionType.TRUE_FALSE, json("{\"answer\":false}")));
        assertEquals(new StudentAnswer.TrueFalse(true),
                AnswerJson.readStudent(QuestionType.TRUE_FALSE, json("{\"answer\":true}")));
    }

    @Test
    @DisplayName("判断题 1 / 0 也不接受 —— 只认布尔，数字同样是类型错")
    void rejectsNumericBoolean() {
        assertEquals(400, codeOf(() ->
                AnswerJson.readCorrect(QuestionType.TRUE_FALSE, json("{\"answer\":1}"))));
    }

    /**
     * <b>这条不测我们的代码，测的是「危险默认」本身。</b>
     *
     * <p>Jackson 默认会把 {@code "true"} 强制转成 {@code true}。所以只要有人把
     * {@link AnswerJson} 换回「DTO 里一个 {@code Boolean} 字段让 Jackson 绑定」的写法，
     * {@link #rejectsStringTrueForTrueFalseType} 会红，而<b>本条会告诉他为什么</b>：
     * 那个写法下 {@code "true"} 根本到不了校验器，它在绑定阶段就已经变成 {@code true} 了。
     *
     * <p>若哪天 Jackson 改了默认行为、本条自己红了，那说明「必须用 JsonNode 显式判类型」
     * 这条理由的前提变了 —— 那时该重新评估，而不是直接删掉本条。
     */
    @Test
    @DisplayName("Jackson 默认【会】把 \"true\" 静默转成 true —— 这就是不能用 POJO 绑定的原因")
    void jacksonDefaultWouldSilentlyCoerceStringTrue() throws Exception {
        NaiveDto naive = new ObjectMapper().readValue("{\"answer\":\"true\"}", NaiveDto.class);
        assertEquals(Boolean.TRUE, naive.answer(),
                "Jackson 不再隐式转换了？那 AnswerJson 用 JsonNode 显式判类型的理由要重新评估");
    }

    /** 只为上面那条存在：如果判断题用 DTO 绑定，长的就是这个样子。 */
    record NaiveDto(Boolean answer) {
    }

    // ==================================================== 多选：排序去重

    @Test
    @DisplayName("多选 [\"C\",\"A\"] 与 [\"A\",\"C\"] 解析成同一个规范形")
    void multiChoiceIsSortedAndDeduped() {
        CorrectAnswer ca = AnswerJson.readCorrect(QuestionType.MULTI, json("{\"answer\":[\"C\",\"A\"]}"));
        CorrectAnswer ac = AnswerJson.readCorrect(QuestionType.MULTI, json("{\"answer\":[\"A\",\"C\"]}"));
        assertEquals(ac, ca);
        assertEquals(java.util.List.of("A", "C"), ((CorrectAnswer.MultiChoice) ca).keys());
    }

    @Test
    @DisplayName("多选重复项去重：[\"A\",\"A\",\"C\"] ≡ [\"A\",\"C\"]")
    void multiChoiceDedupes() {
        assertEquals(AnswerJson.readCorrect(QuestionType.MULTI, json("{\"answer\":[\"A\",\"C\"]}")),
                AnswerJson.readCorrect(QuestionType.MULTI, json("{\"answer\":[\"A\",\"A\",\"C\"]}")));
    }

    @Test
    @DisplayName("落库走规范形：writeCorrect 输出的多选一定是排好序的")
    void writeCorrectEmitsCanonicalForm() {
        CorrectAnswer answer = AnswerJson.readCorrect(QuestionType.MULTI,
                json("{\"answer\":[\"D\",\"B\",\"A\"]}"));
        assertEquals("{\"answer\":[\"A\",\"B\",\"D\"]}", AnswerJson.writeCorrect(answer).toString());
    }

    @Test
    @DisplayName("多选给了字符串而不是数组 → 400（类型错）")
    void multiChoiceRejectsNonArray() {
        assertEquals(400, codeOf(() ->
                AnswerJson.readCorrect(QuestionType.MULTI, json("{\"answer\":\"A\"}"))));
    }

    // ==================================================== 填空：index 对齐 + 去空白

    @Test
    @DisplayName("填空按 index 升序重排，accepts 去首尾空白")
    void blanksAreSortedAndStripped() {
        CorrectAnswer answer = AnswerJson.readCorrect(QuestionType.BLANK, json("""
                {"blanks":[{"index":2,"accepts":["  扬子江 ","大江"]},
                           {"index":1,"accepts":[" 北京","北京市 "]}]}"""));
        CorrectAnswer.Blanks blanks = (CorrectAnswer.Blanks) answer;
        assertEquals(1, blanks.blanks().get(0).index());
        assertEquals(2, blanks.blanks().get(1).index());
        assertEquals(java.util.List.of("北京", "北京市"), blanks.blanks().get(0).accepts());
        assertEquals(java.util.List.of("扬子江", "大江"), blanks.blanks().get(1).accepts());
    }

    @Test
    @DisplayName("学生填空同样按 index 升序、text 去首尾空白")
    void studentBlanksAreSortedAndStripped() {
        StudentAnswer.Blanks blanks = (StudentAnswer.Blanks) AnswerJson.readStudent(QuestionType.BLANK,
                json("{\"blanks\":[{\"index\":2,\"text\":\" 扬子江 \"},{\"index\":1,\"text\":\"北京 \"}]}"));
        assertEquals(new StudentAnswer.FilledBlank(1, "北京"), blanks.blanks().get(0));
        assertEquals(new StudentAnswer.FilledBlank(2, "扬子江"), blanks.blanks().get(1));
    }

    @Test
    @DisplayName("填空 index 重复 → 30006（结构错，不是类型错）")
    void duplicateBlankIndexIsMismatch() {
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(), codeOf(() ->
                AnswerJson.readCorrect(QuestionType.BLANK,
                        json("{\"blanks\":[{\"index\":1,\"accepts\":[\"a\"]},"
                                + "{\"index\":1,\"accepts\":[\"b\"]}]}"))));
    }

    @Test
    @DisplayName("填空 accepts 不是数组 → 400（类型错）")
    void blankAcceptsMustBeArray() {
        assertEquals(400, codeOf(() ->
                AnswerJson.readCorrect(QuestionType.BLANK,
                        json("{\"blanks\":[{\"index\":1,\"accepts\":\"北京\"}]}"))));
    }

    // ==================================================== 材料题父题

    @Test
    @DisplayName("材料题父题不存答案；带了答案 → 30006")
    void materialParentHasNoAnswer() {
        assertEquals(new CorrectAnswer.MaterialParent(),
                AnswerJson.readCorrect(QuestionType.MATERIAL, null));
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(), codeOf(() ->
                AnswerJson.readCorrect(QuestionType.MATERIAL, json("{\"answer\":\"A\"}"))));
    }

    // ==================================================== content 校验

    @Test
    @DisplayName("选项数越界 → 30006（PRD F3-1：2~8 项）")
    void optionCountBounds() {
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(), codeOf(() ->
                AnswerJson.validateContent(QuestionType.SINGLE,
                        json("{\"stem\":\"x\",\"options\":[{\"key\":\"A\",\"text\":\"a\"}]}"))));
    }

    @Test
    @DisplayName("填空 blankCount 与题干里的 ____ 个数不一致 → 30006（03-04 §2.2）")
    void blankCountMustMatchStemMarkers() {
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(), codeOf(() ->
                AnswerJson.validateContent(QuestionType.BLANK,
                        json("{\"stem\":\"中国的首都是____。\",\"blankCount\":2}"))));
        AnswerJson.validateContent(QuestionType.BLANK,
                json("{\"stem\":\"中国的首都是____，长江古称____。\",\"blankCount\":2}"));
    }

    @Test
    @DisplayName("答案选项号不在 options 里 → 30006（类型对、语义不对）")
    void answerKeyMustExistInOptions() {
        JsonNode content = json("{\"stem\":\"x\",\"options\":["
                + "{\"key\":\"A\",\"text\":\"a\"},{\"key\":\"B\",\"text\":\"b\"}]}");
        CorrectAnswer answer = AnswerJson.readCorrect(QuestionType.SINGLE, json("{\"answer\":\"E\"}"));
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(), codeOf(() ->
                AnswerJson.validateAgainstContent(QuestionType.SINGLE, content, answer)));
    }

    @Test
    @DisplayName("blankCount 与 blanks 项数不一致 → 30006")
    void blankCountMustMatchAnswerLength() {
        JsonNode content = json("{\"stem\":\"a____b____c\",\"blankCount\":2}");
        CorrectAnswer answer = AnswerJson.readCorrect(QuestionType.BLANK,
                json("{\"blanks\":[{\"index\":1,\"accepts\":[\"x\"]}]}"));
        assertEquals(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getCode(), codeOf(() ->
                AnswerJson.validateAgainstContent(QuestionType.BLANK, content, answer)));
    }

    @Test
    @DisplayName("学生未作答（null / 空对象）是一个真实状态，不是错误")
    void unansweredIsNotAnError() {
        assertTrue(AnswerJson.readStudent(QuestionType.SINGLE, null)
                instanceof StudentAnswer.Unanswered);
        assertTrue(AnswerJson.readStudent(QuestionType.MULTI, json("{}"))
                instanceof StudentAnswer.Unanswered);
    }
}
