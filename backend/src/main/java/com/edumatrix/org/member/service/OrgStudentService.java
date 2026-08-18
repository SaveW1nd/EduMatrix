package com.edumatrix.org.member.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.member.dto.StudentCreateReq;
import com.edumatrix.org.member.dto.StudentPageQuery;
import com.edumatrix.org.member.dto.StudentUpdateReq;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.mapper.MemberQueryMapper;
import com.edumatrix.org.member.mapper.OrgStudentMapper;
import com.edumatrix.org.member.mapper.OrgTeacherMapper;
import com.edumatrix.org.member.vo.MemberCreatedVO;
import com.edumatrix.org.member.vo.StudentVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.service.CurrentNodeResolver;

/**
 * 学生管理的建 / 改 / 删 / 列（03-02 §6.1~§6.4，接口 16 / 17 / 18 / 19）。
 *
 * <p>归属变更走 {@code StudentAssignService}（接口 20/21/22），
 * 学籍状态变更走 {@code StudentLifecycleService}（接口 23/24/25）——
 * 分册对这两条边界各写了一句，拆成三个 Service 是为了让「改学生」这个入口
 * <b>物理上没有</b>改父与改学籍的能力。
 *
 * <p><b>本节路径 {@code {id}} 是学生档案 ID（{@code org_student.id}）。</b>
 */
@Service
public class OrgStudentService {

    private static final String ROLE_KEY = "student";

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MemberWriteSupport writeSupport;
    private final MemberQueryMapper queryMapper;
    private final OrgStudentMapper studentMapper;
    private final OrgTeacherMapper teacherMapper;
    private final OrgNodeMapper nodeMapper;
    private final CurrentNodeResolver currentNodeResolver;
    private final MemberOperLogWriter operLogWriter;
    private final StudentStatusGuard statusGuard;

    public OrgStudentService(MemberWriteSupport writeSupport,
                             MemberQueryMapper queryMapper,
                             OrgStudentMapper studentMapper,
                             OrgTeacherMapper teacherMapper,
                             OrgNodeMapper nodeMapper,
                             CurrentNodeResolver currentNodeResolver,
                             MemberOperLogWriter operLogWriter,
                             StudentStatusGuard statusGuard) {
        this.writeSupport = writeSupport;
        this.queryMapper = queryMapper;
        this.studentMapper = studentMapper;
        this.teacherMapper = teacherMapper;
        this.nodeMapper = nodeMapper;
        this.currentNodeResolver = currentNodeResolver;
        this.operLogWriter = operLogWriter;
        this.statusGuard = statusGuard;
    }

    // =====================================================================
    // 接口 16 §6.1 学生分页列表
    // =====================================================================

