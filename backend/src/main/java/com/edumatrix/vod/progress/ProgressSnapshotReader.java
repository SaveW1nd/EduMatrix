package com.edumatrix.vod.progress;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.edumatrix.vod.progress.entity.VodWatchProgress;
import com.edumatrix.vod.progress.mapper.VodWatchProgressMapper;

/**
 * 进度快照读取：<b>落库值与 Redis 缓冲取较大值</b>（03-03 §8.1 / §8.3.2）。
 *
 * <p>模块 12 的 play-auth 响应要回传这三个数；<b>写入方是模块 13</b>（尚未开工，
 * 故当前一律读到 0——这不是缺陷，是模块 13 还没写）。
 *
 * <p><b>Redis 键是模块 13 的</b>（{@code vod:hb:{studentId}:{lessonId}}），
 * 本类<b>只读不写</b>。键名常量放在这里是为了让模块 13 直接复用——
 * 04 §B 模块 12「对外产出」逐字：「模块 13 的心跳规则 1 直接读这些键，不要另建一套」。
 */
@Component
public class ProgressSnapshotReader {

    /** 模块 13 的心跳缓冲键前缀。 */
    public static final String HEARTBEAT_KEY_PREFIX = "vod:hb:";

    public static final String FIELD_WATCHED_DURATION = "watchedDuration";
    public static final String FIELD_MAX_POSITION = "maxPosition";
    public static final String FIELD_WATCH_STATUS = "watchStatus";

    private final VodWatchProgressMapper progressMapper;
    private final StringRedisTemplate redis;

    public ProgressSnapshotReader(VodWatchProgressMapper progressMapper, StringRedisTemplate redis) {
        this.progressMapper = progressMapper;
        this.redis = redis;
    }

    public static String heartbeatKey(Long studentId, Long lessonId) {
        return HEARTBEAT_KEY_PREFIX + studentId + ":" + lessonId;
    }

    /**
     * @param studentId 管理端预览时为 {@code null} —— 预览没有学生档案、也就没有进度，
     *                  直接返回全零快照（<b>不要拿预览者的 userId 去凑一个 studentId</b>，
     *                  那会把预览记录写进某个学生的进度里）
     */
    public Snapshot read(Long studentId, Long lessonId) {
        if (studentId == null || lessonId == null) {
            return Snapshot.empty();
        }
        VodWatchProgress row = progressMapper.selectOne(Wrappers.<VodWatchProgress>lambdaQuery()
                .eq(VodWatchProgress::getStudentId, studentId)
                .eq(VodWatchProgress::getLessonId, lessonId));
        int dbWatched = row == null || row.getWatchedDuration() == null ? 0 : row.getWatchedDuration();
        int dbMax = row == null || row.getMaxPosition() == null ? 0 : row.getMaxPosition();
        int status = row == null || row.getWatchStatus() == null
                ? VodWatchProgress.STATUS_NOT_STARTED : row.getWatchStatus();

        String key = heartbeatKey(studentId, lessonId);
        int bufWatched = readInt(key, FIELD_WATCHED_DURATION);
        int bufMax = readInt(key, FIELD_MAX_POSITION);
        Integer bufStatus = readIntOrNull(key, FIELD_WATCH_STATUS);

        return new Snapshot(Math.max(dbWatched, bufWatched), Math.max(dbMax, bufMax),
                bufStatus == null ? status : bufStatus);
    }

    private int readInt(String key, String field) {
        return Optional.ofNullable(readIntOrNull(key, field)).orElse(0);
    }

    private Integer readIntOrNull(String key, String field) {
        try {
            Object raw = redis.opsForHash().get(key, field);
            return raw == null ? null : Integer.valueOf(raw.toString().trim());
        } catch (RuntimeException e) {
            // Redis 不可用或值不是数字：进度快照是【展示用】的，不该让取凭证整个失败。
            // 播放本身不依赖它 —— 学生宁可看到进度显示 0，也不该因此播不了视频。
            return null;
        }
    }

    /**
     * @param watchedDuration 累计观看秒数（F-113：由前端计时器累计、模块 13 记账）
     * @param maxPosition     最远触达位置（F-113 后用途 = 识别「看到多深」，不再限制拖拽）
     * @param watchStatus     0 未开始 / 1 学习中（<b>第一版不会出现 2</b>，完播判定延后，F-113 定案四）
     */
    public record Snapshot(int watchedDuration, int maxPosition, int watchStatus) {

        static Snapshot empty() {
            return new Snapshot(0, 0, VodWatchProgress.STATUS_NOT_STARTED);
        }
    }
}
