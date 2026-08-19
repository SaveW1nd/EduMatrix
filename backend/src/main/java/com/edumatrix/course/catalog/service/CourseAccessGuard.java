package com.edumatrix.course.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.course.catalog.entity.CrsCourse;
import com.edumatrix.course.catalog.mapper.CrsCourseMapper;

/**
 * 课程的<b>可见性</b>与<b>编排权限</b>判定 —— 本模块所有接口的唯一入口。
 *
 * <h2>两条判定是两件事，顺序不能颠倒</h2>
 * <p>03-03 §0.2 开头逐字：「<b>两条彼此独立的判定必须同时满足</b>」——
 * ① 数据权限（看到哪些行，契约 §2.4 子树）；② 资源可见性（能用哪些课程，契约 §2.5，
 * <b>精确等于我的节点或被显式授权给我的节点</b>，不回溯祖先链）。
 * 对课程而言两条取交集后就是 ②，所以本类只判 ②。
 *
 * <table border="1">
 *   <caption>判定顺序（任一不过即终止）</caption>
 *   <tr><th>#</th><th>判定</th><th>不通过</th><th>依据</th></tr>
 *   <tr><td>1</td><td>{@code @SaCheckPermission}</td><td>403</td>
 *       <td>契约 §10 附表 A；不经过本类</td></tr>
 *   <tr><td>2</td><td>按 id 查到行（租户条件由插件注入，{@code deleted_at=0} 由 @TableLogic）</td>
 *       <td><b>随 {@link CourseRef} 而定</b>：{@code PATH} / {@code DERIVED} → <b>404</b>；
 *           {@code PARAM} → <b>{@code 20004}</b></td>
 *       <td>见下方「F-42 定案」</td></tr>
 *   <tr><td>3</td><td><b>资源可见性</b>：是 owner ∪ 被显式授权且在有效期内</td>
 *       <td><b>404</b></td>
 *       <td>03-03 §1.2「均不满足时返回 HTTP 404（不暴露存在性）」+ 契约 §2.4 三分法第 1 行</td></tr>
 *   <tr><td>4</td><td><b>编排权限</b>：{@code owner_node_id} <b>严格等于</b>我的节点</td>
 *       <td><b>403</b></td>
 *       <td>契约 §2.5 规则 8、PRD F2-1 规则 8、03-03 §0.2「只读可用 vs 可编辑」</td></tr>
 * </table>
 *
 * <p><b>3 在 4 之前是必要的</b>：先 404 再 403，被授权者才会拿到 403（可见但不可改），
 * 完全不相干的人拿到 404。反过来会把「存在一门我看不见的课」泄露出去。
 *
 * <h2>F-42 定案：路径上的资源，「不存在」与「不可见」必须给出同一个结果</h2>
 * <p>此前第 2 步恒为 {@code 20004}、第 3 步恒为 404，两条各自都是分册原文，
 * <b>合起来却能被拿来探测存在性</b>：拿到 {@code 20004} = 这个 id 在本租户不存在，
 * 拿到 404 = <b>存在但你看不到</b>。而 03-01 §7.2 恰恰用「雪花 ID 同租户内时间相邻、
 * 可近邻枚举」论证过详情接口不能下发直链 —— 同一条推理在这里指向相反的结论。
 *
 * <p>需方定案：<b>统一到 404</b>。不能反过来统一到业务码 ——
 * 契约 §2.4 三分法第 1 行「访问<b>路径上的资源</b>而该资源不在我的子树内 → <b>404</b>，
 * 不暴露存在性」是上位文档，动不得，所以能动的只有「不存在」那一侧。
 *
 * <h2>{@link CourseRef}：每个调用点必须显式说明 courseId 从哪来</h2>
 * <p><b>本类没有省略该参数的重载</b>，这是刻意的：新增接口时必须现场做一次选择，
 * 而不是照着最近的一行抄。三类的区别不是风格，是<b>返回码不同</b>。
 *
 * <h2>上级管理员对下级教师自建的课程既不可改、也看不到</h2>
 * <p>这是 03-03 §0.2 那两条判定取交集的<b>必然结果</b>，不是本类的额外收紧：
 * §0.2 逐字「不回溯祖先链、无继承……上级拥有 ≠ 我自动拥有；
 * <b>父级授权给了我的下级也不等于授权给了我</b>」，
 * §0.2 的角色表也写「管理员……课程/媒资范围 = 本节点 {@code owner_node_id} 的 +
 * 被显式授权给本节点的」。而契约 §2.5 规则 2「只能授权给自己<b>子树内</b>的节点」
 * 意味着下级无法向上授权，所以这条路在结构上不存在。
 * <b>连带后果</b>（教师离职后机构管理员无法接管其课程）已登记 F-47，不在本模块处置。
 *
 * <h2>编排权限与「子树通则」不冲突</h2>
 * <p>契约 §2.4 末段自己切开了：「<b>数据范围由树决定，操作权限由角色决定</b>。
 * 父节点决定子节点『能看到哪些数据』，<b>不决定『能执行哪些操作』</b>」。
 */
