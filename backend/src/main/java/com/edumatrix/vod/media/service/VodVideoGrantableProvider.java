package com.edumatrix.vod.media.service;

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
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.mapper.VodVideoMapper;

/**
 * {@link ResourceType#VIDEO} 的可授权清单提供方（{@code resource_type = 3}，03-02 §9.1）。
 *
 * <p>可见性谓词与 03-03 §7.3 媒资列表<b>共用</b> {@link VodVideoVisibilityPredicate}；
 * <b>本类不做任何权限判定</b>，同 {@code CourseGrantableProvider}。
 *
 * <h2>为什么不按 {@code status} 过滤（含「转码中」的媒资）</h2>
 * <p>§9.1 参数表没有 {@code status}，契约 §2.5 规则 12 也明写资源停用时授权行照样保留、
 * 可用性由<b>使用侧</b>按资源状态拒绝。转码中的视频同理：授权是<b>提前铺好权限</b>，
 * 转码完成后学员立刻能看，不需要有人回来补授一遍。
 * {@code status} 照 §9.1 放进 {@code extra}，由前端标注。
 *
 * <p>另有一层：题目 / 视频<b>不得授权给学生节点</b>（契约 §2.5 规则 11 → {@code 10308}），
 * 那是<b>目标侧</b>的判定，落在接口 38 的第 5 条校验上，与本清单无关 ——
 * 视频可以正常授给管理员 / 教师节点，用于备课与编排课时。
 */
@Component
public class VodVideoGrantableProvider implements GrantableResourceProvider {

    private final VodVideoMapper videoMapper;

    public VodVideoGrantableProvider(VodVideoMapper videoMapper) {
        this.videoMapper = videoMapper;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.VIDEO;
    }

    @Override
    public PageResult<GrantableResourceItem> page(GrantableResourceQuery query) {
        LambdaQueryWrapper<VodVideo> wrapper = VodVideoVisibilityPredicate.apply(
                new LambdaQueryWrapper<>(), query.getMyNodeId(),
                query.getRegrantableIds(), query.getSource());
        if (wrapper == null) {
            return PageResult.empty();
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(VodVideo::getVideoName, query.getKeyword().trim());
        }
        wrapper.orderByDesc(VodVideo::getCreateTime).orderByDesc(VodVideo::getId);

        IPage<VodVideo> page = videoMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        List<GrantableResourceItem> list = new ArrayList<>(page.getRecords().size());
        for (VodVideo video : page.getRecords()) {
            GrantableResourceItem item = new GrantableResourceItem();
            item.setResourceId(video.getId());
            item.setResourceName(video.getVideoName());
            item.setOwnerNodeId(video.getOwnerNodeId());
            item.setSource(query.getMyNodeId().equals(video.getOwnerNodeId())
                    ? GrantableResourceItem.SOURCE_OWNED : GrantableResourceItem.SOURCE_GRANTED);
            item.put("duration", video.getDuration())
                    .put("status", video.getStatus())
                    .put("sizeBytes", video.getSizeBytes());
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
        for (VodVideo video : videoMapper.selectBatchIds(resourceIds)) {
            names.put(video.getId(), video.getVideoName());
        }
        return names;
    }
}
