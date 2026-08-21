package com.edumatrix.course.catalog.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.edumatrix.common.subtree.OrgRootGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.media.VideoRef;
import com.edumatrix.common.media.VideoRefReader;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.course.catalog.dto.LessonCreateReq;
import com.edumatrix.course.catalog.dto.LessonPageQuery;
import com.edumatrix.course.catalog.dto.LessonUpdateReq;
import com.edumatrix.course.catalog.entity.CrsChapter;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.entity.CrsLesson;
import com.edumatrix.course.catalog.entity.CrsMaterial;
import com.edumatrix.course.catalog.mapper.CrsChapterMapper;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;
import com.edumatrix.course.catalog.mapper.CrsMaterialMapper;
import com.edumatrix.course.catalog.service.CourseAccessGuard.CourseRef;
import com.edumatrix.course.catalog.vo.CreatedIdVO;
import com.edumatrix.course.catalog.vo.LessonDetailVO;
import com.edumatrix.course.catalog.vo.LessonListVO;

/**
 * 课时管理（03-03 §3.1~§3.5，接口 12~16）。
 *
 * <h2>创建 / 修改的判定顺序表（仿 03-02 §3.4 的写法）</h2>
 * <table border="1">
 *   <caption>任一失败即整体拒绝，不落任何数据</caption>
 *   <tr><th>#</th><th>判定</th><th>失败</th><th>依据</th></tr>
 *   <tr><td>1</td><td>{@code @SaCheckPermission}</td><td>403</td><td>契约 §10 附表 A</td></tr>
 *   <tr><td>2</td><td>（修改）课时存在且未删除</td><td><b>{@code 20014}</b></td><td>§3.4 错误码栏</td></tr>
 *   <tr><td>3</td><td>{@code chapterId} 存在、未删除、<b>且属于目标课程</b></td>
 *       <td><b>{@code 20007}</b></td>
 *       <td>§3.3 规则 1。<b>该场景分册没给码</b>，G 定案启用 §9.3 明确登记的预留号位；
 *           <b>不把 {@code 20006} 扩义</b> —— C3 校验同码同义且作用于全局</td></tr>
 *   <tr><td>4</td><td>所属课程存在未删 → 可见 → owner</td><td>{@code 20004} / 404 / 403</td>
 *       <td>{@link CourseAccessGuard}</td></tr>
 *   <tr><td>5</td><td>{@code lessonType=1} 缺 {@code videoId} 或 {@code =2} 缺 {@code materialId}</td>
 *       <td><b>{@code 20019}</b></td><td>§3.3 规则 2 / 3、§0.3</td></tr>
 *   <tr><td>6</td><td>{@code lessonType=1}：{@code videoId} 对应媒资存在且未删除</td>
 *       <td><b>{@code 20008}</b></td><td>§3.3 规则 2</td></tr>
 *   <tr><td>7</td><td>{@code lessonType=1} <b>且目标 {@code status=1} 可见</b>：视频 {@code status=2}</td>
 *       <td><b>{@code 20008}</b>（<b>不是 {@code 20003}</b>）</td>
 *       <td>见下方「B 定案」</td></tr>
 *   <tr><td>8</td><td>{@code lessonType=2}：{@code materialId} 对应资料存在且未删除</td>
 *       <td><b>{@code 20009}</b></td><td>§3.3 规则 3</td></tr>
 *   <tr><td>9</td><td>落库 + <b>同事务</b>全量重算 {@code lesson_count} / {@code total_duration}</td>
 *       <td>—</td><td>{@link CourseCounterService}</td></tr>
 * </table>
 *
 * <h2>第 7 条：只在「置为可见」时校验视频状态（B 定案，<b>明知地推翻分册</b>）</h2>
 * <p>两处原文对同一件事给出不同答案：
 * <ul>
 *   <li><b>03-03 §3.3 规则 2 / §3.4</b>（权威更高）：「{@code videoId} …… 对应
 *       {@code vod_video} 必须存在、未删除、{@code status=2}，否则 {@code 20008}」——
 *       <b>没有「置为可见时」这个前提</b>，即任何创建/修改都卡；
 *   <li><b>PRD F2-1 规则 4</b>：「视频课时必须关联 {@code vod_video} 且其 {@code status=2}
 *       <b>才允许将课时置为 {@code status=1} 可见</b>」。
 * </ul>
 * <p>按权威顺序（DESIGN-CONTRACT &gt; 03 六分册 &gt; 01-PRD）本该取分册。
 * <b>需方定案取 PRD 口径，理由是可达性</b>：取分册口径会让 PRD F2-1 那条验收标准
 * 「关联视频 {@code status=1} 时置课时可见返回 {@code 20008}」<b>几乎走不到</b> ——
 * 创建那一步就先失败了，等于把一条验收标准做死。
 * 已登记 <b>F-43</b>，并已订正 §3.3 规则 2 与 §3.4 的措辞。
 *
 * <p><b>第二道网仍在</b>：接口 6 上架时按 §1.6 规则 2 校验<b>全部</b>视频课时
 * （含隐藏课时）的视频 {@code status=2}，否则 {@code 20003}。
 */