@Service
public class CourseAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(CourseAccessGuard.class);

    /**
     * 「这个 {@code courseId} 是从哪来的」—— 它决定<b>课程行查不到时返回什么</b>。
     *
     * <p>可见性（第 3 步）与编排权限（第 4 步）三类完全相同，只有第 2 步不同。
     */
    public enum CourseRef {

        /**
         * <b>路径上的资源</b>：{@code GET/PUT/DELETE /courses/{id}/...}，
         * 用户请求的就是这门课本身。
         *
         * <p>查不到 → <b>404</b>，与「存在但不可见」<b>同一个结果</b>（F-42 定案）。
         * 落点：§1.2 详情、§1.4 修改、§1.5 删除、§1.6 上下架、§2.1 章节树、§2.5 排序。
         */
        PATH,

        /**
         * <b>请求体 / 查询参数里显式指定的 {@code courseId}</b>：用户请求的是
         * 「章节集合」或「课时列表」，课程只是个筛选/归属参数。
         *
         * <p>查不到 → <b>{@code 20004}</b>。<b>这里不能返 404</b>：
         * 404 的语义是「你请求的那个资源不存在」，而此处被请求的资源存在与否
         * 与 courseId 是两件事，返 404 会指代不清（用户会以为是端点写错了）。
         * 与契约 §2.4 三分法第 3 行「请求体/参数中显式指定的目标越界 → 业务码而非静默 404」
         * 同一条理由。
         *
         * <p>落点：§2.2 创建章节（body {@code courseId}）、§3.1 课时列表（query {@code courseId}）。
         */
        PARAM,

        /**
         * <b>由已经读出来的行推导</b>：{@code chapter.getCourseId()} /
         * {@code lesson.getCourseId()}。用户请求的是那个章节/课时（路径上的 {@code {id}}），
         * 课程是顺着冗余外键找到的。
         *
         * <p>查不到 → <b>404</b>，与 {@link #PATH} 相同。两条理由：
         * <ol>
         *   <li><b>对调用方而言语义一致</b>：他请求的是 {@code /chapters/{id}}，
         *       而这个章节已经不可用了 —— 章节自身不存在时本来就返 404
         *       （§2.3 说明逐字），两种情形给同一个答案才不泄露「章节在、课程没了」；
         *   <li><b>它在正常数据下不该发生</b>：删课程会级联逻辑删章节与课时，
         *       所以查得到章节却查不到课程只有两种可能 —— 与删除课程<b>并发</b>的一次请求，
         *       或者数据损坏。
         * </ol>
         *
         * <p><b>因此这一类额外记一条 WARN</b>：对调用方它和 404 无差别（这是设计），
         * 但「悬挂的 course_id」是数据完整性异常，<b>不能只对人静默、也对日志静默</b> ——
         * 那就成了本项目反复点名的「不报错的故障」。
         */
        DERIVED
    }

    private final CrsCourseMapper courseMapper;
    private final ResourceGrantReader grantReader;
    private final CurrentNodeProvider currentNodeProvider;

    public CourseAccessGuard(CrsCourseMapper courseMapper,
                             ResourceGrantReader grantReader,
                             CurrentNodeProvider currentNodeProvider) {
        this.courseMapper = courseMapper;
        this.grantReader = grantReader;
        this.currentNodeProvider = currentNodeProvider;
    }

    /** 当前登录人所在节点；取不到抛 400（绝不退化为「不加过滤」，契约 §7.1）。 */
    public Long myNodeId() {
        return currentNodeProvider.requireCurrentNodeId();
    }

    /** 该课程对我可见吗（自有 ∪ 被授权且在有效期内）。 */
    public boolean isVisible(CrsCourse course, Long myNodeId) {
        return isOwned(course, myNodeId)
                || grantReader.hasGrant(ResourceType.COURSE, course.getId(), myNodeId);
    }

    /** 我是不是该课程的 owner（<b>严格相等</b>）。 */
    public boolean isOwned(CrsCourse course, Long myNodeId) {
        return course.getOwnerNodeId() != null && course.getOwnerNodeId().equals(myNodeId);
    }

    /**
     * 判定 2：存在且未删除。查不到时按 {@code ref} 决定返回什么，见 {@link CourseRef}。
     *
     * <p><b>没有省略 {@code ref} 的重载</b>：三类的返回码不同，必须现场选一次。
     */
    public CrsCourse loadExisting(CourseRef ref, Long courseId) {
        CrsCourse course = courseId == null ? null : courseMapper.selectById(courseId);
        if (course != null) {
            return course;
        }
        switch (ref) {
            case PARAM ->
                // 用户显式选了这个 courseId，需明确告诉他选错了（契约 §2.4 三分法第 3 行同理）
                    throw new BizException(ErrorCode.COURSE_NOT_FOUND);
            case DERIVED -> {
                // 查得到章节/课时却查不到课程 = 悬挂外键。对调用方与 404 无差别（F-42 定案），
                // 但它是数据完整性异常，必须在日志里看得见
                log.warn("悬挂的 course_id={}：章节/课时行存在而所属课程查不到（已删除 / 跨租户 / 数据损坏）。"
                        + "对调用方返回 404 与「课程不存在」同一个结果，见 CourseAccessGuard.CourseRef", courseId);
                throw BizException.notFound(courseId);
            }
            default -> throw BizException.notFound(courseId);
        }
    }

    /** 判定 2 + 3：读操作用。不可见 → 404。 */
    public CrsCourse loadVisible(CourseRef ref, Long courseId) {
        CrsCourse course = loadExisting(ref, courseId);
        if (!isVisible(course, myNodeId())) {
            throw BizException.notFound(courseId);
        }
        return course;
    }

    /**
     * 判定 2 + 3 + 4：写操作用。不可见 → 404；可见但非 owner → 403。
     *
     * <p><b>不取锁</b>。会改到章节/课时/冗余计数的写操作请用
     * {@link #loadOwnedForUpdate}，那才是编排的统一锁点。
     */
    public CrsCourse loadOwned(CourseRef ref, Long courseId) {
        Long myNodeId = myNodeId();
        CrsCourse course = loadExisting(ref, courseId);
        if (!isVisible(course, myNodeId)) {
            throw BizException.notFound(courseId);
        }
        if (!isOwned(course, myNodeId)) {
            // 可见但不是 owner —— 被授权者只读（契约 §2.5 规则 8）
            throw BizException.forbidden();
        }
        return course;
    }

    /**
     * 判定 2 + 3 + 4，并在<b>同一事务内</b>取课程行的排他锁。
     *
     * <p>编排类写操作（章节增删改排序、课时增删改、上下架、删课程）<b>一律走这个入口</b>。
     * 锁的必要性见 {@link CrsCourseMapper#lockForUpdate}。
     *
     * <p>调用方必须已在事务中，否则 {@code FOR UPDATE} 的锁在语句结束时即释放，
     * 等于没加。本类不标 {@code @Transactional} —— 事务边界属于调用它的那个业务方法。
     */
    public CrsCourse loadOwnedForUpdate(CourseRef ref, Long courseId) {
        CrsCourse course = loadOwned(ref, courseId);
        courseMapper.lockForUpdate(course.getId());
        return course;
    }
}
