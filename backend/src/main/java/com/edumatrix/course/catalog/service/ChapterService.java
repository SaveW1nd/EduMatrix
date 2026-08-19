package com.edumatrix.course.catalog.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.course.catalog.dto.ChapterCreateReq;
import com.edumatrix.course.catalog.dto.ChapterSortReq;
import com.edumatrix.course.catalog.dto.ChapterUpdateReq;
import com.edumatrix.course.catalog.entity.CrsChapter;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.mapper.CrsChapterMapper;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;
import com.edumatrix.course.catalog.service.CourseAccessGuard.CourseRef;
import com.edumatrix.course.catalog.vo.ChapterDeleteVO;
import com.edumatrix.course.catalog.vo.ChapterNodeVO;
import com.edumatrix.course.catalog.vo.CreatedIdVO;

/**
 * 章节管理（03-03 §2.1~§2.5，接口 7~11）。
 *
 * <h2>两级树是硬约束</h2>
 * <p>PRD F2-1 规则 1：章（{@code parent_id=0}）→ 节（{@code parent_id=章 id}），
 * <b>节下不得再建节</b>，违反 → {@code 20006}。这是模块 08 的自检项之一。
 *
 * <h2>所有写操作先取课程行锁</h2>
 * <p>见 {@code CrsCourseMapper#lockForUpdate}。{@code 20018}（排序集合不一致）
 * <b>必须在锁内判</b>：那是一次「读集合 → 比对 → 批量写」，把读比对放到锁外，
 * 两个并发请求会<b>都通过</b>集合校验然后交错写入，而<b>两个都返回 200</b> ——
 * 接口 200、字段齐全、结果错，正是本项目 1 号失败模式，也正好架空了 {@code 20018} 存在的理由。
 */
@Service
public class ChapterService {

    private final CrsChapterMapper chapterMapper;
    private final CrsLessonMapper lessonMapper;
    private final CourseAccessGuard guard;
    private final CourseCounterService counterService;

    public ChapterService(CrsChapterMapper chapterMapper,
                          CrsLessonMapper lessonMapper,
                          CourseAccessGuard guard,
                          CourseCounterService counterService) {
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.guard = guard;
        this.counterService = counterService;
    }

    // =====================================================================
    // 接口 7 §2.1 章节树查询
    // =====================================================================

    /**
     * <b>读操作按 §0.2 资源可见性判定，不要求 owner</b>。
     *
     * <p>§2.1 权限栏原写「要求课程 {@code owner_node_id} = 我的节点（仅被授权者只读，
     * 写操作返回 403）」—— 一个 GET 接口的前半句要求 owner、后半句说被授权者只读，
     * <b>同一行自相矛盾</b>，且与 §0.2「被授权者为只读视图」、§6.2「被授权的课程可预览」
     * 冲突。本轮已订正该处措辞。
     */
    public List<ChapterNodeVO> tree(Long courseId) {
        // 路径上的资源（/courses/{id}/chapters）：不存在与不可见同为 404（F-42 定案）
        CrsCourse course = guard.loadVisible(CourseRef.PATH, courseId);
        List<CrsChapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<CrsChapter>()
                .eq(CrsChapter::getCourseId, course.getId())
                .orderByAsc(CrsChapter::getSort).orderByAsc(CrsChapter::getId));
        if (chapters.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> lessonCounts = lessonCounts(
                chapters.stream().map(CrsChapter::getId).toList());

        Map<Long, ChapterNodeVO> byId = new LinkedHashMap<>();
        List<ChapterNodeVO> roots = new ArrayList<>();
        for (CrsChapter chapter : chapters) {
            ChapterNodeVO vo = new ChapterNodeVO();
            vo.setId(chapter.getId());
            vo.setCourseId(chapter.getCourseId());
            vo.setChapterName(chapter.getChapterName());
            vo.setParentId(chapter.getParentId());
            vo.setSort(chapter.getSort());
            vo.setLessonCount(lessonCounts.getOrDefault(chapter.getId(), 0));
            byId.put(chapter.getId(), vo);
        }
        for (CrsChapter chapter : chapters) {
            ChapterNodeVO vo = byId.get(chapter.getId());
            if (chapter.isTopLevel()) {
                roots.add(vo);
            } else {
                ChapterNodeVO parent = byId.get(chapter.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                } else {
                    // 父章已被删除而节还在：不静默丢弃（丢了就是「接口 200、少了几条」），
                    // 提到顶层展示，让人看得见这条脏数据
                    roots.add(vo);
                }
            }
        }
        return roots;
    }