    /**
     * <p>{@code status} <b>不传查全部</b>（§6.1 参数表），与接口 15 的「默认 0 在读」相反。
     *
     * <p><b>没有 {@code org_student} 档案行的学生节点查不出来</b>，而且这不是过滤掉的 ——
     * 本查询以 {@code org_student} 为驱动表，那种节点连主键都没有。
     * 理由与它带来的一个后果（教师删不掉）见 {@code MemberQueryMapper#pageStudents}
     * 与 04-实施计划.md §E 的 F-22。
     */
    public PageResult<StudentVO> page(StudentPageQuery query) {
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();
        OrgNode root = resolveScopeRoot(query.getNodeId(), myNodeId);

        IPage<MemberQueryMapper.StudentRow> result = queryMapper.pageStudents(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())),
                root.getId(), root.selfPrefix(),
                Boolean.TRUE.equals(query.getDirectOnly()),
                Boolean.TRUE.equals(query.getUnassigned()),
                query.getStatus(),
                OrgAdminService.blankToNull(query.getRealName()),
                OrgAdminService.blankToNull(query.getStudentNo()),
                OrgAdminService.blankToNull(query.getPhone()),
                parseDateTime(query.getBeginTime()), parseDateTime(query.getEndTime()));

        List<StudentVO> list = new ArrayList<>(result.getRecords().size());
        for (MemberQueryMapper.StudentRow row : result.getRecords()) {
            list.add(toVO(row));
        }
        return PageResult.of(result.getTotal(), list);
    }

    // =====================================================================
    // 接口 17 §6.2 创建学生
    // =====================================================================

    /**
     * <b>三写一事务</b>：{@code sys_user} + {@code org_node} + {@code org_student}
     * 任一失败整体回滚（PRD F1-3 规则 1）。
     *
     * <h2>{@code guardianConsent} 在进到这里之前就已经被拦下</h2>
     * <p>它在 {@code StudentCreateReq} 上是 {@code @AssertTrue}，参数校验阶段就返回 {@code 400}，
     * <b>请求根本进不到本方法</b> —— PRD F7-1 的自检项「未勾选监护人同意时返回 400 且
     * <b>无任何节点/账号/档案产生</b>」因此是结构上成立的，而不是靠「记得先判一下」。
     *
     * <h2>默认挂创建者所在节点（PRD F1-3 规则 4）</h2>
     * <p>一条规则同时实现两种语义：管理员创建 → 父节点是管理员 → 「已归属该管理员、
     * 尚未分配导师」；<b>教师创建 → 父节点是教师 → 即刻成为其名下学员</b>。
     * <b>后半句要求教师能调本接口</b>，而 03-02 §6.2 的权限栏写的是「仅 org_admin」——
     * 已登记为 04-实施计划.md §E 的 <b>F-29</b>（三比一：PRD、本工单规则 4、菜单绑定数据
     * 都要求教师可建）。实现按菜单数据，见 {@code OrgStudentController#create}。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberCreatedVO create(StudentCreateReq req) {
        Long parentNodeId = req.getParentNodeId() == null
                ? currentNodeResolver.requireCurrentNodeId()
                : req.getParentNodeId();

        // 【写入之前把校验做完】父节点在子树内 → 10107；学号唯一 → 10202；在读上限 → 10207。
        // 自检项「未勾选监护人同意时无任何节点/账号/档案产生」验的就是这个顺序
        OrgNode parent = writeSupport.requireParentInMyScope(parentNodeId);
        assertStudentNoFree(req.getStudentNo(), null);
        assertStudentQuotaNotExceeded(parent.getTenantId());

        MemberWriteSupport.PersonCreated created = writeSupport.createPerson(
                new MemberWriteSupport.PersonCreateCmd(
                        parentNodeId, NodePath.NODE_TYPE_STUDENT, ROLE_KEY,
                        req.getRealName(), req.getPhone(), req.getUsername(),
                        // 学生节点名恒取姓名：§6.2 的参数表【没有】 nodeName 这个参数
                        null, null, req.getInitPassword(), req.getRemark()));

        OrgStudent student = new OrgStudent();
        student.setNodeId(created.nodeId());
        student.setUserId(created.userId());
        student.setStudentNo(req.getStudentNo());
        student.setGuardianName(OrgAdminService.blankToNull(req.getGuardianName()));
        student.setGuardianPhone(OrgAdminService.blankToNull(req.getGuardianPhone()));
        student.setStatus(OrgStudent.STATUS_ACTIVE);
        student.setRemark(req.getRemark());
        student.setTenantId(parent.getTenantId());
        studentMapper.insert(student);

        // 冗余计数：沿新祖先链 student_count + 1；父节点是教师时 org_teacher.student_count + 1
        // （§6.2 步骤 4；父节点非教师时匹配 0 行、静默无事发生 —— org_teacher 有 uk_node_id）
        nodeMapper.addStudentCount(ancestorChainOf(parent), 1);
        teacherMapper.addStudentCount(parentNodeId, 1);

        // 【F7-1 监护人同意留痕，与建档同事务】留痕与建档必须同生共死：
        // 建档回滚了却留下一条「已取得监护人同意」，比没有这条记录更糟
        operLogWriter.guardianConsent(created.userId(), parent.getTenantId());

        writeSupport.warnTemplateNotApplied(req.getTemplateId(), created.nodeId());

        MemberCreatedVO vo = new MemberCreatedVO();
        vo.setId(student.getId());
        vo.setNodeId(created.nodeId());
        vo.setUserId(created.userId());
        vo.setUsername(created.username());
        vo.setInitPassword(created.plainPassword());
        vo.setPwdResetFlag(1);
        vo.setParentNodeId(parentNodeId);
        vo.setAncestors(created.ancestors());
        vo.setNodePath(writeSupport.nodePath(created.nodeId()));
        vo.setStatus(OrgStudent.STATUS_ACTIVE);
        vo.setChangeType(1);
        return vo;
    }

    // =====================================================================
    // 接口 18 §6.3 修改学生
    // =====================================================================

    /**
     * <b>已退课或已归档的学生不可修改 → {@code 10203}</b>（§6.3）。
     * 归属变更走 20/21/22，学籍状态变更走 23/24/25 —— 本方法两样都做不到。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long studentId, StudentUpdateReq req) {
        OrgStudent student = statusGuard.assertActive(studentId);
        writeSupport.requireNodeInMyScope(student.getNodeId(), NodePath.NODE_TYPE_STUDENT);
        assertStudentNoFree(req.getStudentNo(), studentId);

        // §6.3 参数表：phone「原 username 即手机号时同步更新登录账号」
        String currentUsername = writeSupport.usernameOf(student.getUserId());
        String newUsername = null;
        if (currentUsername != null && currentUsername.equals(phoneOf(student.getUserId()))) {
            newUsername = req.getPhone();
        }
        writeSupport.updateAccount(student.getUserId(), req.getRealName(),
                req.getPhone(), newUsername);

        // 学生节点名与 real_name 同步（org_node.node_name 的 DDL 注释：「与 sys_user.real_name 同步」）
        OrgNode node = nodeMapper.selectById(student.getNodeId());
        writeSupport.updateNodeProfile(node, req.getRealName(), null, req.getRemark());

        OrgStudent update = new OrgStudent();
        update.setId(studentId);
        update.setStudentNo(req.getStudentNo());
        update.setGuardianName(OrgAdminService.blankToNull(req.getGuardianName()));
        update.setGuardianPhone(OrgAdminService.blankToNull(req.getGuardianPhone()));
        update.setRemark(req.getRemark());
        update.setUpdateBy(TenantHelper.getUserId());
        studentMapper.updateById(update);
    }

    // =====================================================================
    // 接口 19 §6.4 删除学生
    // =====================================================================

    /**
     * 逻辑删除。
     *
     * <p><b>学习记录一律保留不删</b>：{@code vod_watch_progress}、{@code hw_answer_sheet}、
     * {@code hw_wrong_book}、{@code stat_student_daily}（契约 §2.2 / §2.5 规则 5）。
     * 本方法因此<b>只碰四张表</b>：{@code org_node} / {@code org_student} / {@code sys_user}
     * / {@code sys_user_role}，外加两个冗余计数。
     *
     * <p><b>冗余计数只在原为在读时才减</b>（§6.4 原文：「学生<b>原为在读</b>（{@code status=0}）
     * 时沿祖先链 {@code student_count - 1}」）—— 已退课/已归档的学员早在退课/归档那一刻
     * 就已经从计数里减过一次，这里再减就会把计数减成负数。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long studentId) {
        OrgStudent student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        OrgNode node = writeSupport.requireNodeInMyScope(
                student.getNodeId(), NodePath.NODE_TYPE_STUDENT);

        boolean wasActive = student.isActive();
        studentMapper.deleteById(studentId);
        writeSupport.deletePerson(node);

        if (wasActive) {
            OrgNode parent = nodeMapper.selectById(node.getParentId());
            if (parent != null) {
                nodeMapper.addStudentCount(ancestorChainOf(parent), -1);
            }
            teacherMapper.addStudentCount(node.getParentId(), -1);
        }
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 祖先链上要维护 {@code student_count} 的节点：<b>父节点自身 + 它的全部祖先</b>。
     *
     * <p>写法与 {@code NodeMoveService#ancestorChainOf} 同源；
     * {@code NodePath.parseAncestorIds} 会跳过首位哨兵 {@code 0} —— 平台根不属于任何租户，
     * 它的 {@code student_count} 没有语义。
     */
    static List<Long> ancestorChainOf(OrgNode parent) {
        List<Long> ids = new ArrayList<>(NodePath.parseAncestorIds(parent.getAncestors()));
        if (parent.getId() != null && parent.getId() != OrgNode.PLATFORM_ROOT_ID
                && !ids.contains(parent.getId())) {
            ids.add(parent.getId());
        }
        return ids;
    }

    /**
     * 在读学生总数不得超过 {@code sys_tenant.max_student_count} → {@code 10207}（§6.2「上限校验」）。
     *
     * <p><b>口径与 {@code system/user/mapper/StudentQuotaMapper} 一致</b>：按
     * {@code org_student.status = 0} 的行数计（F-22 定案，两处必须同口径，
     * 否则同一个租户会算出两个学生数）。
     *
     * <p><b>与 03-01 §2.2 那条路径的差别</b>：那里 {@code 10207} <b>实际不可达</b>
     * （该路径不写 {@code org_student} 档案，建出的学生永远不是「在读」，
     * 见 {@code StudentQuotaMapper} 的类注释）；<b>本接口写档案，所以这里是它唯一真正能触发的地方</b>。
     *
     * <p>{@code max_student_count} 为 {@code null} 或 {@code <= 0} 表示不限。
     */
    private void assertStudentQuotaNotExceeded(Long tenantId) {
        Integer max = studentMapper.selectMaxStudentCount(tenantId);
        if (max == null || max <= 0) {
            return;
        }
        if (studentMapper.countActiveStudents() + 1 > max) {
            throw new BizException(ErrorCode.STUDENT_COUNT_EXCEEDS_LIMIT);
        }
    }

    private void assertStudentNoFree(String studentNo, Long excludeId) {
        if (studentNo != null && studentMapper.countByStudentNo(studentNo, excludeId) > 0) {
            throw new BizException(ErrorCode.STUDENT_NO_ALREADY_EXISTS);
        }
    }

    private String phoneOf(Long userId) {
        return writeSupport.phoneOf(userId);
    }

    private OrgNode resolveScopeRoot(Long nodeId, Long myNodeId) {
        if (nodeId == null) {
            OrgNode mine = nodeMapper.selectById(myNodeId);
            if (mine == null) {
                throw new BizException(ErrorCode.NODE_NOT_FOUND);
            }
            return mine;
        }
        return writeSupport.requireParentInMyScope(nodeId);
    }

    private static LocalDateTime parseDateTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value, DATE_TIME);
    }

    StudentVO toVO(MemberQueryMapper.StudentRow row) {
        StudentVO vo = new StudentVO();
        vo.setId(row.getId());
        vo.setNodeId(row.getNodeId());
        vo.setUserId(row.getUserId());
        vo.setUsername(row.getUsername());
        vo.setStudentNo(row.getStudentNo());
        vo.setRealName(row.getRealName());
        vo.setPhone(row.getPhone());
        vo.setGuardianName(row.getGuardianName());
        vo.setGuardianPhone(row.getGuardianPhone());
        vo.setParentNodeId(row.getParentNodeId());
        vo.setParentNodeName(row.getParentNodeName());
        vo.setParentNodeType(row.getParentNodeType());
        // teacherNodeId 仅在父节点是教师时有值（§6.1 字段说明：「父节点非教师节点时为 null」）
        boolean underTeacher = row.getParentNodeType() != null
                && row.getParentNodeType() == NodePath.NODE_TYPE_TEACHER;
        vo.setTeacherNodeId(underTeacher ? row.getParentNodeId() : null);
        vo.setTeacherName(underTeacher ? row.getParentNodeName() : null);
        vo.setNodePath(writeSupport.nodePath(row.getNodeId()));
        vo.setStatus(row.getStatus());
        vo.setQuitTime(row.getQuitTime());
        vo.setQuitReason(row.getQuitReason());
        vo.setArchiveTime(row.getArchiveTime());
        vo.setArchiveReason(row.getArchiveReason());
        vo.setAnonymizedAt(row.getAnonymizedAt());
        vo.setRemark(row.getRemark());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }
}
