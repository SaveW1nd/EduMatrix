package com.edumatrix.vod.play.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 播放凭证发放审计（{@code vod_play_auth_log}）。
 *
 * <p><b>{@code event_type} 恒为 1</b>（播放凭证）。取值 2（解密密钥）随接口 29 删除而
 * <b>不再被写入</b>——DDL 与枚举<b>一字不动</b>，留着比改掉便宜，且它记录着那条路线存在过（F-112）。
 *
 * <p><b>{@code student_id} 仅 {@code viewer_type=3} 时写</b>：教师与管理员没有 {@code org_student}
 * 档案行。该列在 DDL 里是 NULL 可空的，正是为这件事留的。
 */
@TableName("vod_play_auth_log")
public class VodPlayAuthLog {

    /**
     * <b>不继承 {@code BaseEntity}</b>：本表是日志表，DDL 里<b>没有 {@code create_by} /
     * {@code update_by} / {@code remark}</b> 三列（契约第 4 节对日志表的例外）。
     * 继承会让 MyBatis-Plus 把它们拼进 INSERT，实测直接
     * {@code Unknown column 'create_by' in 'field list'}。
     * 与 {@code auth/entity/AuthLoginLog} 同型。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Integer eventType;
    private Long viewerUserId;
    private Integer viewerType;
    private Long studentId;
    private Long lessonId;
    private Long videoId;
    private String authToken;
    private LocalDateTime expireTime;
    private String clientIp;

    public Integer getEventType() {
        return eventType;
    }

    public void setEventType(Integer eventType) {
        this.eventType = eventType;
    }

    public Long getViewerUserId() {
        return viewerUserId;
    }

    public void setViewerUserId(Long viewerUserId) {
        this.viewerUserId = viewerUserId;
    }

    public Integer getViewerType() {
        return viewerType;
    }

    public void setViewerType(Integer viewerType) {
        this.viewerType = viewerType;
    }

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

    public Long getVideoId() {
        return videoId;
    }

    public void setVideoId(Long videoId) {
        this.videoId = videoId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
