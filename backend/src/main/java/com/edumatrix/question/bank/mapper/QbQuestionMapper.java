package com.edumatrix.question.bank.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.question.bank.entity.QbQuestion;

/**
 * {@code qb_question}。租户条件由插件注入，这里一个字不写（契约 §2.9）。
 *
 * <p>与 {@code QbQuestionVersionMapper} 的<b>区别是刻意的</b>：主表要更新
 * {@code current_version} / {@code status} / {@code category_id}，所以继承
 * {@link BaseMapper}；版本表只增不改，所以那一个<b>不继承</b>。
 */
@Mapper
public interface QbQuestionMapper extends BaseMapper<QbQuestion> {

    /**
     * <b>题目写操作的统一锁点</b>：取题目行的排他锁。
     *
     * <p>与 {@code CrsCourseMapper#lockForUpdate} 同型。修改题目（写新版本 + 回写
     * {@code current_version}）必须先取它 —— 否则两个并发编辑都读到
     * {@code current_version = 2}，都算出新版本号 3，一个撞 {@code UK(question_id, version)}
     * 失败、或者更糟：两条都写进去而 {@code current_version} 只反映其中一条。
     *
     * <p>材料题一律锁<b>父题</b>行（子题编辑也锁父题），于是同一道材料题的父子编辑串行。
     */
    @Select("SELECT id FROM qb_question WHERE id = #{questionId} AND deleted_at = 0 FOR UPDATE")
    Long lockForUpdate(@Param("questionId") Long questionId);

    /** 某节点自有（{@code owner_node_id} 严格相等）的题目 ID —— 只取父题与普通题。 */
    @Select("SELECT id FROM qb_question WHERE owner_node_id = #{nodeId} AND parent_id = 0 AND deleted_at = 0")
    List<Long> selectOwnedRootIds(@Param("nodeId") Long nodeId);
}
