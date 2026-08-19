package com.edumatrix.vod.media.service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.media.VideoRef;
import com.edumatrix.common.media.VideoRefReader;
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.mapper.VodVideoMapper;

/**
 * {@code common/media/VideoRefReader} 的<b>正式实现</b> —— 模块 09 接管模块 08 的临时构件。
 *
 * <h2>到期动作已完成</h2>
 * <p>模块 08 为了让「关联视频 {@code status=1} 时置课时可见返回 {@code 20008}」这条验收
 * 可测，先开了一个临时实现 {@code common/media/TempVideoRefReader} +
 * {@code common/media/mapper/VideoRefMapper}。本提交<b>把它们删掉了</b>，
 * 由本类接管（04-实施计划.md §B 模块 09「做完什么算做完」第 1 条）。
 * {@code CourseSpiWiringIT#videoRefReaderHasExactlyOneImplementation} 按 <b>Bean 数量</b>
 * 断言恰为 1 —— 只加不删会立刻红，删了没加则上下文起不来。
 *
 * <h2>本类<b>不含任何业务判定</b></h2>
 * <p>{@code status} 该怎么解读是调用方的事（模块 08 的 {@code LessonService}、
 * 本模块的 {@link VideoStatusChecker}）。一旦这里出现 {@code if (status == ...)}
 * 就说明判定被复制到了第二处 —— 05-工程结构.md §E1 的两条纪律之一。
 */
@Component
public class VodVideoRefProvider implements VideoRefReader {

    private final VodVideoMapper videoMapper;

    public VodVideoRefProvider(VodVideoMapper videoMapper) {
        this.videoMapper = videoMapper;
    }

    @Override
    public VideoRef read(Long videoId) {
        if (videoId == null) {
            return null;
        }
        return toRef(videoMapper.selectById(videoId));
    }

    @Override
    public List<VideoRef> readAll(Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }
        return videoMapper.selectList(new LambdaQueryWrapper<VodVideo>()
                        .in(VodVideo::getId, videoIds))
                .stream().map(VodVideoRefProvider::toRef).toList();
    }

    /** 逻辑删除与跨租户由 {@code @TableLogic} 与租户插件挡掉，这里只做形状转换。 */
    private static VideoRef toRef(VodVideo video) {
        return video == null ? null
                : new VideoRef(video.getId(), video.getStatus(),
                        video.getDuration(), video.getVideoName());
    }
}
