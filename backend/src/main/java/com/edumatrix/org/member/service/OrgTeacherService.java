package com.edumatrix.org.member.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.member.dto.TeacherCreateReq;
import com.edumatrix.org.member.dto.TeacherPageQuery;
import com.edumatrix.org.member.dto.TeacherStudentQuery;
import com.edumatrix.org.member.dto.TeacherUpdateReq;
import com.edumatrix.org.member.entity.OrgTeacher;
import com.edumatrix.org.member.mapper.MemberQueryMapper;
import com.edumatrix.org.member.mapper.OrgTeacherMapper;
import com.edumatrix.org.member.vo.MemberCreatedVO;
import com.edumatrix.org.member.vo.TeacherStudentVO;
import com.edumatrix.org.member.vo.TeacherVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.service.CurrentNodeResolver;

/**
 * 教师管理（03-02 §5.1~§5.5，接口 11 / 12 / 13 / 14 / 15）。
 *
 * <p><b>本节路径 {@code {id}} 是教师<b>档案 ID</b>（{@code org_teacher.id}）</b>，
 * 不是节点 ID —— 与管理员那一节相反（§4.1 字段说明：管理员用 {@code nodeId}）。
 * 两节导语各写了一句，混用会让「删除教师」删到一个节点 id 恰好等于某档案 id 的对象。
 *
 * <h2>教师是<b>督学导师，不是授课老师</b></h2>
 * <p>这条语义决定了很多别处的设计（F-21 的「默认保持现状」、教师不需要课程授权），
 * 在本类里的体现是：{@code org_teacher} 没有课程/班级关联列，
 * <b>名下学员 = 该教师节点的直接子节点</b>，归属完全由树表达。
 */
@Service
public class OrgTeacherService {

    private static final String ROLE_KEY = "teacher";

    /** §5.5 的 {@code status} 参数：默认 0 在读；传 {@code -1} 表示查全部（对应分册的「传空串」）。 */
    private static final int STATUS_ALL = -1;

    private final MemberWriteSupport writeSupport;
    private final MemberQueryMapper queryMapper;
    private final OrgTeacherMapper teacherMapper;
    private final OrgNodeMapper nodeMapper;
    private final CurrentNodeResolver currentNodeResolver;

    public OrgTeacherService(MemberWriteSupport writeSupport,
                             MemberQueryMapper queryMapper,
                             OrgTeacherMapper teacherMapper,
                             OrgNodeMapper nodeMapper,
                             CurrentNodeResolver currentNodeResolver) {
        this.writeSupport = writeSupport;
        this.queryMapper = queryMapper;
        this.teacherMapper = teacherMapper;
        this.nodeMapper = nodeMapper;
        this.currentNodeResolver = currentNodeResolver;
    }

    // =====================================================================
    // 接口 11 §5.1 教师分页列表
    // =====================================================================

