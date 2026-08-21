package com.edumatrix.vod.play.dto;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/v1/vod/play-auth} 请求体（03-03 接口 28）。 */
public class PlayAuthReq {

    @NotNull(message = "lessonId 不能为空")
    private Long lessonId;

    public Long getLessonId() {
        return lessonId;
    }

    public void setLessonId(Long lessonId) {
        this.lessonId = lessonId;
    }
}
