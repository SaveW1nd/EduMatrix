package com.edumatrix.vod.play;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.edumatrix.common.course.CourseShelfReader;
import com.edumatrix.common.course.LessonVisibilityChecker;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.media.VideoRef;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.student.StudentEnrollmentReader;
import com.edumatrix.vod.media.service.VideoStatusChecker;
import com.edumatrix.vod.play.service.PlayAuthChainService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 五步校验链的单测 —— <b>它是 04 §B 模块 12 完工判据的承载物</b>。
 *
 * <p>完工判据（本轮改写）：<b>把五步里任意一步改成恒真，必须有用例变红</b>。
 * 原判据「§8.1 与 §8.2 的校验链必须逐条相同」已失效——§8.2 上一轮成了墓碑（接口 29 删除），
 * <b>没有第二条链可比</b>。
 *
 * <p>下面 {@code 第N步} 每个 {@code @Nested} 至少有一条<b>只有那一步能让它红</b>的用例，
 * 对应变异 M38~M42。<b>第 1 步尤其单独跑</b>：它是本轮才补进 04 的，最可能没有用例守着。
 */
@DisplayName("模块 12 · 播放凭证五步校验链")
class PlayAuthChainServiceTest {

    private static final long STUDENT_NODE = 1001L;
    private static final long ADMIN_NODE = 2001L;
    private static final long LESSON_ID = 5001L;
    private static final long COURSE_ID = 6001L;
    private static final long VIDEO_ID = 7001L;
    private static final long STUDENT_ID = 8001L;

    private StudentEnrollmentReader enrollmentReader;
    private LessonVisibilityChecker lessonChecker;
    private ResourceGrantReader grantReader;
    private ResourceOwnerChecker ownerChecker;
    private CourseShelfReader courseShelfReader;
    private VideoStatusChecker videoStatusChecker;
    private PlayAuthChainService chain;

