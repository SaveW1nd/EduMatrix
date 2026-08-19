package com.edumatrix.course.catalog.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.course.catalog.entity.CrsCourse;

/**
 * {@code crs_course}。租户条件由插件注入，这里一个字不写（契约 §2.9）。
 */
@Mapper
public interface CrsCourseMapper extends BaseMapper<CrsCourse> {

    /**
     * <b>编排写操作的统一锁点</b>：取课程行的排他锁。
     *
     * <p>章节增删改排序、课时增删改、上下架、删除课程<b>一律先取它</b>，
     * 于是同一课程的编排串行、不同课程并行。加锁对象只有一个，
     * <b>结构上不可能死锁</b>（无跨表加锁顺序）。
     *
     * <p>没有它会怎样：两个并发的排序请求可以<b>都通过</b> {@code 20018} 的集合一致性校验
     * （那一刻集合确实一致），随后交错写入 —— 最终是两次提交的混合，而<b>两个请求都返回 200</b>。
     *
     * <p>不用 {@code selectById(...).forUpdate()}：MyBatis-Plus 没有该写法，
     * 且这里只需要锁，不需要把整行读回来。
     */
    @Select("SELECT id FROM crs_course WHERE id = #{courseId} AND deleted_at = 0 FOR UPDATE")
    Long lockForUpdate(@Param("courseId") Long courseId);

    /**
     * 全量重算两个冗余列（C 定案：<b>全部未删除课时，不加 status 限定</b>）。
     *
     * <p><b>全量重算而不是增量 ±1</b> —— 理由见 {@code common/course/CourseCounterRefresher}。
     * 走 {@code idx_course_status (course_id, status)} 的前缀。
     */
    @Update("UPDATE crs_course SET "
            + "  lesson_count   = (SELECT COUNT(*) FROM crs_lesson "
            + "                     WHERE course_id = #{courseId} AND deleted_at = 0), "
            + "  total_duration = (SELECT COALESCE(SUM(duration), 0) FROM crs_lesson "
            + "                     WHERE course_id = #{courseId} AND deleted_at = 0) "
            + "WHERE id = #{courseId} AND deleted_at = 0")
    int recountRedundant(@Param("courseId") Long courseId);
}
