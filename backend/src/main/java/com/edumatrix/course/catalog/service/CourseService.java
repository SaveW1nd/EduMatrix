package com.edumatrix.course.catalog.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.file.InlineFileUrlProvider;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.course.catalog.dto.CourseCreateReq;
import com.edumatrix.course.catalog.dto.CoursePageQuery;
import com.edumatrix.course.catalog.dto.CourseShelfReq;
import com.edumatrix.course.catalog.dto.CourseUpdateReq;
import com.edumatrix.course.catalog.entity.CrsChapter;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.entity.CrsLesson;
import com.edumatrix.course.catalog.mapper.CrsChapterMapper;
import com.edumatrix.course.catalog.mapper.CrsCourseMapper;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;
import com.edumatrix.course.catalog.service.CourseAccessGuard.CourseRef;
import com.edumatrix.course.catalog.vo.CourseDetailVO;
import com.edumatrix.course.catalog.vo.CourseListVO;
import com.edumatrix.course.catalog.vo.CourseShelfVO;
import com.edumatrix.course.catalog.vo.CreatedIdVO;

/**
 * 课程管理（03-03 §1.1~§1.6，接口 1~6）。
 *
 * <h2>{@code coverUrl} 一律现签，绝不下发 {@code sys_file.file_url}</h2>
 * <p>D-2 定案 + {@code 04-实施计划.md} §B 模块 08「做完什么算做完」的强制检查点：
 * {@code coverUrl} 必须是 {@code FileService#inlineSignedUrl(coverFileId)} 现签的
 * <b>≤30 分钟</b>地址。{@code sys_file.file_url} <b>只存对象键</b>，
 * 读出来直接返回等于下发了一条永久直链（{@code 00-通用约定} §7.4 第 1 行）。
 * 本类只注入 {@link InlineFileUrlProvider}，<b>没有任何途径拿到那一列</b> ——
 * {@code course} 领域不能 import {@code system}（检查③），
 * 而 {@code common/file/FileMeta} 在类型上就没有 URL 字段。
 *
 * <h2>资源可见性：精确等于我的节点 ∪ 被显式授权给我的节点</h2>
 * <p>03-03 §0.2 的 SQL 逐条。<b>授权谓词只写在
 * {@code common/grant/ResourceGrantReader} 一处</b> —— 这里用它返回的 id 集合拼
 * {@code id IN (...)}，而不是在本模块的 SQL 里再写一遍 {@code EXISTS (...)}。
 * 两份实现迟早出现「有的地方回溯了祖先链、有的地方没回溯」，而两种写法都返回 200。
 */
@Service
public class CourseService {

    private final CrsCourseMapper courseMapper;
    private final CrsChapterMapper chapterMapper;
    private final CrsLessonMapper lessonMapper;
    private final CourseAccessGuard guard;
    private final CourseCounterService counterService;
    private final ResourceGrantReader grantReader;
    private final InlineFileUrlProvider inlineFileUrlProvider;
    private final NodeNameReader nodeNameReader;
    private final UserNameReader userNameReader;
    private final VideoLessonInspector videoLessonInspector;

    public CourseService(CrsCourseMapper courseMapper,
                         CrsChapterMapper chapterMapper,
                         CrsLessonMapper lessonMapper,
                         CourseAccessGuard guard,
                         CourseCounterService counterService,
                         ResourceGrantReader grantReader,
                         InlineFileUrlProvider inlineFileUrlProvider,
                         NodeNameReader nodeNameReader,
                         UserNameReader userNameReader,
                         VideoLessonInspector videoLessonInspector) {
        this.courseMapper = courseMapper;
        this.chapterMapper = chapterMapper;
        this.lessonMapper = lessonMapper;
        this.guard = guard;
        this.counterService = counterService;
        this.grantReader = grantReader;
        this.inlineFileUrlProvider = inlineFileUrlProvider;
        this.nodeNameReader = nodeNameReader;
        this.userNameReader = userNameReader;
        this.videoLessonInspector = videoLessonInspector;
    }

    // =====================================================================
    // 接口 1 §1.1 课程分页列表
    // =====================================================================

