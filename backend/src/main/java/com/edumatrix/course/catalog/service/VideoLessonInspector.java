package com.edumatrix.course.catalog.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.media.VideoRef;
import com.edumatrix.common.media.VideoRefReader;
import com.edumatrix.common.response.BizException;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;

/**
 * 视频课时与 {@code vod_video} 的关联校验 —— <b>{@code 20003} 与 {@code 20008} 的分工在这里</b>。
 *
 * <table border="1">
 *   <caption>三个码的触发场景（00-通用约定 §9.3 / 03-03 §0.3 逐条）</caption>
 *   <tr><th>码</th><th>含义</th><th>触发它的接口（全集）</th></tr>
 *   <tr><td>{@code 20019}</td><td>课时类型与关联资源<b>参数</b>不匹配（该传的没传）</td>
 *       <td>接口 14 / 15</td></tr>
 *   <tr><td>{@code 20008}</td><td>关联视频<b>不存在或状态不可用</b></td>
 *       <td>接口 14 / 15（{@link LessonService}）</td></tr>
 *   <tr><td>{@code 20003}</td><td>视频转码未完成 —— <b>只用于上架与发放播放凭证</b></td>
 *       <td>接口 6（本类的 {@link #assertAllVideosTranscoded}）、
 *           模块 12 的 §8.1 / §8.2</td></tr>
 * </table>
 *
 * <p><b>不得用 {@code 20003} 表达「关联视频状态不可用」</b>（模块 08「禁止事项」逐字）。
 * 一句话口径：{@code 20019} 判参数形状，{@code 20008}/{@code 20009} 判被指向的资源本身，
 * {@code 20003} 只回答「这门课能不能放出去」。
 *
 * <p>{@code vod_video.status} 的取值语义查的是原文，不是直觉：
 * <b>0 上传中 / 1 转码中 / 2 正常 / 3 转码失败 / 9 禁用</b>
 * （03-03 §7 导语、附「状态机速查」、契约 §4）。附录还写着<b>「仅 2 可挂课时、可发放播放凭证」</b>。
 * 所以 PRD F2-1 验收标准里的「关联视频 {@code status=1}」是<b>转码中</b>。
 */
@Service
public class VideoLessonInspector {

    private final CrsLessonMapper lessonMapper;
    private final VideoRefReader videoRefReader;

    public VideoLessonInspector(CrsLessonMapper lessonMapper, VideoRefReader videoRefReader) {
        this.lessonMapper = lessonMapper;
        this.videoRefReader = videoRefReader;
    }

    /**
     * 接口 6 上架前置（§1.6 规则 2 后半句）：<b>全部</b>视频课时关联视频必须 {@code status=2}。
     *
     * <p>失败文案点名到具体课时 —— §1.6 的失败响应示例逐字：
     * 「课时[1.1.2 集合的表示（视频）]关联视频尚未转码完成，不可上架」。
     */
    public void assertAllVideosTranscoded(Long courseId) {
        List<CrsLessonMapper.VideoLessonRow> rows = lessonMapper.selectVideoLessons(courseId);
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, VideoRef> refs = new LinkedHashMap<>();
        for (VideoRef ref : videoRefReader.readAll(rows.stream()
                .map(CrsLessonMapper.VideoLessonRow::getVideoId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList())) {
            refs.put(ref.id(), ref);
        }
        for (CrsLessonMapper.VideoLessonRow row : rows) {
            VideoRef ref = row.getVideoId() == null ? null : refs.get(row.getVideoId());
            if (ref == null || !ref.isNormal()) {
                throw new BizException(ErrorCode.VIDEO_TRANSCODE_NOT_FINISHED,
                        "视频转码未完成：课时[" + row.getLessonName() + "]关联视频尚未转码完成，不可上架");
            }
        }
    }
}
