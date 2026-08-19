package com.edumatrix.course.catalog.service;

import org.springframework.stereotype.Component;

import com.edumatrix.common.resource.ResourceOwnerProvider;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.mapper.CrsCourseMapper;

/**
 * {@link ResourceType#COURSE} 的归属提供方（{@code resource_type = 1}）。
 *
 * <p>三类受管资源各注册一个：视频（{@code =3}）由模块 09、题目（{@code =2}）由模块 10 补。
 * 在那之前问它们的归属会<b>响亮失败</b>（{@code ResourceOwnerChecker} 抛
 * {@code IllegalStateException}），而不是静默返回 {@code false}。
 *
 * <p>本类<b>只回答归属是谁，不做任何权限判定</b> —— 判定在
 * {@code common/resource/ResourceOwnerChecker}，那样三类资源口径必然一致。
 */
@Component
public class CourseOwnerProvider implements ResourceOwnerProvider {

    private final CrsCourseMapper courseMapper;

    public CourseOwnerProvider(CrsCourseMapper courseMapper) {
        this.courseMapper = courseMapper;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.COURSE;
    }

    @Override
    public Long ownerNodeIdOf(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        // 租户条件由插件注入；deleted_at = 0 由 @TableLogic 自动追加
        CrsCourse course = courseMapper.selectById(resourceId);
        return course == null ? null : course.getOwnerNodeId();
    }
}