    public PageResult<CourseListVO> page(CoursePageQuery query) {
        Long myNodeId = guard.myNodeId();
        List<Long> grantedIds = grantReader.grantedResourceIds(ResourceType.COURSE, myNodeId);

        LambdaQueryWrapper<CrsCourse> wrapper = new LambdaQueryWrapper<>();
        applyVisibility(wrapper, myNodeId, grantedIds, query.getGrantType());
        if (wrapper == null) {
            return PageResult.empty();
        }
        if (query.getCourseName() != null && !query.getCourseName().isBlank()) {
            wrapper.like(CrsCourse::getCourseName, query.getCourseName().trim());
        }
        if (query.getSubject() != null && !query.getSubject().isBlank()) {
            wrapper.eq(CrsCourse::getSubject, query.getSubject().trim());
        }
        if (query.getStatus() != null) {
            wrapper.eq(CrsCourse::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(CrsCourse::getCreateTime).orderByDesc(CrsCourse::getId);

        IPage<CrsCourse> page = courseMapper.selectPage(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())), wrapper);

        List<CrsCourse> rows = page.getRecords();
        Map<Long, String> nodeNames = nodeNameReader.nodeNames(
                rows.stream().map(CrsCourse::getOwnerNodeId).toList());
        // grantedNodeCount 只对自有行取真实值 —— §1.1「下级不得窥探同级/上级的授权面」
        List<Long> ownIds = rows.stream()
                .filter(c -> myNodeId.equals(c.getOwnerNodeId()))
                .map(CrsCourse::getId).toList();
        Map<Long, Integer> grantedCounts = grantReader.countActiveTargets(ResourceType.COURSE, ownIds);

        List<CourseListVO> list = new ArrayList<>(rows.size());
        for (CrsCourse course : rows) {
            boolean own = myNodeId.equals(course.getOwnerNodeId());
            CourseListVO vo = new CourseListVO();
            vo.setId(course.getId());
            vo.setCourseName(course.getCourseName());
            vo.setCoverFileId(course.getCoverFileId());
            vo.setCoverUrl(signedCoverUrl(course.getCoverFileId()));
            vo.setSubject(course.getSubject());
            vo.setOwnerNodeId(course.getOwnerNodeId());
            vo.setOwnerNodeName(nodeNames.get(course.getOwnerNodeId()));
            vo.setGrantType(own ? 1 : 2);
            vo.setStatus(course.getStatus());
            vo.setLessonCount(course.getLessonCount());
            vo.setTotalDuration(course.getTotalDuration());
            vo.setGrantedNodeCount(own ? grantedCounts.getOrDefault(course.getId(), 0) : null);
            vo.setCreateTime(course.getCreateTime());
            vo.setUpdateTime(course.getUpdateTime());
            list.add(vo);
        }
        return PageResult.of(page.getTotal(), list);
    }

    /**
     * 把 §0.2 的可见性判定翻成 wrapper 条件。
     *
     * <p><b>实现已抽到 {@link CourseVisibilityPredicate}</b>：模块 11 的接口 37
     *（我可授权的资源列表）要的是同一条谓词，只是 ID 清单换成了 {@code canRegrant} 口径。
     * 照抄一遍就是两份同源实现，而两份都返回 200。
     *
     * @return 传入的 wrapper；若筛选条件导致<b>结果必然为空</b>（只要被授权而一条授权都没有）
     *         则返回 {@code null}，调用方直接回空页 —— 拼一个 {@code id IN ()} 是语法错误
     */
    private LambdaQueryWrapper<CrsCourse> applyVisibility(LambdaQueryWrapper<CrsCourse> wrapper,
                                                          Long myNodeId, List<Long> grantedIds,
                                                          Integer grantType) {
        return CourseVisibilityPredicate.apply(wrapper, myNodeId, grantedIds, grantType);
    }

    // =====================================================================
    // 接口 2 §1.2 课程详情
    // =====================================================================

    public CourseDetailVO detail(Long courseId) {
        Long myNodeId = guard.myNodeId();
        // 路径上的资源：不存在与不可见同为 404（F-42 定案）
        CrsCourse course = guard.loadVisible(CourseRef.PATH, courseId);

        CourseDetailVO vo = new CourseDetailVO();
        vo.setId(course.getId());
        vo.setCourseName(course.getCourseName());
        vo.setCoverFileId(course.getCoverFileId());
        vo.setCoverUrl(signedCoverUrl(course.getCoverFileId()));
        vo.setSubject(course.getSubject());
        vo.setDescription(course.getDescription());
        vo.setOwnerNodeId(course.getOwnerNodeId());
        vo.setOwnerNodeName(nodeNameReader.nodeNames(List.of(course.getOwnerNodeId()))
                .get(course.getOwnerNodeId()));
        vo.setGrantType(guard.isOwned(course, myNodeId) ? 1 : 2);
        vo.setStatus(course.getStatus());
        vo.setLessonCount(course.getLessonCount());
        vo.setTotalDuration(course.getTotalDuration());
        vo.setRemark(course.getRemark());
        vo.setCreateBy(course.getCreateBy());
        vo.setCreateByName(course.getCreateBy() == null ? null
                : userNameReader.realNames(List.of(course.getCreateBy())).get(course.getCreateBy()));
        vo.setCreateTime(course.getCreateTime());
        vo.setUpdateBy(course.getUpdateBy());
        vo.setUpdateTime(course.getUpdateTime());
        return vo;
    }

