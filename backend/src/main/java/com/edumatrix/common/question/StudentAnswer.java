package com.edumatrix.common.question;

import java.util.List;

/**
 * 学生作答的类型化模型（{@code hw_answer_detail.student_answer}，03-04 §4.3）。
 *
 * <p>与 {@link CorrectAnswer} 在题型 1/2/3 上同形、在 4/5 上不同形 ——
 * 逐条差异与定案依据见 {@link CorrectAnswer} 类注释。
 *
 * <p><b>模块 15 是唯一消费方</b>：模块 10 的十二个接口一个都不收学生作答。
 * 放在这里是因为「解析 + 规范化 + 判分」三件事必须与标准答案<b>同一套代码</b> ——
 * 分开写就是两份同源实现，而两边规范化不一致的表现是
 * <b>接口 200、字段齐全、判分错</b>。
 */
public sealed interface StudentAnswer {

    /** 未作答（03-04 §4.4 处理流程 3：未作答的客观题记 0 分）。 */
    record Unanswered() implements StudentAnswer {
    }

    /** 1 单选。 */
    record SingleChoice(String key) implements StudentAnswer {
    }

    /** 2 多选：{@code keys} 已<b>排序去重</b>。 */
    record MultiChoice(List<String> keys) implements StudentAnswer {
    }

    /** 3 判断：<b>布尔字面量</b>；字符串 {@code "true"} 在解析处 400。 */
    record TrueFalse(boolean value) implements StudentAnswer {
    }

    /** 4 填空：已按 {@code index} 升序、{@code text} 已去首尾空白。 */
    record Blanks(List<FilledBlank> blanks) implements StudentAnswer {
    }

    /**
     * 学生填的一个空。
     *
     * @param index 空位序号，从 1 起
     * @param text  学生填的内容（已去首尾空白）
     */
    record FilledBlank(int index, String text) {
    }

    /** 5 简答：进人工批改。 */
    record Text(String text) implements StudentAnswer {
    }
}
