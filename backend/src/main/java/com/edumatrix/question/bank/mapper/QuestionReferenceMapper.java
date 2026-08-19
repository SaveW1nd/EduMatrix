package com.edumatrix.question.bank.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.edumatrix.question.bank.vo.ReferencingHomeworkVO;

/**
 * 「这道题被哪些作业引用了」—— <b>只读</b>，模块 10 对 {@code hw_} 两张表的唯一触点。
 *
 * <p>04-实施计划.md §B 模块 10 的「涉及表」写明：{@code hw_homework_question}
 * 是<b>只读</b>（引用校验）。写侧归模块 15。<b>本接口不得出现任何
 * {@code @Insert / @Update / @Delete}</b> —— 理由与 {@code system/log/mapper}
 * 那条（约定检查 ⑥）同源：同一张表两份写实现，在本项目没有任何自动守卫。
 *
 * <h2>F-76 定案：什么才算「被引用」</h2>
 * <p>03-04 §0.3 对 30001 写「任意<b>未删除</b>的 {@code hw_homework_question} 引用
 * （不限作业状态）」，对 30005 写「任意<b>状态</b>作业的引用」——
 * 两处措辞不同，且都<b>没说作业本身被逻辑删除时算不算</b>。契约与 PRD 均无。
 *
 * <p>定案：{@code hw_homework_question.deleted_at = 0} <b>且</b>
 * {@code hw_homework.deleted_at = 0} 才算引用。反向口径（只看明细行）会让题库
 * 单调累积一批「删不掉也停不了」的题，而<b>那批题没有任何界面能处理</b> ——
 * 与 F-47（教师离职后课程无人接手）、F-65（放弃重传后媒资永久停在转码中）
 * 是同一个形状：链没闭合。不制造第三处。
 *
 * <p><b>「不限作业状态」这一半照原文实现</b>：草稿、已发布、已截止、已撤回
 * 四种状态一律算引用 —— 已撤回的作业还能重新发布（30009 说明），
 * 那时它固化的版本必须还在。
 */
@Mapper
public interface QuestionReferenceMapper {

    /**
     * 引用了这批题目（材料题请传父题 + 全部子题 ID）的作业，去重、按引用先后返回。
     *
     * <p>走 {@code hw_homework_question.idx_question_id}（DDL 注释逐字：
     * 「按题目反查被哪些作业引用（停用校验，错误码 30001）」）。
     */
    @Select("""
            <script>
            SELECT DISTINCT h.id AS homeworkId, h.homework_name AS homeworkName,
                   h.status AS homeworkStatus, h.deadline AS deadline
              FROM hw_homework_question q
              JOIN hw_homework h ON h.id = q.homework_id AND h.deleted_at = 0
             WHERE q.deleted_at = 0
               AND q.question_id IN
               <foreach collection="questionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY h.id
            </script>
            """)
    List<ReferencingHomeworkVO> selectReferencingHomeworks(
            @Param("questionIds") List<Long> questionIds);
}
