package com.edumatrix.course.catalog.service;

import org.springframework.stereotype.Component;

import com.edumatrix.common.course.LessonVisibilityChecker;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.course.catalog.entity.CrsLesson;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;

/**
 * {@code common/course/LessonVisibilityChecker} 的实现（模块 08「对外产出」第一行）。
 *
 * <p>口径逐条对应 03-03 §0.3 的 {@code 20014} 说明：
 * 「课时<b>不存在</b>、<b>已删除</b>、{@code status=0} <b>隐藏</b>、
 * 或<b>课时类型与接口不匹配</b>」——四种情形同一个码。
 *
 * <p>「不存在 / 已删除 / 跨租户」三者在这里<b>合并成同一个结果</b>：
 * {@code selectById} 带 {@code @TableLogic} 与租户插件，三者都返回 {@code null}。
 * 这正是不暴露存在性想要的（03-03 §0.1「跨租户访问一律返回 HTTP 404，不暴露资源存在性」）。
 *
 * <p><b>它不判授权</b>：「该学生节点被授权该课程」是 {@code 20013}，
 * 模块 12 的校验链（03-03 §8.1）第二步才判它。见接口注释。
 */
@Component
public class LessonVisibilityProvider implements LessonVisibilityChecker {

    private final CrsLessonMapper lessonMapper;

    public LessonVisibilityProvider(CrsLessonMapper lessonMapper) {
        this.lessonMapper = lessonMapper;
    }

    @Override
    public VisibleLesson assertVisible(Long lessonId) {
        return assertVisible(lessonId, null);
    }

    @Override
    public VisibleLesson assertVisible(Long lessonId, int expectedLessonType) {
        return assertVisible(lessonId, Integer.valueOf(expectedLessonType));
    }

    private VisibleLesson assertVisible(Long lessonId, Integer expectedLessonType) {
        CrsLesson lesson = lessonId == null ? null : lessonMapper.selectById(lessonId);
        if (lesson == null || !lesson.isVisible()) {
            throw new BizException(ErrorCode.LESSON_NOT_FOUND_OR_INVISIBLE);
        }
        if (expectedLessonType != null && !expectedLessonType.equals(lesson.getLessonType())) {
            throw new BizException(ErrorCode.LESSON_NOT_FOUND_OR_INVISIBLE);
        }
        return new VisibleLesson(lesson.getId(), lesson.getCourseId(), lesson.getChapterId(),
                lesson.getLessonType(), lesson.getVideoId(), lesson.getContentId(),
                lesson.getDuration(), lesson.getIsFreePreview());
    }
}
