package com.edumatrix.common.media.mapper;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.edumatrix.common.media.VideoRef;

/**
 * ⚠ <b>临时构件，模块 09 落地时随 {@code TempVideoRefReader} 一并删除。</b>
 *
 * <p>只读 {@code vod_video} 的四列，刻意不做成完整 Mapper —— 模块 09 会建
 * {@code vod/media/mapper/VodVideoMapper}，这里多一列就是将来多一处要对齐的地方。
 *
 * <p>到期标记见 {@code common/media/VideoRefReader} 类注释与
 * {@code 04-实施计划.md} §B 模块 09 的「做完什么算做完」。
 *
 * <p>租户条件由插件注入（契约 §2.9）。
 */
@Mapper
public interface VideoRefMapper {

    @Select("SELECT id, status, duration, video_name AS videoName "
            + "  FROM vod_video WHERE id = #{videoId} AND deleted_at = 0")
    VideoRef selectRef(@Param("videoId") Long videoId);

    @Select("<script>"
            + "SELECT id, status, duration, video_name AS videoName FROM vod_video "
            + " WHERE deleted_at = 0 AND id IN "
            + " <foreach collection='videoIds' item='vid' open='(' separator=',' close=')'>#{vid}</foreach>"
            + "</script>")
    List<VideoRef> selectRefs(@Param("videoIds") Collection<Long> videoIds);
}