    private Map<Long, Integer> lessonCounts(List<Long> chapterIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (chapterIds.isEmpty()) {
            return counts;
        }
        for (CrsLessonMapper.ChapterLessonCount row : lessonMapper.countByChapters(chapterIds)) {
            counts.put(row.getChapterId(), row.getLessonCount());
        }
        return counts;
    }

    // =====================================================================
    // 接口 8 §2.2 创建章节
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public CreatedIdVO create(ChapterCreateReq req) {
        // 请求体里显式指定的 courseId —— 用户请求的资源是「章节」，课程只是归属参数，
        // 查不到必须明确告诉他选错了，返 404 会指代不清（F-42 定案的边界，CourseRef.PARAM）
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.PARAM, req.getCourseId());
        Long parentId = req.getParentId() == null ? CrsChapter.ROOT_PARENT_ID : req.getParentId();
        if (parentId != CrsChapter.ROOT_PARENT_ID) {
            CrsChapter parent = chapterMapper.selectById(parentId);
            // 父必须存在、属于本课程、且本身是「章」（parent_id = 0）——节下再建节即三级
            if (parent == null || !parent.getCourseId().equals(course.getId()) || !parent.isTopLevel()) {
                throw new BizException(ErrorCode.CHAPTER_LEVEL_INVALID,
                        "章节层级不合法：仅支持「章-节」两级，不能在「节」下再建子章节");
            }
        }
        CrsChapter chapter = new CrsChapter();
        chapter.setCourseId(course.getId());
        chapter.setParentId(parentId);
        chapter.setChapterName(req.getChapterName().trim());
        chapter.setSort(req.getSort() != null ? req.getSort() : nextSort(course.getId(), parentId));
        chapterMapper.insert(chapter);

