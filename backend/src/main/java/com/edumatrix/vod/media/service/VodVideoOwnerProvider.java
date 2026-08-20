package com.edumatrix.vod.media.service;

import org.springframework.stereotype.Component;

import com.edumatrix.common.resource.ResourceOwnerProvider;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.mapper.VodVideoMapper;

/**
 * 视频（{@code resource_type=3}）的归属提供方 —— 模块 09 补齐三类受管资源的最后一类
 * （04-实施计划.md §B 模块 09「做完什么算做完」第 3 条）。
 *
 * <p><b>在本类之前，{@code ResourceOwnerChecker} 对 {@code VIDEO} 是抛
 * {@code IllegalStateException}，不是返回 {@code false}</b> —— 那是有意的：
 * 静默返回 false 会让模块 11 的授权引擎判定「你不是 owner」而接口 200、字段齐全、结果错，
 * 正是本项目 1 号失败模式。
 *
 * <p>本类<b>只回答「归属是谁」，不做任何权限判定</b>（05-工程结构.md §E1 纪律 1）。
 * 判定在 {@code ResourceOwnerChecker}，那样三类资源的口径必然一致。
 */
@Component
public class VodVideoOwnerProvider implements ResourceOwnerProvider {

    private final VodVideoMapper videoMapper;

    public VodVideoOwnerProvider(VodVideoMapper videoMapper) {
        this.videoMapper = videoMapper;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.VIDEO;
    }

    @Override
    public Long ownerNodeIdOf(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        VodVideo video = videoMapper.selectById(resourceId);
        return video == null ? null : video.getOwnerNodeId();
    }
}
