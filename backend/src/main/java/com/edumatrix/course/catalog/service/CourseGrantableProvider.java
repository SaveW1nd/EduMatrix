package com.edumatrix.course.catalog.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.resource.GrantableResourceItem;
import com.edumatrix.common.resource.GrantableResourceProvider;
import com.edumatrix.common.resource.GrantableResourceQuery;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.mapper.CrsCourseMapper;

/**
 * {@link ResourceType#COURSE} 的可授权清单提供方（{@code resource_type = 1}，03-02 §9.1）。
 *
 * <p><b>本类不做任何权限判定</b>：{@code owner_node_id = 我} 之外能授出哪些，
 * 全部由调用方（模块 11）用 {@code ResourceOwnerChecker.canRegrant} 判完后
 * 以 {@link GrantableResourceQuery#getRegrantableIds()} 传进来。
 * 可见性谓词与 03-03 §1.1 课程列表<b>共用</b> {@link CourseVisibilityPredicate}。
 *
 * <h2>为什么不按 {@code status} 过滤</h2>
 * <p>§9.1 的参数表没有 {@code status}，而契约 §2.5 规则 12 明写：资源下架 / 停用时
 * <b>授权行一律保留</b>，可用性由资源状态在<b>使用侧</b>拒绝。
 * 也就是说「课程已下架」不等于「不能授权」—— 下架可再上架，授权在那之后自动重新生效。
 * 在这里按 {@code status} 滤掉，等于替使用侧做了一个它自己会做、且做得更准的判定，
 * 代价是运营看不到自己拥有的下架课程、也就无法为「下周重新上架」提前授权。
 * {@code status} 照 §9.1 放进 {@code extra} 里，由前端标注。
 */
@Component
public class CourseGrantableProvider implements GrantableResourceProvider {

    private final CrsCourseMapper courseMapper;

    public CourseGrantableProvider(CrsCourseMapper courseMapper) {
        this.courseMapper = courseMapper;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.COURSE;
    }

    @Override
    public PageResult<GrantableResourceItem> page(GrantableResourceQuery query) {
        LambdaQueryWrapper<CrsCourse> wrapper = CourseVisibilityPredicate.apply(
                new LambdaQueryWrapper<>(), query.getMyNodeId(),
                query.getRegrantableIds(), query.getSource());
        if (wrapper == null) {
            // 只要受授权、而一条可再下发的都没有 —— 空页，不是「不加过滤」
            return PageResult.empty();
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(CrsCourse::getCourseName, query.getKeyword().trim());
        }
        if (query.getSubject() != null && !query.getSubject().isBlank()) {
            wrapper.eq(CrsCourse::getSubject, query.getSubject().trim());
        }
        wrapper.orderByDesc(CrsCourse::getCreateTime).orderByDesc(CrsCourse::getId);

        IPage<CrsCourse> page = courseMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        List<GrantableResourceItem> list = new ArrayList<>(page.getRecords().size());
        for (CrsCourse course : page.getRecords()) {
            GrantableResourceItem item = new GrantableResourceItem();
            item.setResourceId(course.getId());
            item.setResourceName(course.getCourseName());
            item.setOwnerNodeId(course.getOwnerNodeId());
            item.setSource(query.getMyNodeId().equals(course.getOwnerNodeId())
                    ? GrantableResourceItem.SOURCE_OWNED : GrantableResourceItem.SOURCE_GRANTED);
            item.put("subject", course.getSubject())
                    .put("status", course.getStatus())
                    .put("lessonCount", course.getLessonCount())
                    .put("totalDuration", course.getTotalDuration());
            list.add(item);
        }
        return PageResult.of(page.getTotal(), list);
    }

    @Override
    public Map<Long, String> namesOf(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        // 租户条件由插件注入；deleted_at = 0 由 @TableLogic 自动追加
        Map<Long, String> names = new HashMap<>();
        for (CrsCourse course : courseMapper.selectBatchIds(resourceIds)) {
            names.put(course.getId(), course.getCourseName());
        }
        return names;
    }
}
