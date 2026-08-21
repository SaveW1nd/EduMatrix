package com.edumatrix.vod.play;

/**
 * 模块 12 的 Redis 键。<b>模块 13 的心跳规则 1 直接读这些键，不要另建一套</b>。
 */
public final class PlayAuthKeys {

    /** {@code play:auth:{authToken}} → Hash{studentId, lessonId, videoId, sessionId}，TTL 300s。 */
    public static final String PLAY_AUTH_PREFIX = "play:auth:";

    public static final String FIELD_STUDENT_ID = "studentId";
    public static final String FIELD_LESSON_ID = "lessonId";
    public static final String FIELD_VIDEO_ID = "videoId";
    public static final String FIELD_SESSION_ID = "sessionId";

    public static String playAuth(String authToken) {
        return PLAY_AUTH_PREFIX + authToken;
    }

    private PlayAuthKeys() {
    }
}