    /** 六个依赖全部放行的「一切正常」基线；每个用例只推翻其中一个。 */
    @BeforeEach
    void setUp() {
        enrollmentReader = mock(StudentEnrollmentReader.class);
        lessonChecker = mock(LessonVisibilityChecker.class);
        grantReader = mock(ResourceGrantReader.class);
        ownerChecker = mock(ResourceOwnerChecker.class);
        courseShelfReader = mock(CourseShelfReader.class);
        videoStatusChecker = mock(VideoStatusChecker.class);
        chain = new PlayAuthChainService(enrollmentReader, lessonChecker, grantReader,
                ownerChecker, courseShelfReader, videoStatusChecker);

        when(enrollmentReader.byNodeId(STUDENT_NODE))
                .thenReturn(new StudentEnrollmentReader.Enrollment(STUDENT_ID, 0));
        when(lessonChecker.assertVisible(eq(LESSON_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new LessonVisibilityChecker.VisibleLesson(
                        LESSON_ID, COURSE_ID, 4001L, 1, VIDEO_ID, null, 600, 0));
        when(grantReader.hasGrant(ResourceType.COURSE, COURSE_ID, STUDENT_NODE)).thenReturn(true);
        when(ownerChecker.canUse(ResourceType.COURSE, COURSE_ID, ADMIN_NODE)).thenReturn(true);
        when(courseShelfReader.isOnShelf(COURSE_ID)).thenReturn(true);
        when(videoStatusChecker.assertPlayable(VIDEO_ID))
                .thenReturn(new VideoRef(VIDEO_ID, 2, 600, "trailer.mp4"));
    }

    private PlayAuthChainService.VerifiedPlay verifyAsStudent() {
        return chain.verify(STUDENT_NODE, 3, LESSON_ID);
    }

    private PlayAuthChainService.VerifiedPlay verifyAsAdmin() {
        return chain.verify(ADMIN_NODE, 1, LESSON_ID);
    }

    @Test
    @DisplayName("一切正常时五步全过，学生路径带回 studentId")
    void happyPathStudent() {
        PlayAuthChainService.VerifiedPlay play = verifyAsStudent();
        assertThat(play.studentId()).isEqualTo(STUDENT_ID);
        assertThat(play.courseId()).isEqualTo(COURSE_ID);
        assertThat(play.videoId()).isEqualTo(VIDEO_ID);
        assertThat(play.isStudent()).isTrue();
    }

    // =========================================================================
    // 第 1 步 · 学生在读 —— M38
    // =========================================================================
    @Nested
    @DisplayName("第 1 步 学生在读（M38）")
    class Step1Enrollment {

        @Test
        @DisplayName("退课学生取不到凭证 —— 撤销之外唯一的失效手段，04 原文的三步整个漏掉了它")
        void quitStudentRejected() {
            when(enrollmentReader.byNodeId(STUDENT_NODE))
                    .thenReturn(new StudentEnrollmentReader.Enrollment(STUDENT_ID, 1));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COURSE_NOT_GRANTED);
        }

        @Test
        @DisplayName("归档学生同样取不到（status 白名单：只有 0 放行）")
        void archivedStudentRejected() {
            when(enrollmentReader.byNodeId(STUDENT_NODE))
                    .thenReturn(new StudentEnrollmentReader.Enrollment(STUDENT_ID, 2));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("没有学籍档案行的账号走学生路径也取不到")
        void missingEnrollmentRejected() {
            when(enrollmentReader.byNodeId(STUDENT_NODE)).thenReturn(null);
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("⚠ 第 1 步失败必须【当场终止】，后面四步一步都不许查")
        void step1FailureShortCircuits() {
            when(enrollmentReader.byNodeId(STUDENT_NODE))
                    .thenReturn(new StudentEnrollmentReader.Enrollment(STUDENT_ID, 1));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class);
            // 只断「抛了异常」证不了它是被第 1 步拦下的 —— 后面任何一步都能抛同一个码。
            verify(lessonChecker, never()).assertVisible(anyLong(), org.mockito.ArgumentMatchers.anyInt());
            verify(grantReader, never()).hasGrant(any(), anyLong(), anyLong());
            verify(courseShelfReader, never()).isOnShelf(anyLong());
            verify(videoStatusChecker, never()).assertPlayable(anyLong());
        }
    }

    // =========================================================================
    // 第 2 步 · 课时校验 —— M39 / M44
    // =========================================================================
    @Nested
    @DisplayName("第 2 步 课时校验（M39 / M44）")
    class Step2Lesson {

        @Test
        @DisplayName("课时不可见 → 20014，且【不并进 20013】（F-48 定案：两种回答保持可区分）")
        void invisibleLessonRejectedWith20014() {
            when(lessonChecker.assertVisible(eq(LESSON_ID), org.mockito.ArgumentMatchers.anyInt()))
                    .thenThrow(new BizException(ErrorCode.LESSON_NOT_FOUND_OR_INVISIBLE));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LESSON_NOT_FOUND_OR_INVISIBLE);
        }

        @Test
        @DisplayName("⚠ M44：管理端预览【也要】走第 2 步 —— 预览只允许跳第 1、3 两步")
        void adminPreviewStillRunsLessonCheck() {
            when(lessonChecker.assertVisible(eq(LESSON_ID), org.mockito.ArgumentMatchers.anyInt()))
                    .thenThrow(new BizException(ErrorCode.LESSON_NOT_FOUND_OR_INVISIBLE));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsAdmin)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LESSON_NOT_FOUND_OR_INVISIBLE);
        }

