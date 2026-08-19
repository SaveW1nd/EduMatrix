package com.edumatrix.course.catalog.service;

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
 *       <td><b>{@code 20004}</b></td><td>03-03 §0.3「课程不存在、已逻辑删除」</td></tr>
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

    /** 判定 2：存在且未删除，否则 {@code 20004}。 */
    public CrsCourse loadExisting(Long courseId) {
        CrsCourse course = courseId == null ? null : courseMapper.selectById(courseId);
        if (course == null) {
            throw new BizException(ErrorCode.COURSE_NOT_FOUND);
        }
        return course;
    }

    /** 判定 2 + 3：读操作用。不可见 → 404。 */
    public CrsCourse loadVisible(Long courseId) {
        CrsCourse course = loadExisting(courseId);
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
    public CrsCourse loadOwned(Long courseId) {
        Long myNodeId = myNodeId();
        CrsCourse course = loadExisting(courseId);
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
    public CrsCourse loadOwnedForUpdate(Long courseId) {
        CrsCourse course = loadOwned(courseId);
        courseMapper.lockForUpdate(course.getId());
        return course;
    }
}