    public PageResult<TeacherVO> page(TeacherPageQuery query) {
        Long myNodeId = currentNodeResolver.requireCurrentNodeId();
        OrgNode root = resolveScopeRoot(query.getNodeId(), myNodeId);

        IPage<MemberQueryMapper.TeacherRow> result = queryMapper.pageTeachers(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())),
                root.getId(), root.selfPrefix(),
                OrgAdminService.blankToNull(query.getRealName()),
                OrgAdminService.blankToNull(query.getTeacherNo()),
                OrgAdminService.blankToNull(query.getSubject()),
                OrgAdminService.blankToNull(query.getPhone()),
                query.getStatus());

        Map<Long, String> parentNames = loadNodeNames(result.getRecords().stream()
                .map(MemberQueryMapper.TeacherRow::getParentNodeId).toList());

        List<TeacherVO> list = new ArrayList<>(result.getRecords().size());
        for (MemberQueryMapper.TeacherRow row : result.getRecords()) {
            TeacherVO vo = new TeacherVO();
            vo.setId(row.getId());
            vo.setNodeId(row.getNodeId());
            vo.setUserId(row.getUserId());
            vo.setUsername(row.getUsername());
            vo.setTeacherNo(row.getTeacherNo());
            vo.setRealName(row.getRealName());
            vo.setPhone(row.getPhone());
            vo.setSubject(row.getSubject());
            vo.setTitle(row.getTitle());
            vo.setEntryDate(row.getEntryDate());
            vo.setParentNodeId(row.getParentNodeId());
            vo.setParentNodeName(parentNames.get(row.getParentNodeId()));
            vo.setNodePath(writeSupport.nodePath(row.getNodeId()));
            vo.setStudentCount(row.getStudentCount());
            vo.setStatus(row.getStatus());
            vo.setRemark(row.getRemark());
            vo.setCreateTime(row.getCreateTime());
            vo.setUpdateTime(row.getUpdateTime());
            list.add(vo);
        }
        return PageResult.of(result.getTotal(), list);
    }

    // =====================================================================
    // 接口 12 §5.2 新建教师
    // =====================================================================

    /**
     * <b>三写一事务</b>：{@code sys_user} + {@code org_node} + {@code org_teacher}
     * 任一失败整体回滚（PRD F1-3 规则 1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberCreatedVO create(TeacherCreateReq req) {
        // 工号机构内唯一 → 10201。【在任何写入之前】判，与自检项「失败时无任何行产生」一致
        assertTeacherNoFree(req.getTeacherNo(), null);

        MemberWriteSupport.PersonCreated created = writeSupport.createPerson(
                new MemberWriteSupport.PersonCreateCmd(
                        req.getParentNodeId(), NodePath.NODE_TYPE_TEACHER, ROLE_KEY,
                        req.getRealName(), req.getPhone(), req.getUsername(),
                        req.getNodeName(), req.getSort(), req.getInitPassword(), req.getRemark()));

        OrgNode parent = nodeMapper.selectById(req.getParentNodeId());
        OrgTeacher teacher = new OrgTeacher();
        teacher.setNodeId(created.nodeId());
        teacher.setUserId(created.userId());
        teacher.setTeacherNo(req.getTeacherNo());
        teacher.setSubject(OrgAdminService.blankToNull(req.getSubject()));
        teacher.setTitle(OrgAdminService.blankToNull(req.getTitle()));
        teacher.setEntryDate(parseDate(req.getEntryDate()));
        // 新教师名下必然没有学员
        teacher.setStudentCount(0);
        teacher.setRemark(req.getRemark());
        teacher.setTenantId(parent.getTenantId());
        teacherMapper.insert(teacher);

        writeSupport.warnTemplateNotApplied(req.getTemplateId(), created.nodeId());

        MemberCreatedVO vo = new MemberCreatedVO();
        vo.setId(teacher.getId());
        vo.setNodeId(created.nodeId());
        vo.setUserId(created.userId());
        vo.setUsername(created.username());
        vo.setInitPassword(created.plainPassword());
        vo.setPwdResetFlag(1);
        vo.setParentNodeId(req.getParentNodeId());
        vo.setAncestors(created.ancestors());
        vo.setNodePath(writeSupport.nodePath(created.nodeId()));
        vo.setStudentCount(0);
        vo.setChangeType(1);
        return vo;
    }

    // =====================================================================
    // 接口 13 §5.3 修改教师
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long teacherId, TeacherUpdateReq req) {
        OrgTeacher teacher = requireTeacherInMyScope(teacherId);
        assertTeacherNoFree(req.getTeacherNo(), teacherId);
        OrgNode node = nodeMapper.selectById(teacher.getNodeId());

        writeSupport.updateAccount(teacher.getUserId(), req.getRealName(),
                req.getPhone(), OrgAdminService.blankToNull(req.getUsername()));
        writeSupport.updateNodeProfile(node, OrgAdminService.blankToNull(req.getNodeName()),
                req.getSort(), req.getRemark());

        OrgTeacher update = new OrgTeacher();
        update.setId(teacherId);
        update.setTeacherNo(req.getTeacherNo());
        update.setSubject(OrgAdminService.blankToNull(req.getSubject()));
        update.setTitle(OrgAdminService.blankToNull(req.getTitle()));
        update.setEntryDate(parseDate(req.getEntryDate()));
        update.setRemark(req.getRemark());
        update.setUpdateBy(TenantHelper.getUserId());
        teacherMapper.updateById(update);
    }

    // =====================================================================
    // 接口 14 §5.4 删除教师
    // =====================================================================

    /**
     * 逻辑删除。<b>名下仍有学员时禁止删除 → {@code 10206}</b>。
     *
     * <p>§5.4 逐字：「名下学员<b>即使全部已退课 / 已归档</b>，节点上仍挂着学生子节点，
     * 同样返回 {@code 10206}——请先转出再删除，<b>避免历史学员失去归属</b>」。
     * 所以判据是<b>节点级</b>的「有没有学生子节点」，<b>不是</b>「有没有在读学员」，
     * 更不是 {@code org_teacher.student_count}（那个只数在读）。
     *
     * <p><b>这条节点级判据有一个已登记的副作用</b>：超管经 03-01 §2.2 建出的
     * 无档案孤儿学生同样计入，而它在接口 15 / 16 里查不到、也无法经接口 20/21/22 转走
     * （那三个接口以档案 ID 寻址）—— 管理员会卡死。
     * 已作为新证据登记在 04-实施计划.md §E 的 <b>F-22</b>。
     * <b>本模块不改这条判据</b>：改它要么放宽 {@code 10206}、要么禁止 §2.2 建学生，
     * 两者都是 F-22 待定的内容。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long teacherId) {
        OrgTeacher teacher = requireTeacherInMyScope(teacherId);
        OrgNode node = nodeMapper.selectById(teacher.getNodeId());
        writeSupport.assertNotSelf(node.getId());

        long students = 0;
        for (OrgNodeMapper.NodeTypeCountRow row : nodeMapper.selectChildStat(node.getId())) {
            if (row.getNodeType() != null && row.getNodeType() == NodePath.NODE_TYPE_STUDENT) {
                students += row.getCnt() == null ? 0 : row.getCnt();
            }
        }
        if (students > 0) {
            throw new BizException(ErrorCode.TEACHER_STILL_HAS_STUDENTS);
        }

        teacherMapper.deleteById(teacherId);
        writeSupport.deletePerson(node);
    }

    // =====================================================================
    // 接口 15 §5.5 教师名下学员列表
    // =====================================================================

    /**
     * 与接口 16 传 {@code nodeId=教师节点ID} + {@code directOnly=true} <b>等价</b>（§5.5 原文），
     * 因此共用同一条 SQL —— 语义化入口另加 {@code assignTime} 与 {@code lastStudyTime}。
     *
     * <p>{@code status} <b>默认 0 在读</b>，与接口 16 的「不传查全部」相反（§5.5 参数表）。
     */
    public PageResult<TeacherStudentVO> students(Long teacherId, TeacherStudentQuery query) {
        OrgTeacher teacher = requireTeacherInMyScope(teacherId);
        OrgNode node = nodeMapper.selectById(teacher.getNodeId());

        Integer status = query.getStatus() == null ? 0 : query.getStatus();
        if (status == STATUS_ALL) {
            status = null;
        }

        IPage<MemberQueryMapper.StudentRow> result = queryMapper.pageStudents(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())),
                node.getId(), node.selfPrefix(), true, false, status,
                OrgAdminService.blankToNull(query.getRealName()), null, null, null, null);

        List<Long> nodeIds = result.getRecords().stream()
                .map(MemberQueryMapper.StudentRow::getNodeId).toList();
        Map<Long, java.time.LocalDateTime> assignTimes = new LinkedHashMap<>();
        if (!nodeIds.isEmpty()) {
            for (MemberQueryMapper.AssignTimeRow row : queryMapper.selectAssignTimes(nodeIds)) {
                assignTimes.put(row.getNodeId(), row.getAssignTime());
            }
        }

        List<TeacherStudentVO> list = new ArrayList<>(result.getRecords().size());
        for (MemberQueryMapper.StudentRow row : result.getRecords()) {
            TeacherStudentVO vo = new TeacherStudentVO();
            vo.setId(row.getId());
            vo.setNodeId(row.getNodeId());
            vo.setUserId(row.getUserId());
            vo.setStudentNo(row.getStudentNo());
            vo.setRealName(row.getRealName());
            vo.setPhone(row.getPhone());
            vo.setGuardianName(row.getGuardianName());
            vo.setGuardianPhone(row.getGuardianPhone());
            vo.setStatus(row.getStatus());
            vo.setAssignTime(assignTimes.get(row.getNodeId()));
            // lastStudyTime 在 vod_watch_progress（模块 13 的表），不在本模块涉及表内 —— 恒 null
            vo.setLastStudyTime(null);
            vo.setCreateTime(row.getCreateTime());
            list.add(vo);
        }
        return PageResult.of(result.getTotal(), list);
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 路径 {@code {id}} 上的教师档案：不存在或不在子树内一律 <b>404</b>
     * （§5.5「他人返回 404」、§5.3 / §5.4「不在子树内 10107」——
     * 后两者的路径参数同样是「我要操作的东西」，按契约 §2.4 三分法取 404；
     * 分册那两格写的 {@code 10107} 针对的是请求体里的目标，本接口的请求体里没有节点）。
     */
    private OrgTeacher requireTeacherInMyScope(Long teacherId) {
        OrgTeacher teacher = teacherId == null ? null : teacherMapper.selectById(teacherId);
        if (teacher == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        writeSupport.requireNodeInMyScope(teacher.getNodeId(), NodePath.NODE_TYPE_TEACHER);
        return teacher;
    }

    private void assertTeacherNoFree(String teacherNo, Long excludeId) {
        if (teacherNo != null && teacherMapper.countByTeacherNo(teacherNo, excludeId) > 0) {
            throw new BizException(ErrorCode.TEACHER_NO_ALREADY_EXISTS);
        }
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

    private Map<Long, String> loadNodeNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (OrgNode n : nodeMapper.selectByIds(distinct)) {
            names.put(n.getId(), n.getNodeName());
        }
        return names;
    }

    /** {@code yyyy-MM-dd}；空串与 {@code null} 都表示不设值（分册：入职日期非必填）。 */
    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
