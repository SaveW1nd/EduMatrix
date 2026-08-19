package com.edumatrix.common.question;

import java.util.List;

/**
 * 标准答案的类型化模型（{@code qb_question_version.correct_answer}）。
 *
 * <h2>它与 {@link StudentAnswer} 【不是】同一个形状 —— 契约 §5 那句话有两处例外</h2>
 * <p>契约 §5 写「{@code correct_answer} 与 {@code hw_answer_detail.student_answer}
 * 共用同一形状」。这句话在题型 1/2/3 上成立，在 <b>4 填空</b>与 <b>5 简答</b>上不成立：
 * <table border="1">
 *   <tr><th>题型</th><th>标准答案</th><th>学生作答</th></tr>
 *   <tr><td>4 填空</td><td>{@code {"blanks":[{"index":1,"accepts":["北京","北京市"]}]}}</td>
 *       <td>{@code {"blanks":[{"index":1,"text":"北京"}]}}</td></tr>
 *   <tr><td>5 简答</td><td>{@code {"referenceAnswer":"…","scoringPoints":[…]}}（F-71 定案前按
 *       {@code {"text":"…"}}）</td><td>{@code {"text":"…"}}</td></tr>
 * </table>
 *
 * <p><b>F-70 定案（明知地推翻契约 §5）</b>：填空题采用 {@code accepts} 同义答案集。
 * 判据是<b>代价不对称</b> —— 按 {@code text} 实现等于取消同义答案，「北京」对、
 * 「北京市」错，而客观题不开放教师改分（PRD F3-6 规则 3），<b>错了没有救济路径</b>。
 * 03-04 §4.4 的判分规则与 PRD F3-1 的验收标准都<b>逐字依赖</b> {@code accepts}。
 * 契约 §5 已同步订正。
 *
 * <p><b>F-71 定案</b>：简答题<b>先按契约的 {@code {"text":"…"}} 实现</b>。
 * 代价比 F-70 小 —— 简答不自动判分，有教师批改这条救济路径。
 * 解除条件写死在 F-71：<b>模块 15 做批改流水线时若确实需要按点给分</b>，
 * 那时扩字段 + 做数据迁移；不为一个还没有消费方的字段现在改契约。
 */
public sealed interface CorrectAnswer {

    QuestionType type();

    /** 1 单选：选项号大写字母。 */
    record SingleChoice(String key) implements CorrectAnswer {
        @Override
        public QuestionType type() {
            return QuestionType.SINGLE;
        }
    }

    /**
     * 2 多选：{@code keys} 已<b>排序去重</b>（{@link AnswerJson} 解析出口保证）。
     *
     * <p>规范化放在解析出口而不是比较入口，理由见 {@link AnswerJson} 类注释。
     */
    record MultiChoice(List<String> keys) implements CorrectAnswer {
        @Override
        public QuestionType type() {
            return QuestionType.MULTI;
        }
    }

    /** 3 判断：<b>布尔字面量</b>。收到字符串 {@code "true"} 在解析处就 400 了，到不了这里。 */
    record TrueFalse(boolean value) implements CorrectAnswer {
        @Override
        public QuestionType type() {
            return QuestionType.TRUE_FALSE;
        }
    }

    /** 4 填空：{@code blanks} 已按 {@code index} 升序、各 {@code accepts} 已去首尾空白。 */
    record Blanks(List<BlankKey> blanks) implements CorrectAnswer {
        @Override
        public QuestionType type() {
            return QuestionType.BLANK;
        }
    }

    /**
     * 单个空的同义答案集（F-70）。
     *
     * @param index   空位序号，从 1 起
     * @param accepts 同义答案集，命中任一项即该空得分（03-04 §4.4）
     */
    record BlankKey(int index, List<String> accepts) {
    }

    /** 5 简答：参考答案（F-71：先按契约的 {@code {"text":"…"}}）。不自动判分。 */
    record Reference(String text) implements CorrectAnswer {
        @Override
        public QuestionType type() {
            return QuestionType.SUBJECTIVE;
        }
    }

    /**
     * 6 材料题父题：<b>不存答案</b>（契约 §5、03-04 §2.2）。
     *
     * <p>用一个显式的取值而不是 {@code null} —— 「父题没有答案」是一个<b>真实状态</b>，
     * 与「答案还没填」不是一回事。这与 {@code common/entity/AuditFieldHandler} 里
     * 「不要让『没发生』和『发生过又被抹掉』落在同一个取值上」是同一条原则。
     *
     * <p>「父题分数 = 子题之和」（契约 §5）是<b>服务层</b>的校验，不在这个模型里 ——
     * 它要的是子题的 {@code score_default}，那不是答案 JSON 的内容。
     */
    record MaterialParent() implements CorrectAnswer {
        @Override
        public QuestionType type() {
            return QuestionType.MATERIAL;
        }
    }
}
