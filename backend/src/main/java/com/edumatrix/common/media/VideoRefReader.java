package com.edumatrix.common.media;

import java.util.Collection;
import java.util.List;

/**
 * 读 {@code vod_video} 的窄能力 —— 接口在 {@code common/}，实现在提供方领域内。
 *
 * <h2>⚠ 当前实现是<b>临时</b>的，模块 09 到期删除</h2>
 * <p>模块 08 的验收标准要「关联视频 {@code status=1} 时置课时可见返回 {@code 20008}」，
 * 判定需要读 {@code vod_video.status}；而 {@code vod} 领域要到模块 09 才存在。
 * 于是先由 {@link TempVideoRefReader} 顶上。
 *
 * <p><b>换顺序不能绕过它</b>：模块 09 规则 13「媒资被未删除课时引用时不可删 →
 * {@code 20016}」要读 {@code crs_lesson}，其「涉及表」也写着「只读：{@code crs_lesson}」。
 * 两个模块互相需要对方的表，<b>先做哪个都要开一个临时构件</b>。
 *
 * <p><b>到期动作</b>（已写进 {@code 04-实施计划.md} §B 模块 09 的「做完什么算做完」）：
 * 模块 09 用 {@code vod/media/service/VodVideoRefProvider} 实现本接口，
 * 并<b>删除</b> {@link TempVideoRefReader} 与 {@code common/media/mapper/VideoRefMapper}。
 * {@code VideoRefReaderUniquenessTest} 断言本接口的 Bean 数恒为 1 ——
 * 只加不删会立刻红，删了没加则启动失败。
 */
public interface VideoRefReader {

    /** 单条。不存在 / 已逻辑删除 / 跨租户 → {@code null}。 */
    VideoRef read(Long videoId);

    /** 批量。返回的顺序不保证；查不到的 id 不出现在结果里。 */
    List<VideoRef> readAll(Collection<Long> videoIds);
}
