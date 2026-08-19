package com.edumatrix.common.media;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.edumatrix.common.media.mapper.VideoRefMapper;

/**
 * ⚠ <b>临时实现，模块 09 落地时删除本类与 {@code common/media/mapper/VideoRefMapper}。</b>
 *
 * <p>类名里的 {@code Temp} 是刻意的 —— 它是唯一能被 grep 到的到期标记。
 * 完整理由与到期动作见 {@link VideoRefReader} 类注释；对应的强制检查点在
 * {@code 04-实施计划.md} §B 模块 09 的「做完什么算做完」。
 *
 * <p>本类<b>不含任何业务判定</b>：{@code status} 该怎么解读是调用方（模块 08 的
 * {@code LessonService}、将来模块 12 的 {@code VideoStatusChecker}）的事。
 * 一旦这里出现 {@code if (status == ...)} 就说明判定被复制到了第二处。
 */
@Component
public class TempVideoRefReader implements VideoRefReader {

    private final VideoRefMapper videoRefMapper;

    public TempVideoRefReader(VideoRefMapper videoRefMapper) {
        this.videoRefMapper = videoRefMapper;
    }

    @Override
    public VideoRef read(Long videoId) {
        return videoId == null ? null : videoRefMapper.selectRef(videoId);
    }

    @Override
    public List<VideoRef> readAll(Collection<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }
        return videoRefMapper.selectRefs(videoIds);
    }
}
