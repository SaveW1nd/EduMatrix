package com.edumatrix.vod.play.service;

import org.springframework.stereotype.Service;

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
import com.edumatrix.vod.play.PlayAuthConst;

/**
 * 播放凭证的五步校验链 —— <b>整条链上唯一的那道闸</b>。
 *
 * <p><b>为什么单独抽成一个类</b>：现在只有 {@code POST /api/v1/vod/play-auth} 一个调用方，
 * 但它<b>被单独命名、单独测试比内联在 Controller 里更难被悄悄简化</b>（04 §B 模块 12「对外产出」逐字）。
 * 旧方案还有「取解密密钥」接口做后备闸，那个已随 F-112 删除（03-03 §8.2 墓碑小节）——
 * <b>现在打断这五步中的任何一步，都没有第二道东西会拦住</b>。
 *
 * <h2>⚠ 不要在心跳或换发凭证时加授权重判（F-106 需方定案）</h2>
 * 需方 2026-08-21 定案：<b>撤销从「下一次点开视频」起生效，正在播放的那个视频可以看完</b>。
 * 因此心跳（03-03 §8.3.1 规则 1）只校验 {@code X-Play-Token} 本身、<b>不回库重跑 {@code org_resource_grant}</b>；
 * 换发 {@code authToken} / {@code playAuth} 时<b>同样不重判</b>——换发是换一张同样的票，不是一次新的准入。
 *
 * <p><b>这一条明知地推翻了 PRD FR-4 规则 4 的「即时生效」与契约 §1 的核心验收场景</b>，两处已在原处标注。
 * <b>下一个人在心跳里加一次 {@code org_resource_grant} 查询，改的是需方定案，不是补 bug</b>——
 * 要改先回去改 F-106，不要从这里开始。
 *
 * <p>窗口有多大：<b>一个视频的剩余时长</b>，不是无限。下一次点开任何视频都会重新走完整的五步。
 *
 * <h2>五步（按序，任一失败即终止）</h2>
 * <ol>
 *   <li><b>学生在读</b>（{@code org_student.status = 0}）→ 20013。
 *       <b>这是撤销之外唯一的失效手段</b>（F-103：授权一律永久有效、没有到期日）——
 *       需方定了永久有效之后，退课 / 结业 / 归档全靠这一步兜底。
 *       <b>04 §B 原文的「三步」整个漏掉了它</b>，按那个实现，退课学生照样拿得到凭证
 *       而且不报错、不告警（本轮已订正为五步）。</li>
 *   <li>课时校验（存在、未删、{@code status=1}、{@code lesson_type=1}）→ 20014</li>
 *   <li>授权校验（学生：该<b>学生节点</b>被授权该课程，<b>不回溯祖先链</b>）→ 20013</li>
 *   <li>课程已上架（{@code crs_course.status = 1}）→ 20013</li>
 *   <li>视频校验（{@code status ∈ {0,1}} → 20003；{@code ∈ {3,9}} → 20015）</li>
 * </ol>
 *
 * <p><b>管理端预览只跳第 1、3 两步</b>：没有学籍故第 1 步不适用；第 3 步改按 03-03 §0.2
 * 资源可见性（课程自有或被授权）。<b>第 2、4、5 步一步都不能跳。</b>
 *
 * <p><b>错误码按 F-48 定案保持现状</b>（需方 2026-08-21 裁决「不收口」）：
 * 「课时不存在」= 20014、「存在但没被授权」= 20013，<b>两种回答保持可区分</b>，
 * 不要把前者并进后者。理由见 04 §E F-48。
 */
@Service
public class PlayAuthChainService {

    private final StudentEnrollmentReader enrollmentReader;
    private final LessonVisibilityChecker lessonChecker;
    private final ResourceGrantReader grantReader;
    private final ResourceOwnerChecker ownerChecker;
    private final CourseShelfReader courseShelfReader;
    private final VideoStatusChecker videoStatusChecker;

    public PlayAuthChainService(StudentEnrollmentReader enrollmentReader,
                                LessonVisibilityChecker lessonChecker,
                                ResourceGrantReader grantReader,
                                ResourceOwnerChecker ownerChecker,
                                CourseShelfReader courseShelfReader,
                                VideoStatusChecker videoStatusChecker) {
        this.enrollmentReader = enrollmentReader;
        this.lessonChecker = lessonChecker;
        this.grantReader = grantReader;
        this.ownerChecker = ownerChecker;
        this.courseShelfReader = courseShelfReader;
        this.videoStatusChecker = videoStatusChecker;
    }

    /**
     * @param viewerNodeId 当前账号所在节点。学生路径下它同时是「学生节点」——
     *                     学籍与授权两步用<b>同一个 key</b>，避免出现「查学籍用 A、查授权用 B」这种对不上的形态
     * @param viewerType   {@code sys_user.user_type}：3 学生走完整五步；1 / 2 走管理端预览
     * @param lessonId     课时 ID
     */
    public VerifiedPlay verify(Long viewerNodeId, Integer viewerType, Long lessonId) {
        boolean student = viewerType != null && viewerType == PlayAuthConst.VIEWER_TYPE_STUDENT;

        // ── 第 1 步 · 学生在读（管理端预览不适用）
        Long studentId = null;
        if (student) {
            StudentEnrollmentReader.Enrollment enrollment = enrollmentReader.byNodeId(viewerNodeId);
            if (enrollment == null || !enrollment.active()) {
                throw new BizException(ErrorCode.COURSE_NOT_GRANTED);
            }
            studentId = enrollment.studentId();
        }

        // ── 第 2 步 · 课时校验（预览【不跳】）
        LessonVisibilityChecker.VisibleLesson lesson =
                lessonChecker.assertVisible(lessonId, LessonVisibilityChecker.LESSON_TYPE_VIDEO);

        // ── 第 3 步 · 授权校验：学生按 org_resource_grant，预览按 0.2 资源可见性
        boolean permitted = student
                ? grantReader.hasGrant(ResourceType.COURSE, lesson.courseId(), viewerNodeId)
                : ownerChecker.canUse(ResourceType.COURSE, lesson.courseId(), viewerNodeId);
        if (!permitted) {
            throw new BizException(ErrorCode.COURSE_NOT_GRANTED);
        }

        // ── 第 4 步 · 课程已上架（预览【不跳】）
        if (!courseShelfReader.isOnShelf(lesson.courseId())) {
            throw new BizException(ErrorCode.COURSE_NOT_GRANTED);
        }

        // ── 第 5 步 · 视频校验（预览【不跳】）
        VideoRef video = videoStatusChecker.assertPlayable(lesson.videoId());

        return new VerifiedPlay(studentId, lesson.courseId(), lessonId, lesson.videoId(), video);
    }

    /**
     * @param studentId 仅学生路径有值；管理端预览为 {@code null}
     *                  （教师与管理员没有 {@code org_student} 档案行，
     *                  {@code vod_play_auth_log.student_id} 因此留 NULL）
     */
    public record VerifiedPlay(Long studentId, Long courseId, Long lessonId, Long videoId, VideoRef video) {

        public boolean isStudent() {
            return studentId != null;
        }
    }
}