        @Test
        @DisplayName("必须按【视频课时】校验，不能只判存在（图文课时没有 videoId，放过去会 NPE 在第 5 步）")
        void lessonTypeMustBeVideo() {
            verifyAsStudent();
            verify(lessonChecker).assertVisible(LESSON_ID, LessonVisibilityChecker.LESSON_TYPE_VIDEO);
        }
    }

    // =========================================================================
    // 第 3 步 · 授权校验 —— M40
    // =========================================================================
    @Nested
    @DisplayName("第 3 步 授权校验（M40）")
    class Step3Grant {

        @Test
        @DisplayName("学生节点没被授权该课程 → 20013")
        void ungrantedStudentRejected() {
            when(grantReader.hasGrant(ResourceType.COURSE, COURSE_ID, STUDENT_NODE)).thenReturn(false);
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COURSE_NOT_GRANTED);
        }

        @Test
        @DisplayName("⚠ 判定以【本学生节点】为 key，不回溯祖先链")
        void grantCheckedAgainstStudentNodeOnly() {
            verifyAsStudent();
            verify(grantReader).hasGrant(ResourceType.COURSE, COURSE_ID, STUDENT_NODE);
        }

        @Test
        @DisplayName("管理端预览改走 0.2 资源可见性，不查 org_resource_grant 的学生授权")
        void adminPreviewUsesVisibilityInstead() {
            verifyAsAdmin();
            verify(ownerChecker).canUse(ResourceType.COURSE, COURSE_ID, ADMIN_NODE);
            verify(grantReader, never()).hasGrant(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("管理端对该课程既非自有也未被授权 → 同样 20013")
        void adminWithoutVisibilityRejected() {
            when(ownerChecker.canUse(ResourceType.COURSE, COURSE_ID, ADMIN_NODE)).thenReturn(false);
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsAdmin)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COURSE_NOT_GRANTED);
        }
    }

    // =========================================================================
    // 第 4 步 · 课程已上架 —— M41
    // =========================================================================
    @Nested
    @DisplayName("第 4 步 课程已上架（M41）")
    class Step4Shelf {

        @Test
        @DisplayName("课程已下架 → 20013（即使学生有授权）")
        void offShelfCourseRejected() {
            when(courseShelfReader.isOnShelf(COURSE_ID)).thenReturn(false);
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COURSE_NOT_GRANTED);
        }

        @Test
        @DisplayName("⚠ 管理端预览【也要】走第 4 步 —— 预览只允许跳第 1、3 两步")
        void adminPreviewStillRunsShelfCheck() {
            when(courseShelfReader.isOnShelf(COURSE_ID)).thenReturn(false);
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsAdmin)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COURSE_NOT_GRANTED);
        }
    }

    // =========================================================================
    // 第 5 步 · 视频校验 —— M42
    // =========================================================================
    @Nested
    @DisplayName("第 5 步 视频校验（M42）")
    class Step5Video {

        @Test
        @DisplayName("转码未完成 → 20003")
        void transcodingRejected() {
            when(videoStatusChecker.assertPlayable(VIDEO_ID))
                    .thenThrow(new BizException(ErrorCode.VIDEO_TRANSCODE_NOT_FINISHED));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VIDEO_TRANSCODE_NOT_FINISHED);
        }

        @Test
        @DisplayName("媒资被禁用 → 20015")
        void disabledVideoRejected() {
            when(videoStatusChecker.assertPlayable(VIDEO_ID))
                    .thenThrow(new BizException(ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsStudent)
                    .isInstanceOf(BizException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VIDEO_NOT_FOUND_OR_STATUS_INVALID);
        }

        @Test
        @DisplayName("⚠ 管理端预览【也要】走第 5 步")
        void adminPreviewStillRunsVideoCheck() {
            when(videoStatusChecker.assertPlayable(VIDEO_ID))
                    .thenThrow(new BizException(ErrorCode.VIDEO_TRANSCODE_NOT_FINISHED));
            assertThatThrownBy(PlayAuthChainServiceTest.this::verifyAsAdmin)
                    .isInstanceOf(BizException.class);
        }
    }

    // =========================================================================
    // 管理端预览整体形状
    // =========================================================================
    @Nested
    @DisplayName("管理端预览")
    class AdminPreview {

        @Test
        @DisplayName("预览不查学籍，且 studentId 留 NULL（教师/管理员没有 org_student 档案行）")
        void previewSkipsEnrollmentAndLeavesStudentIdNull() {
            PlayAuthChainService.VerifiedPlay play = verifyAsAdmin();
            assertThat(play.studentId()).isNull();
            assertThat(play.isStudent()).isFalse();
            verify(enrollmentReader, never()).byNodeId(anyLong());
        }

        @Test
        @DisplayName("教师预览与管理员预览走同一条路径")
        void teacherPreviewSameAsAdmin() {
            when(ownerChecker.canUse(ResourceType.COURSE, COURSE_ID, ADMIN_NODE)).thenReturn(true);
            PlayAuthChainService.VerifiedPlay play = chain.verify(ADMIN_NODE, 2, LESSON_ID);
            assertThat(play.studentId()).isNull();
        }
    }
}
