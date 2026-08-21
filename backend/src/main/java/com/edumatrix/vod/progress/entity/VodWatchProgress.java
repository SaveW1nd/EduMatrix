package com.edumatrix.vod.progress.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.BaseEntity;

/**
 * 学习进度（{@code vod_watch_progress}）—— 学生 × 课时的聚合态。
 *
 * <p><b>本表由模块 13 拥有并写入；模块 12 只读</b>（play-auth 响应要回传
 * {@code maxPosition} / {@code watchedDuration} / {@code watchStatus} 三个快照字段）。
 * <b>实体与 Mapper 放在 {@code vod/progress/} 而不是 {@code vod/play/}，就是为了让模块 13 直接复用</b>
 * ——同一张表两份实现是约定检查⑥ 点名要防的形态，而检查⑥ 只看一个目录、拦不住跨目录重复。
 *
 * <p><b>F-113 之后 {@code max_position} 换了用途</b>：不再限制拖拽（拖拽已放开），
 * 而是<b>第一版唯一能反映「看到多深」的数据</b>——与 {@code watched_duration} 配合识别
 * 「反复看开头刷时长」。冻结基线里那句「禁快进：仅允许回看 ≤ 此值区间」已失效，
 * 见 02-数据库设计 §4.2.7 的退役注记（基线原文不改，改它会变 Flyway 校验和）。
 */
@TableName("vod_watch_progress")
public class VodWatchProgress extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 未开始。 */
    public static final int STATUS_NOT_STARTED = 0;

    private Long studentId;
    private Long lessonId;
    private Long courseId;
    private Integer watchedDuration;
    private Integer maxPosition;
    private Integer watchStatus;
    private LocalDateTime completeTime;
    private LocalDateTime lastHeartbeatTime;

    public Long getStudentId() {
        return studentId;
    }

    public void setStudentId(Long studentId) {
        this.studentId = studentId;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public void setLessonId(Long lessonId) {
        this.lessonId = lessonId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Integer getWatchedDuration() {
        return watchedDuration;
    }

    public void setWatchedDuration(Integer watchedDuration) {
        this.watchedDuration = watchedDuration;
    }

    public Integer getMaxPosition() {
        return maxPosition;
    }

    public void setMaxPosition(Integer maxPosition) {
        this.maxPosition = maxPosition;
    }

    public Integer getWatchStatus() {
        return watchStatus;
    }

    public void setWatchStatus(Integer watchStatus) {
        this.watchStatus = watchStatus;
    }

    public LocalDateTime getCompleteTime() {
        return completeTime;
    }

    public void setCompleteTime(LocalDateTime completeTime) {
        this.completeTime = completeTime;
    }

    public LocalDateTime getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void setLastHeartbeatTime(LocalDateTime lastHeartbeatTime) {
        this.lastHeartbeatTime = lastHeartbeatTime;
    }
}
