package com.edumatrix.common.question;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 题目版本快照的<b>读侧唯一入口</b> —— 模块 15 发布固化版本、渲染试卷、判卷都走它。
 *
 * <p>接口在 {@code common/}、实现在 {@code question/bank/}（约定检查 ③：
 * {@code homework} 领域不得 import {@code question} 领域）。
 * ⚠ {@code 05-工程结构.md} 模块 15 那一行写「读 {@code question} 领域 Service
 * （题目版本快照）」——<b>照字面做会被检查 ③ 拦下</b>，实际必须经本接口。
 * 该不一致已登记（F-77）。
 *
 * <h2>为什么有三个方法，而 04-实施计划只点名了一个</h2>
 * <p>04 §B 模块 10「对外产出」只写了 {@code snapshot(questionId)}。
 * 另两个是增补，各有不可回避的理由：
 * <ul>
 *   <li>{@link #snapshot(Collection)} —— 一份作业几十道题，逐题点查就是 N 次往返；
 *   <li>{@link #read(Long, int)} —— PRD F3-2 规则 6：「所有消费方读题一律按
 *       {@code question_id + 指定 version} 读版本快照，<b>绝不读 current_version 兜底</b>」。
 *       不给这个方法，模块 15 只能自己去查版本表 —— 那就是第二份实现，
 *       而它一旦漏了「按指定 version 读」这一条，表现是<b>学生看到的题干和当初布置的不一样</b>，
 *       接口 200、字段齐全、内容错。
 * </ul>
 */
public interface QuestionVersionService {

    /**
     * 题目当前版本号，供模块 15 发布时固化 {@code hw_homework_question.question_version}。
     *
     * @return 当前版本号；题目不存在 / 已删除 / 跨租户时 {@code null}
     */
    Integer snapshot(Long questionId);

    /**
     * 批量版。
     *
     * @return 只含查到的题目；<b>缺失的键 = 该题不可用</b>，调用方按 30008 / 30006 处置
     */
    Map<Long, Integer> snapshot(Collection<Long> questionIds);

    /**
     * 读 {@code (question_id, version)} 的不可变快照。
     *
     * @throws com.edumatrix.common.response.BizException {@code 30007} 版本不存在
     */
    QuestionSnapshot read(Long questionId, int version);

    /**
     * 不可变快照。
     *
     * @param content       题干/选项/blankCount/childOrder
     * @param correctAnswer 标准答案（材料题父题为 {@code null}）
     */
    record QuestionSnapshot(Long questionId, int version, QuestionType type,
                            JsonNode content, JsonNode correctAnswer,
                            String analysis, BigDecimal scoreDefault) {
    }
}
