package com.edumatrix.common.media;

import java.util.Collection;
import java.util.List;

/**
 * 读 {@code vod_video} 的窄能力 —— 接口在 {@code common/}，实现在提供方领域内。
 *
 * <p>实现是 {@code vod/media/service/VodVideoRefProvider}（模块 09）。
 *
 * <h2>这里曾经有一个临时实现，模块 09 已把它删掉</h2>
 * <p>模块 08 的验收标准要「关联视频 {@code status=1} 时置课时可见返回 {@code 20008}」，
 * 判定需要读 {@code vod_video.status}；而 {@code vod} 领域要到模块 09 才存在。
 * 于是先由 {@code common/media/TempVideoRefReader} +
 * {@code common/media/mapper/VideoRefMapper} 顶上。
 *
 * <p><b>换顺序不能绕过它</b>：模块 09 规则 13「媒资被未删除课时引用时不可删 →
 * {@code 20016}」要读 {@code crs_lesson}，两个模块互相需要对方的表，
 * <b>先做哪个都要开一个临时构件</b>。（模块 09 这一侧最终没有开临时构件，
 * 而是加了一个正式的跨领域 SPI {@code common/course/LessonVideoRefCounter}，见 F-62。）
 *
 * <p><b>守卫</b>：{@code CourseSpiWiringIT#videoRefReaderHasExactlyOneImplementation}
 * 按 <b>Bean 数量</b>断言本接口的实现恒为 1 —— 只加不删会立刻红，删了没加则上下文起不来。
 * <b>⚠ 这句话此前写的是「{@code VideoRefReaderUniquenessTest} 断言……」，而全库
 * 没有那个类</b>（模块 09 落地时 grep 确认）—— 一处指向不存在的类的到期标记，
 * 本身就是「以为存在」的一个小样本，已随本次接管订正（<b>F-63</b>）。
 */
public interface VideoRefReader {

    /** 单条。不存在 / 已逻辑删除 / 跨租户 → {@code null}。 */
    VideoRef read(Long videoId);

    /** 批量。返回的顺序不保证；查不到的 id 不出现在结果里。 */
    List<VideoRef> readAll(Collection<Long> videoIds);
}