    // =====================================================================
    // 接口 3 §1.3 创建课程
    // =====================================================================

    /**
     * {@code owner_node_id} 由服务端强制写入创建者所在节点，<b>请求体不接受该参数</b>
     * （§1.3 说明「传入即忽略」）—— {@link CourseCreateReq} 里根本没有这个字段，
     * 这比「读了再忽略」更难写错。
     */
    @Transactional(rollbackFor = Exception.class)
    public CreatedIdVO create(CourseCreateReq req) {
        Long myNodeId = guard.myNodeId();
        CrsCourse course = new CrsCourse();
        course.setCourseName(req.getCourseName().trim());
        course.setOwnerNodeId(myNodeId);
        course.setCoverFileId(req.getCoverFileId());
        course.setSubject(trimToNull(req.getSubject()));
        course.setDescription(trimToNull(req.getDescription()));
        course.setRemark(trimToNull(req.getRemark()));
        course.setStatus(CrsCourse.STATUS_DRAFT);
        course.setLessonCount(0);
        course.setTotalDuration(0);
        courseMapper.insert(course);

        CreatedIdVO vo = new CreatedIdVO();
        vo.setId(course.getId());
        return vo;
    }

    // =====================================================================
    // 接口 4 §1.4 修改课程
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long courseId, CourseUpdateReq req) {
        CrsCourse course = guard.loadOwned(CourseRef.PATH, courseId);
        CrsCourse patch = new CrsCourse();
        patch.setId(course.getId());
        patch.setCourseName(req.getCourseName().trim());
        patch.setCoverFileId(req.getCoverFileId());
        patch.setSubject(trimToNull(req.getSubject()));
        patch.setDescription(trimToNull(req.getDescription()));
        patch.setRemark(trimToNull(req.getRemark()));
        // updateById 只更新非 null 字段，因此「把简介清空」需要显式 UpdateWrapper；
        // §1.4 的语义是全量覆盖基本信息，这里用 set-null 的写法保持与分册一致
        courseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CrsCourse>()
                .eq(CrsCourse::getId, course.getId())
                .set(CrsCourse::getCourseName, patch.getCourseName())
                .set(CrsCourse::getCoverFileId, patch.getCoverFileId())
                .set(CrsCourse::getSubject, patch.getSubject())
                .set(CrsCourse::getDescription, patch.getDescription())
                .set(CrsCourse::getRemark, patch.getRemark()));
    }

    // =====================================================================
    // 接口 5 §1.5 删除课程
    // =====================================================================

    /**
     * 逻辑删除，级联逻辑删除其下全部章节与课时（§1.5 说明）。
     *
     * <p><b>{@code org_resource_grant} 的授权行一律保留、不做级联撤销</b>
     * （契约 §2.5 规则 12 逐字：「任何『删除资源』接口都不得写『级联撤销其全部授权行』」）——
     * 资源状态可逆而级联撤销不可逆，一次误删就会清空全机构授权。
     * 学生已产生的 {@code vod_watch_progress} 同样保留不动。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long courseId) {
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.PATH, courseId);
        if (course.getStatus() != null && course.getStatus() == CrsCourse.STATUS_ON_SHELF) {
            throw new BizException(ErrorCode.COURSE_STATUS_NOT_ALLOWED,
                    "课程当前状态不允许该操作：已上架课程须先下架");
        }
        Long operator = com.edumatrix.common.tenant.TenantHelper.getUserId();
        lessonMapper.softDeleteByCourse(course.getId(), operator);
        chapterMapper.delete(new LambdaQueryWrapper<CrsChapter>()
                .eq(CrsChapter::getCourseId, course.getId()));
        courseMapper.deleteById(course.getId());
    }

    // =====================================================================
    // 接口 6 §1.6 课程上下架
    // =====================================================================

    /**
     * <b>判定顺序表</b>（仿 03-02 §3.4 的写法，任一失败即整体拒绝）：
     *
     * <table border="1">
     *   <caption>§1.6 的四条业务规则逐条落地</caption>
     *   <tr><th>#</th><th>判定</th><th>失败</th><th>依据</th></tr>
     *   <tr><td>1</td><td>课程存在未删 → 可见 → owner</td><td>{@code 20004} / 404 / 403</td>
     *       <td>{@link CourseAccessGuard}</td></tr>
     *   <tr><td>2</td><td>{@code targetStatus ∈ {1,2}}</td><td>400</td><td>§1.6 规则 1</td></tr>
     *   <tr><td>3</td><td>状态流转合法：0→1、2→1、1→2</td><td><b>{@code 20005}</b></td>
     *       <td>§1.6 规则 3（重复上架、草稿直接下架等）</td></tr>
     *   <tr><td>4</td><td>上架时：至少 1 个 {@code status=1} 可见课时</td>
     *       <td><b>{@code 20005}</b></td>
     *       <td>§1.6 规则 2 前半句。<b>该场景分册没给码</b>，G 定案复用 {@code 20005}
     *           并在 msg 里写明 —— 它的含义正是「课程当前状态不允许该操作」</td></tr>
     *   <tr><td>5</td><td>上架时：<b>全部</b>视频课时关联视频 {@code status=2}</td>
     *       <td><b>{@code 20003}</b></td>
     *       <td>§1.6 规则 2 后半句。<b>这是 {@code 20003} 在本模块唯一的落点</b>；
     *           课时侧的「关联视频状态不可用」一律用 {@code 20008}</td></tr>
     * </table>
     *
     * <p><b>第 5 条按 §1.6 的字面覆盖「全部视频课时」，含隐藏课时。</b>
     * 与 B 定案（创建隐藏课时时不校验视频状态）叠加后的效果是：
     * 隐藏课时指向的视频没转完，同样会拦住上架。这是 §1.6 原文的行为，
     * B 定案只推翻了 §3.3 / §3.4，没有动 §1.6；失败文案会点名到具体课时，不会让人猜。
     */
    @Transactional(rollbackFor = Exception.class)
    public CourseShelfVO shelf(Long courseId, CourseShelfReq req) {
        CrsCourse course = guard.loadOwnedForUpdate(CourseRef.PATH, courseId);
        int target = req.getTargetStatus();
        int current = course.getStatus() == null ? CrsCourse.STATUS_DRAFT : course.getStatus();

        boolean legal = (target == CrsCourse.STATUS_ON_SHELF
                            && (current == CrsCourse.STATUS_DRAFT || current == CrsCourse.STATUS_OFF_SHELF))
                || (target == CrsCourse.STATUS_OFF_SHELF && current == CrsCourse.STATUS_ON_SHELF);
        if (!legal) {
            throw new BizException(ErrorCode.COURSE_STATUS_NOT_ALLOWED,
                    "课程当前状态不允许该操作：当前 " + current + "，不可流转到 " + target);
        }

        if (target == CrsCourse.STATUS_ON_SHELF) {
            assertReadyForShelf(course);
        }

        CrsCourse patch = new CrsCourse();
        patch.setId(course.getId());
        patch.setStatus(target);
        courseMapper.updateById(patch);

        CourseShelfVO vo = new CourseShelfVO();
        vo.setId(course.getId());
        vo.setStatus(target);
        return vo;
    }

    private void assertReadyForShelf(CrsCourse course) {
        Long visible = lessonMapper.selectCount(new LambdaQueryWrapper<CrsLesson>()
                .eq(CrsLesson::getCourseId, course.getId())
                .eq(CrsLesson::getStatus, CrsLesson.STATUS_VISIBLE));
        if (visible == null || visible == 0) {
            throw new BizException(ErrorCode.COURSE_STATUS_NOT_ALLOWED,
                    "课程当前状态不允许该操作：课程没有任何可见课时，不可上架");
        }
        videoLessonInspector.assertAllVideosTranscoded(course.getId());
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 见类注释：现签，绝不读 {@code sys_file.file_url}。 */
    private String signedCoverUrl(Long coverFileId) {
        return coverFileId == null ? null
                : inlineFileUrlProvider.inlineSignedUrl(coverFileId).orElse(null);
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    static Optional<String> optional(String value) {
        return Optional.ofNullable(trimToNull(value));
    }
}
