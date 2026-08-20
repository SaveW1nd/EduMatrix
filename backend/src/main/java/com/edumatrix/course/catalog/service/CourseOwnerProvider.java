package com.edumatrix.course.catalog.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * 批量版：一条 {@code selectBatchIds} 代替 N 次点查。
     *
     * <p>模块 11 的接口 38 单次最多 500 个 {@code resourceIds}，走默认实现就是 500 次往返 ——
     * 慢，但<b>不报错</b>。覆写的理由只有性能，语义与逐个查逐字相同。
     */
    @Override
    public Map<Long, Long> ownerNodeIdsOf(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> owners = new HashMap<>();
        for (CrsCourse course : courseMapper.selectBatchIds(resourceIds)) {
            if (course.getOwnerNodeId() != null) {
                owners.put(course.getId(), course.getOwnerNodeId());
            }
        }
        return owners;
    }
}
