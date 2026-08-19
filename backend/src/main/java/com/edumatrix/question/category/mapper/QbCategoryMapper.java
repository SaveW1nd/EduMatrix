package com.edumatrix.question.category.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.question.category.entity.QbCategory;

/**
 * {@code qb_category}。租户条件由插件注入，这里一个字不写（契约 §2.9）。
 */
@Mapper
public interface QbCategoryMapper extends BaseMapper<QbCategory> {

    /**
     * 每个分类的<b>直属</b>题目数（03-04 §1.1：不含子孙节点，材料题只计父题）。
     *
     * <p>一次查完再在内存里拼树 —— 逐节点点查是 N 次往返，而分类树整棵返回。
     */
    @Select("SELECT category_id AS categoryId, COUNT(*) AS questionCount FROM qb_question "
            + "WHERE parent_id = 0 AND deleted_at = 0 GROUP BY category_id")
    List<CategoryCountRow> countDirectQuestions();

    /** 该分类下是否还有未删除的题目（含材料题子题）—— 30004 的判据之一。 */
    @Select("SELECT COUNT(*) FROM qb_question WHERE category_id = #{categoryId} AND deleted_at = 0")
    int countQuestionsIn(@Param("categoryId") Long categoryId);

    /** {@link #countDirectQuestions} 的行。 */
    class CategoryCountRow {
        private Long categoryId;
        private Integer questionCount;

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
        }

        public Integer getQuestionCount() {
            return questionCount;
        }

        public void setQuestionCount(Integer questionCount) {
            this.questionCount = questionCount;
        }
    }
}