        CreatedIdVO vo = new CreatedIdVO();
        vo.setId(chapter.getId());
        return vo;
    }

    private int nextSort(Long courseId, Long parentId) {
        List<CrsChapter> siblings = chapterMapper.selectList(new LambdaQueryWrapper<CrsChapter>()
                .eq(CrsChapter::getCourseId, courseId)
                .eq(CrsChapter::getParentId, parentId)
                .orderByDesc(CrsChapter::getSort).last("LIMIT 1"));
        return siblings.isEmpty() || siblings.get(0).getSort() == null ? 1 : siblings.get(0).getSort() + 1;
    }

    // =====================================================================
    // 接口 9 §2.3 修改章节
    // =====================================================================

    /**
     * <b>只改名称</b>。层级与排序统一走接口 11，避免两处入口引发并发冲突（§2.3 说明）。
     *
     * <p>章节不存在 → <b>HTTP 404</b>（§2.3 说明逐字：「章节资源不存在返回 HTTP 404」）。
     * §2.3 的「相关业务错误码」栏原写「{@code 20004}、{@code 20014}（……<b>不适用</b>……）」
     * 又在下一句说「本接口业务错误码<b>仅</b> {@code 20004}」—— <b>同一行自我否定</b>，
     * 本轮已订正为只留 {@code 20004}。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long chapterId, ChapterUpdateReq req) {
        CrsChapter chapter = loadChapter(chapterId);
        guard.loadOwned(CourseRef.DERIVED, chapter.getCourseId());
        CrsChapter patch = new CrsChapter();
        patch.setId(chapter.getId());
        patch.setChapterName(req.getChapterName().trim());
        chapterMapper.updateById(patch);
    }

    // =====================================================================
    // 接口 10 §2.4 删除章节
    // =====================================================================

    /**
     * <b>级联逻辑删除</b>（PRD F2-1 规则 6、§2.4 说明）：删除章时其下全部节与课时一并逻辑删除；
     * 删除节时其下课时一并逻辑删除。学生已产生的进度数据保留不清理。
     *
     * <p>整个动作在<b>一个事务</b>内，且先取课程行锁：章节集合的解析与课时的级联删除之间
     * 若被别人插入一个新节，就会漏删。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterDeleteVO delete(Long chapterId) {
        CrsChapter chapter = loadChapter(chapterId);
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.DERIVED, chapter.getCourseId());

        List<Long> victims = new ArrayList<>();
        victims.add(chapter.getId());
        if (chapter.isTopLevel()) {
            chapterMapper.selectList(new LambdaQueryWrapper<CrsChapter>()
                            .eq(CrsChapter::getCourseId, course.getId())
                            .eq(CrsChapter::getParentId, chapter.getId()))
                    .forEach(child -> victims.add(child.getId()));
        }

        Long operator = TenantHelper.getUserId();
        int deletedLessons = lessonMapper.softDeleteByChapters(victims, operator);
        chapterMapper.delete(new LambdaQueryWrapper<CrsChapter>().in(CrsChapter::getId, victims));
        counterService.refreshByCourse(course.getId());

        ChapterDeleteVO vo = new ChapterDeleteVO();
        vo.setDeletedChapterCount(victims.size());
        vo.setDeletedLessonCount(deletedLessons);
        return vo;
    }

    // =====================================================================
    // 接口 11 §2.5 章节拖拽排序
    // =====================================================================

    /**
     * <b>判定顺序表</b>（§2.5 业务规则逐条；任一失败即整体拒绝，不落任何数据）：
     *
     * <table border="1">
     *   <caption>排序的五步</caption>
     *   <tr><th>#</th><th>判定</th><th>失败</th><th>依据</th></tr>
     *   <tr><td>1</td><td>课程存在 → 可见 → owner</td><td>{@code 20004} / 404 / 403</td>
     *       <td>{@link CourseAccessGuard}</td></tr>
     *   <tr><td>2</td><td><b>取课程行锁</b>（{@code FOR UPDATE}）</td><td>—</td>
     *       <td>下面第 3 步是「读集合 → 比对 → 批量写」，锁外做等于没判</td></tr>
     *   <tr><td>3</td><td><b>锁内</b>：提交的 id 集合 = 该课程未删除章节集合（不多、不少、不重复）</td>
     *       <td><b>{@code 20018}</b></td><td>§2.5 规则 2</td></tr>
     *   <tr><td>4</td><td>每个 {@code parentId} 为 {@code 0} 或指向<b>本次提交中</b>
     *       {@code parentId=0} 的节点；降级为节的章不得携带子节</td>
     *       <td><b>{@code 20006}</b></td><td>§2.5 规则 3</td></tr>
     *   <tr><td>5</td><td>批量 {@code UPDATE parent_id, sort}</td><td>—</td><td>§2.5 规则 1 / 4</td></tr>
     * </table>
     */
    @Transactional(rollbackFor = Exception.class)
    public void sort(Long courseId, ChapterSortReq req) {
        // 路径上的资源（/courses/{id}/chapters/sort）
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.PATH, courseId);

        // ---- 第 3 步：锁内读出当前集合并比对 ----
        List<CrsChapter> current = chapterMapper.selectList(new LambdaQueryWrapper<CrsChapter>()
                .eq(CrsChapter::getCourseId, course.getId()));
        Set<Long> currentIds = new HashSet<>(current.stream().map(CrsChapter::getId).toList());

        List<ChapterSortReq.ChapterSortItem> items = req.getChapters();
        Set<Long> submitted = new HashSet<>();
        for (ChapterSortReq.ChapterSortItem item : items) {
            if (!submitted.add(item.getId())) {
                // 重复提交同一个 id：集合大小对不上，语义与「不一致」相同
                throw new BizException(ErrorCode.CHAPTER_SORT_MISMATCH);
            }
        }
        if (!submitted.equals(currentIds)) {
            throw new BizException(ErrorCode.CHAPTER_SORT_MISMATCH);
        }

        // ---- 第 4 步：两级树校验（只看本次提交的结构，不看库里的旧结构）----
        Set<Long> newTopLevel = new HashSet<>();
        for (ChapterSortReq.ChapterSortItem item : items) {
            if (isRoot(item.getParentId())) {
                newTopLevel.add(item.getId());
            }
        }
        for (ChapterSortReq.ChapterSortItem item : items) {
            if (isRoot(item.getParentId())) {
                continue;
            }
            if (item.getParentId().equals(item.getId()) || !newTopLevel.contains(item.getParentId())) {
                // 父不是本次提交中的「章」→ 会形成三级（含「降级的章仍携带子节」这一形态）
                throw new BizException(ErrorCode.CHAPTER_LEVEL_INVALID,
                        "章节层级不合法：仅支持「章-节」两级，父节点必须是本次提交中 parentId=0 的章");
            }
        }

        // ---- 第 5 步：批量更新 ----
        for (ChapterSortReq.ChapterSortItem item : items) {
            CrsChapter patch = new CrsChapter();
            patch.setId(item.getId());
            patch.setParentId(isRoot(item.getParentId()) ? CrsChapter.ROOT_PARENT_ID : item.getParentId());
            patch.setSort(item.getSort());
            chapterMapper.updateById(patch);
        }
    }

    private static boolean isRoot(Long parentId) {
        return parentId == null || parentId == CrsChapter.ROOT_PARENT_ID;
    }

    /** 章节资源不存在 → HTTP 404（§2.3 说明逐字）。 */
    private CrsChapter loadChapter(Long chapterId) {
        CrsChapter chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw BizException.notFound(chapterId);
        }
        return chapter;
    }
}
