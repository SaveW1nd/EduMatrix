package com.edumatrix.common.course;

/**
 * 课时可见性判定 —— 模块 12（播放凭证）/ 13（心跳）/ 14（学生端）共用。
 *
 * <p>接口在 {@code common/}、实现在 {@code course/catalog/}，消费方按接口注入
 * （与 {@code common/account/PasswordHasher} + {@code auth/session/AuthAccountProvider} 同型）。
 *
 * <p>口径来自 {@code 04-实施计划.md} §B 模块 08「对外产出」第一行：
 * <b>存在、未删除、{@code status=1}、类型匹配</b>，否则 {@code 20014}。
 * 与 03-03 §0.3 的 {@code 20014} 说明逐字一致：「课时不存在、已删除、{@code status=0} 隐藏、
 * 或<b>课时类型与接口不匹配</b>」。
 *
 * <p><b>它不判授权</b>：「该学生节点被授权该课程」是 {@code 20013}，
 * 由 {@code common/grant/ResourceGrantReader} 回答。两条判定分属两个码，
 * 模块 12 的校验链（03-03 §8.1）是「课时校验 → 授权校验 → 视频校验」三步，
 * 本接口只负责第一步。合并会让「未授权」被报成「课时不存在」，前端提示就错了。
 */
public interface LessonVisibilityChecker {

    /** {@code crs_lesson.lesson_type}：1 视频。 */
    int LESSON_TYPE_VIDEO = 1;

    /** {@code crs_lesson.lesson_type}：2 图文。 */
    int LESSON_TYPE_MATERIAL = 2;

    /**
     * 断言课时可见；不通过抛 {@code 20014}。
     *
     * @param lessonId 课时 ID
     * @return 可见课时的窄视图
     */
    VisibleLesson assertVisible(Long lessonId);

    /**
     * 断言课时可见<b>且</b>类型匹配；不通过抛 {@code 20014}。
     *
     * @param expectedLessonType {@link #LESSON_TYPE_VIDEO} 或 {@link #LESSON_TYPE_MATERIAL}
     */
    VisibleLesson assertVisible(Long lessonId, int expectedLessonType);

    /**
     * 可见课时的窄视图。
     *
     * @param id        课时 ID
     * @param courseId  所属课程 ID（授权判定的入参 —— 授权挂在<b>课程</b>上，不挂课时）
     * @param chapterId 所属章节 ID
     * @param lessonType 1 视频 2 图文
     * @param videoId   视频课时的媒资 ID，图文课时为 {@code null}
     * @param contentId 图文课时的资料 ID（{@code crs_lesson.content_id}），视频课时为 {@code null}
     * @param duration  时长（秒），图文恒 0
     * @param isFreePreview 是否免费试看：0 否 1 是
     */
    record VisibleLesson(Long id, Long courseId, Long chapterId, Integer lessonType,
                         Long videoId, Long contentId, Integer duration, Integer isFreePreview) {
    }
}
