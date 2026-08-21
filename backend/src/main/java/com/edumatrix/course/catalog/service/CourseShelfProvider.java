package com.edumatrix.course.catalog.service;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.edumatrix.common.course.CourseShelfReader;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.mapper.CrsCourseMapper;

/**
 * {@link CourseShelfReader} 在 {@code course} 领域的唯一实现。
 */
@Component
public class CourseShelfProvider implements CourseShelfReader {

    private final CrsCourseMapper courseMapper;

    public CourseShelfProvider(CrsCourseMapper courseMapper) {
        this.courseMapper = courseMapper;
    }

    @Override
    public boolean isOnShelf(Long courseId) {
        if (courseId == null) {
            return false;
        }
        CrsCourse course = courseMapper.selectOne(Wrappers.<CrsCourse>lambdaQuery()
                .select(CrsCourse::getId, CrsCourse::getStatus)
                .eq(CrsCourse::getId, courseId));
        return course != null && course.getStatus() != null && course.getStatus() == STATUS_ON_SHELF;
    }
}