@Service
public class LessonService {

    private final CrsLessonMapper lessonMapper;
    private final OrgRootGuard orgRootGuard;
    private final CrsChapterMapper chapterMapper;
    private final CrsMaterialMapper materialMapper;
    private final CourseAccessGuard guard;
    private final CourseCounterService counterService;
    private final VideoRefReader videoRefReader;

    public LessonService(CrsLessonMapper lessonMapper,
                         CrsChapterMapper chapterMapper,
                         CrsMaterialMapper materialMapper,
                         CourseAccessGuard guard,
                         CourseCounterService counterService,
                         VideoRefReader videoRefReader,
                         OrgRootGuard orgRootGuard) {
        this.orgRootGuard = orgRootGuard;
        this.lessonMapper = lessonMapper;
        this.chapterMapper = chapterMapper;
        this.materialMapper = materialMapper;
        this.guard = guard;
        this.counterService = counterService;
        this.videoRefReader = videoRefReader;
    }

    // =====================================================================
    // 接口 12 §3.1 课时分页列表
    // =====================================================================

    public PageResult<LessonListVO> page(LessonPageQuery query) {
        // 查询参数里显式指定的 courseId —— 用户请求的资源是「课时列表」（CourseRef.PARAM）
        CrsCourse course = guard.loadVisible(CourseRef.PARAM, query.getCourseId());

        LambdaQueryWrapper<CrsLesson> wrapper = new LambdaQueryWrapper<CrsLesson>()
                .eq(CrsLesson::getCourseId, course.getId());
        if (query.getChapterId() != null) {
            wrapper.eq(CrsLesson::getChapterId, query.getChapterId());
        }
        if (query.getLessonName() != null && !query.getLessonName().isBlank()) {
            wrapper.like(CrsLesson::getLessonName, query.getLessonName().trim());
        }
        if (query.getLessonType() != null) {
            wrapper.eq(CrsLesson::getLessonType, query.getLessonType());
        }
        if (query.getStatus() != null) {
            wrapper.eq(CrsLesson::getStatus, query.getStatus());
        }
        wrapper.orderByAsc(CrsLesson::getSort).orderByAsc(CrsLesson::getId);

        IPage<CrsLesson> page = lessonMapper.selectPage(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())), wrapper);

        List<CrsLesson> rows = page.getRecords();
        Map<Long, String> chapterNames = chapterNames(rows.stream()
                .map(CrsLesson::getChapterId).filter(java.util.Objects::nonNull).distinct().toList());
        Map<Long, VideoRef> videos = videoRefs(rows.stream()
                .map(CrsLesson::getVideoId).filter(java.util.Objects::nonNull).distinct().toList());

        List<LessonListVO> list = new ArrayList<>(rows.size());
        for (CrsLesson lesson : rows) {
            LessonListVO vo = new LessonListVO();
            vo.setId(lesson.getId());
            vo.setCourseId(lesson.getCourseId());
            vo.setChapterId(lesson.getChapterId());
            vo.setChapterName(chapterNames.get(lesson.getChapterId()));
            vo.setLessonName(lesson.getLessonName());
            vo.setLessonType(lesson.getLessonType());
            vo.setVideoId(lesson.getVideoId());
            vo.setVideoStatus(lesson.getVideoId() == null ? null
                    : videoStatusOf(videos.get(lesson.getVideoId())));
            vo.setMaterialId(lesson.getContentId());
            vo.setDuration(lesson.getDuration());
            vo.setSort(lesson.getSort());
            vo.setIsFreePreview(lesson.getIsFreePreview());
            vo.setStatus(lesson.getStatus());
            vo.setCreateTime(lesson.getCreateTime());
            vo.setUpdateTime(lesson.getUpdateTime());
            list.add(vo);
        }
        return PageResult.of(page.getTotal(), list);
    }

    // =====================================================================
    // 接口 13 §3.2 课时详情
    // =====================================================================

    public LessonDetailVO detail(Long lessonId) {
        CrsLesson lesson = loadLessonByPath(lessonId);
        guard.loadVisible(CourseRef.DERIVED, lesson.getCourseId());

        VideoRef video = lesson.getVideoId() == null ? null : videoRefReader.read(lesson.getVideoId());
        CrsMaterial material = lesson.getContentId() == null ? null
                : materialMapper.selectById(lesson.getContentId());

        LessonDetailVO vo = new LessonDetailVO();
        vo.setId(lesson.getId());
        vo.setCourseId(lesson.getCourseId());
        vo.setChapterId(lesson.getChapterId());
        vo.setLessonName(lesson.getLessonName());
        vo.setLessonType(lesson.getLessonType());
        vo.setVideoId(lesson.getVideoId());
        vo.setVideoName(video == null ? null : video.videoName());
        vo.setVideoStatus(videoStatusOf(video));
        vo.setMaterialId(lesson.getContentId());
        vo.setMaterialTitle(material == null ? null : material.getTitle());
        vo.setDuration(lesson.getDuration());
        vo.setSort(lesson.getSort());
        vo.setIsFreePreview(lesson.getIsFreePreview());
        vo.setStatus(lesson.getStatus());
        vo.setRemark(lesson.getRemark());
        vo.setCreateBy(lesson.getCreateBy());
        vo.setCreateTime(lesson.getCreateTime());
        vo.setUpdateBy(lesson.getUpdateBy());
        vo.setUpdateTime(lesson.getUpdateTime());
        return vo;
    }

    // =====================================================================
    // 接口 14 §3.3 创建课时
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public CreatedIdVO create(LessonCreateReq req) {
        orgRootGuard.assertOrgRoot("课时");   // F-114 收窄：三类受管资源写操作仅机构根
        CrsChapter chapter = loadChapterOrThrow(req.getChapterId());
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.DERIVED, chapter.getCourseId());

        int status = req.getStatus() == null ? CrsLesson.STATUS_VISIBLE : req.getStatus();
        CrsLesson lesson = new CrsLesson();
        lesson.setCourseId(course.getId());
        lesson.setChapterId(chapter.getId());
        lesson.setLessonName(req.getLessonName().trim());
        lesson.setLessonType(req.getLessonType());
        lesson.setSort(req.getSort() != null ? req.getSort() : nextSort(chapter.getId()));
        lesson.setIsFreePreview(req.getIsFreePreview() == null ? 0 : req.getIsFreePreview());
        lesson.setStatus(status);
        bindResource(lesson, req.getLessonType(), req.getVideoId(), req.getMaterialId(), status);

        lessonMapper.insert(lesson);
        counterService.refreshByCourse(course.getId());

        CreatedIdVO vo = new CreatedIdVO();
        vo.setId(lesson.getId());
        return vo;
    }

    // =====================================================================
    // 接口 15 §3.4 修改课时
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long lessonId, LessonUpdateReq req) {
        orgRootGuard.assertOrgRoot("课时");   // F-114 收窄：三类受管资源写操作仅机构根
        CrsLesson lesson = loadLessonByPath(lessonId);
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.DERIVED, lesson.getCourseId());

        Long targetChapterId = lesson.getChapterId();
        if (req.getChapterId() != null && !req.getChapterId().equals(lesson.getChapterId())) {
            CrsChapter target = loadChapterOrThrow(req.getChapterId());
            if (!target.getCourseId().equals(course.getId())) {
                // 跨课程移动会让两个课程的 lesson_count 同时失真；§3.4 也写死了「须属于同一课程」
                throw new BizException(ErrorCode.CHAPTER_NOT_FOUND_IN_COURSE,
                        "章节不存在或不属于该课程：不支持把课时移动到其他课程");
            }
            targetChapterId = target.getId();
        }

        int status = req.getStatus() == null ? lesson.getStatus() : req.getStatus();
        CrsLesson patch = new CrsLesson();
        patch.setId(lesson.getId());
        patch.setChapterId(targetChapterId);
        patch.setLessonName(req.getLessonName().trim());
        patch.setLessonType(req.getLessonType());
        patch.setSort(req.getSort() != null ? req.getSort() : lesson.getSort());
        patch.setIsFreePreview(req.getIsFreePreview() == null
                ? lesson.getIsFreePreview() : req.getIsFreePreview());
        patch.setStatus(status);
        bindResource(patch, req.getLessonType(), req.getVideoId(), req.getMaterialId(), status);

        // 换类型时要把另一侧的外键清空，否则一条课时同时挂着 video_id 与 content_id
        lessonMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CrsLesson>()
                        .eq(CrsLesson::getId, lesson.getId())
                        .set(CrsLesson::getChapterId, patch.getChapterId())
                        .set(CrsLesson::getLessonName, patch.getLessonName())
                        .set(CrsLesson::getLessonType, patch.getLessonType())
                        .set(CrsLesson::getVideoId, patch.getVideoId())
                        .set(CrsLesson::getContentId, patch.getContentId())
                        .set(CrsLesson::getDuration, patch.getDuration())
                        .set(CrsLesson::getSort, patch.getSort())
                        .set(CrsLesson::getIsFreePreview, patch.getIsFreePreview())
                        .set(CrsLesson::getStatus, patch.getStatus()));
        counterService.refreshByCourse(course.getId());
    }

    // =====================================================================
    // 接口 16 §3.5 删除课时
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long lessonId) {
        orgRootGuard.assertOrgRoot("课时");   // F-114 收窄：三类受管资源写操作仅机构根
        CrsLesson lesson = loadLessonByPath(lessonId);
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.DERIVED, lesson.getCourseId());
        lessonMapper.deleteById(lesson.getId());
        counterService.refreshByCourse(course.getId());
    }

    // =====================================================================
    // 内部：第 5~8 条判定
    // =====================================================================

    /**
     * 绑定关联资源并写 {@code duration}。见类注释的判定顺序表第 5~8 条。
     *
     * @param targetStatus 目标 {@code status}（0 隐藏 / 1 可见）—— 第 7 条只在可见时校验视频状态
     */
    private void bindResource(CrsLesson lesson, Integer lessonType,
                              Long videoId, Long materialId, int targetStatus) {
        if (lessonType != null && lessonType == CrsLesson.TYPE_VIDEO) {
            if (videoId == null) {
                throw new BizException(ErrorCode.LESSON_TYPE_RESOURCE_MISMATCH,
                        "课时类型与关联资源参数不匹配：lessonType=1 必须传 videoId");
            }
            VideoRef video = videoRefReader.read(videoId);
            if (video == null) {
                throw new BizException(ErrorCode.RELATED_VIDEO_UNAVAILABLE,
                        "关联视频不存在或状态不可用：媒资不存在或已删除");
            }
            if (targetStatus == CrsLesson.STATUS_VISIBLE && !video.isNormal()) {
                // B 定案：只在「置为可见」时卡。20008 而不是 20003 —— 见类注释
                throw new BizException(ErrorCode.RELATED_VIDEO_UNAVAILABLE,
                        "关联视频不存在或状态不可用：视频 status=" + video.status()
                                + "（仅 2 正常可置为可见）");
            }
            lesson.setVideoId(videoId);
            lesson.setContentId(null);
            lesson.setDuration(video.duration() == null ? 0 : video.duration());
            return;
        }
        if (materialId == null) {
            throw new BizException(ErrorCode.LESSON_TYPE_RESOURCE_MISMATCH,
                    "课时类型与关联资源参数不匹配：lessonType=2 必须传 materialId");
        }
        loadMaterialByParam(materialId);
        lesson.setVideoId(null);
        lesson.setContentId(materialId);
        lesson.setDuration(0);
    }

    /**
     * <b>请求体里显式指定的 {@code materialId}</b>（§3.3 规则 3 / §3.4）：
     * 不存在 / 已删除 → <b>{@code 20009}</b>，<b>不是 404</b>。
     *
     * <p>与 {@link #loadLessonByPath} 的分工就是 F-42 的边界：用户请求的资源是
     * <b>课时</b>，{@code materialId} 只是他选的一个关联对象 —— 选错了要明确告诉他，
     * 返 404 会让人以为端点写错了（契约 §2.4 三分法第 3 行同一条理由）。
     */
    private CrsMaterial loadMaterialByParam(Long materialId) {
        CrsMaterial material = materialId == null ? null : materialMapper.selectById(materialId);
        if (material == null) {
            throw new BizException(ErrorCode.RELATED_MATERIAL_UNAVAILABLE);
        }
        return material;
    }

    /** {@code chapterId} 不存在 / 已删除 → {@code 20007}（G 定案的新码）。 */
    private CrsChapter loadChapterOrThrow(Long chapterId) {
        CrsChapter chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BizException(ErrorCode.CHAPTER_NOT_FOUND_IN_COURSE);
        }
        return chapter;
    }

    /**
     * <b>路径上的课时</b>（{@code /lessons/{id}}，§3.2 / §3.4 / §3.5）：
     * 不存在 / 已删除 / 跨租户 → <b>404</b>。
     *
     * <h2>F-42 定案：与「存在但不可见」同一个结果</h2>
     * <p>原先这里抛 {@code 20014}，而「课时在、但所属课程对我不可见」由
     * {@code guard.loadVisible(DERIVED, ...)} 抛 404 —— 两个码合起来能被拿来
     * <b>探测存在性</b>：拿到 {@code 20014} = 这个 id 不存在，拿到 404 = 存在但你看不到。
     * 统一到 404 而不是统一到业务码，是因为契约 §2.4 三分法第 1 行
     * 「路径上的资源不在我的子树内 → 404，不暴露存在性」是上位文档、动不得。
     *
     * <p><b>{@code 20014} 没有退役</b>：它仍然是
     * {@code common/course/LessonVisibilityChecker} 的码 —— 那条路径上的
     * {@code lessonId} 来自<b>请求体/查询参数</b>（模块 12 的 {@code POST /vod/play-auth}、
     * 模块 13 的心跳、模块 14 的学生端），属于 {@link CourseRef#PARAM} 那一类。
     * 方法名里的 {@code ByPath} 就是用来把这两类在代码里分开的。
     */
    private CrsLesson loadLessonByPath(Long lessonId) {
        CrsLesson lesson = lessonId == null ? null : lessonMapper.selectById(lessonId);
        if (lesson == null) {
            throw BizException.notFound(lessonId);
        }
        return lesson;
    }

    private int nextSort(Long chapterId) {
        List<CrsLesson> siblings = lessonMapper.selectList(new LambdaQueryWrapper<CrsLesson>()
                .eq(CrsLesson::getChapterId, chapterId)
                .orderByDesc(CrsLesson::getSort).last("LIMIT 1"));
        return siblings.isEmpty() || siblings.get(0).getSort() == null ? 1 : siblings.get(0).getSort() + 1;
    }

    private Map<Long, String> chapterNames(List<Long> chapterIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (chapterIds.isEmpty()) {
            return names;
        }
        for (CrsChapter chapter : chapterMapper.selectBatchIds(chapterIds)) {
            names.put(chapter.getId(), chapter.getChapterName());
        }
        return names;
    }

    private Map<Long, VideoRef> videoRefs(List<Long> videoIds) {
        Map<Long, VideoRef> refs = new LinkedHashMap<>();
        for (VideoRef ref : videoRefReader.readAll(videoIds)) {
            refs.put(ref.id(), ref);
        }
        return refs;
    }

    /** 媒资已删除 / 不存在时 {@code videoStatus} 为 {@code null} —— 不编一个状态出来。 */
    private static Integer videoStatusOf(VideoRef video) {
        return video == null ? null : video.status();
    }
}
