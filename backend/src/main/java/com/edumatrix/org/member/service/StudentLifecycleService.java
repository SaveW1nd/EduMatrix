package com.edumatrix.org.member.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.member.dto.StudentArchiveReq;
import com.edumatrix.org.member.dto.StudentQuitReq;
import com.edumatrix.org.member.dto.StudentUnarchiveReq;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.mapper.OrgStudentMapper;
import com.edumatrix.org.member.mapper.OrgTeacherMapper;
import com.edumatrix.org.member.vo.AffectedTeacherVO;
import com.edumatrix.org.member.vo.StudentArchivedVO;
import com.edumatrix.org.member.vo.StudentQuitVO;
import com.edumatrix.org.member.vo.StudentUnarchivedVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.service.NodeChangeLogWriter;
import com.edumatrix.org.node.service.NodeMoveOptions;
import com.edumatrix.org.node.service.NodeMoveService;
import com.edumatrix.org.node.vo.NodeMovedVO;

/**
 * 退课 / 批量毕业归档 / 归档恢复（03-02 §6.8~§6.10，接口 23 / 24 / 25）。
 *
 * <h2>三个动作<b>都不移动节点</b>，除了恢复时可选的重新挂载</h2>
 * <p>模块 07 规则 15：「退课与归档<b>都不移动节点、不撤销授权、不删学习记录</b>」
 * （PRD F1-7 规则 4、F1-8 规则 7/8、边界 B11）。退课后学员仍留在原导师节点下，
 * 便于原责任人复盘与召回 —— 这是产品语义，不是省事。
 *
 * <p>因此它们写的轨迹里 {@code from_parent_id} 与 {@code to_parent_id} <b>相同</b>
 * （§6.8：「因节点未移动，两者均为当前父节点」）。
 *
 * <h2>{@code archiveReason} 决定脱不脱敏 —— 两条路后果相反且不可逆</h2>
 * <ul>
 *   <li>{@code 2} 因监护人删除请求 → 30 日撤回窗口 → 定时任务<b>脱敏</b>（契约 §7.2 第 3 条）；
 *   <li>{@code 1} 正常毕业 → 满 30 日<b>不脱敏</b>，毕业校友的联系方式必须保留（PRD F7-3）。
 * </ul>
 * <p>本类只负责<b>把 {@code archive_reason} 如实写下</b>；判定与执行在
 * {@code AnonymizeArchivedStudentJob}，扫描条件是三个与门。
 */
@Service
public class StudentLifecycleService {

    private final OrgStudentMapper studentMapper;
    private final OrgTeacherMapper teacherMapper;
    private final OrgNodeMapper nodeMapper;
    private final NodeChangeLogWriter changeLogWriter;
    private final NodeMoveService nodeMoveService;
    private final MemberWriteSupport writeSupport;
    private final StudentStatusGuard statusGuard;

    public StudentLifecycleService(OrgStudentMapper studentMapper,
                                   OrgTeacherMapper teacherMapper,
                                   OrgNodeMapper nodeMapper,
                                   NodeChangeLogWriter changeLogWriter,
                                   NodeMoveService nodeMoveService,
                                   MemberWriteSupport writeSupport,
                                   StudentStatusGuard statusGuard) {
        this.studentMapper = studentMapper;
        this.teacherMapper = teacherMapper;
        this.nodeMapper = nodeMapper;
        this.changeLogWriter = changeLogWriter;
        this.nodeMoveService = nodeMoveService;
        this.writeSupport = writeSupport;
        this.statusGuard = statusGuard;
    }

    // =====================================================================
    // 接口 23 §6.8 学生退课
    // =====================================================================

    /**
     * 退课是<b>流失口径的唯一数据来源</b>（PRD F1-7 规则 6）：{@code status} 0 → 1，
     * 记 {@code quit_time} / {@code quit_reason}，写 {@code change_type=7}。
     *
     * <p><b>退课学员仍可登录</b>（区别于归档）—— 仅无新内容下发。禁止登录要用归档。
     */
    @Transactional(rollbackFor = Exception.class)
    public StudentQuitVO quit(Long studentId, StudentQuitReq req) {
        OrgStudent student = statusGuard.assertActive(studentId);
        OrgNode node = writeSupport.requireNodeInMyScope(
                student.getNodeId(), NodePath.NODE_TYPE_STUDENT);

        LocalDateTime now = LocalDateTime.now();
        OrgStudent update = new OrgStudent();
        update.setId(studentId);
        update.setStatus(OrgStudent.STATUS_QUIT);
        update.setQuitTime(now);
        update.setQuitReason(req.getQuitReason());
        update.setUpdateBy(TenantHelper.getUserId());
        studentMapper.updateById(update);

        // 退出统计分母：沿祖先链 student_count - 1、原导师 org_teacher.student_count - 1
        decrementCounts(node);

        // 节点不移动 → from 与 to 都是当前父节点（§6.8 原文）
        changeLogWriter.write(node.getId(), OrgNodeChangeLog.CHANGE_TYPE_QUIT,
                node.getParentId(), node.getParentId(), req.getQuitReason(), node.getTenantId());

        StudentQuitVO vo = new StudentQuitVO();
        vo.setStudentId(studentId);
        vo.setNodeId(node.getId());
        vo.setRealName(node.getNodeName());
        vo.setStatus(OrgStudent.STATUS_QUIT);
        vo.setQuitTime(now);
        vo.setQuitReason(req.getQuitReason());
        vo.setChangeType(OrgNodeChangeLog.CHANGE_TYPE_QUIT);
        return vo;
    }

    // =====================================================================
    // 接口 24 §6.9 批量毕业归档
    // =====================================================================

    /**
     * {@code studentIds} 与 {@code nodeId} <b>二选一</b>，同时传或都不传 → {@code 400}。
     *
     * <p><b>两种模式对 {@code 10208} 的行为不同</b>（§6.9 原文）：
     * 名单模式下含非在读者<b>整体失败</b>；{@code nodeId} 模式「自动只取在读学员，
     * <b>因此不会触发该码</b>」—— 后者的 {@code status = 0} 条件写在
     * {@code OrgStudentMapper#selectActiveStudentIdsInSubtree} 的 SQL 里。
     */
    @Transactional(rollbackFor = Exception.class)
    public StudentArchivedVO archive(StudentArchiveReq req) {
        boolean byIds = req.getStudentIds() != null && !req.getStudentIds().isEmpty();
        boolean byNode = req.getNodeId() != null;
        if (byIds == byNode) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "studentIds 与 nodeId 二选一（03-02 §6.9）");
        }

        int archiveReason = req.getArchiveReason() == null
                ? OrgStudent.ARCHIVE_REASON_GRADUATED : req.getArchiveReason();
        if (archiveReason != OrgStudent.ARCHIVE_REASON_GRADUATED
                && archiveReason != OrgStudent.ARCHIVE_REASON_DELETION_REQUEST) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "archiveReason 只能是 1（正常毕业）或 2（因监护人删除请求）");
        }

        List<OrgStudent> students;
        if (byIds) {
            // 名单模式：整批校验，含非在读者 → 10208 整批拒绝
            students = statusGuard.assertAllActive(req.getStudentIds());
        } else {
            OrgNode root = writeSupport.requireParentInMyScope(req.getNodeId());
            List<Long> ids = studentMapper.selectActiveStudentIdsInSubtree(
                    root.getId(), root.selfPrefix());
            students = ids.isEmpty() ? List.of() : statusGuard.assertAllActive(ids);
        }

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Integer> affectedByTeacher = new LinkedHashMap<>();
        for (OrgStudent student : students) {
            OrgNode node = writeSupport.requireNodeInMyScope(
                    student.getNodeId(), NodePath.NODE_TYPE_STUDENT);

            OrgStudent update = new OrgStudent();
            update.setId(student.getId());
            update.setStatus(OrgStudent.STATUS_ARCHIVED);
            update.setArchiveTime(now);
            // 【这一列决定 30 日后脱不脱敏】写错 1/2 的后果不对称：
            // 该脱不脱 = 对监护人的承诺落空；不该脱却脱了 = 毕业校友联系方式不可逆丢失
            update.setArchiveReason(archiveReason);
            update.setUpdateBy(TenantHelper.getUserId());
            studentMapper.updateById(update);

            decrementCounts(node);
            countAffectedTeacher(affectedByTeacher, node);

            // 节点不移动 → from 与 to 相同
            changeLogWriter.write(node.getId(), OrgNodeChangeLog.CHANGE_TYPE_GRADUATE,
                    node.getParentId(), node.getParentId(), req.getRemark(), node.getTenantId());
        }

        StudentArchivedVO vo = new StudentArchivedVO();
        vo.setArchivedCount(students.size());
        vo.setArchiveTime(now);
        vo.setChangeType(OrgNodeChangeLog.CHANGE_TYPE_GRADUATE);
        vo.setArchiveReason(archiveReason);
        vo.setAffectedTeachers(toAffectedVOs(affectedByTeacher));
        return vo;
    }

    // =====================================================================
    // 接口 25 §6.10 归档恢复
    // =====================================================================

    /**
     * 把已归档（{@code status=2}）或已退课（{@code status=1}）的学员恢复为在读。
     *
     * <h2>两个码的判定顺序是有意的：先 {@code 10209}，再 {@code 10204}</h2>
     * <p>一个<b>已脱敏</b>的学员必然也是已归档（脱敏只发生在 {@code archive_reason=2} 之后），
     * 所以两个条件不会同时命中；但若把 {@code 10204} 判在前面，
     * 一个「在读且已脱敏」的坏数据会报出 {@code 10204}「未处于归档状态」——
     * 那句提示会把排查引向完全错误的方向。<b>不可逆的那条先说话。</b>
     *
     * <p>{@code toParentNodeId} 不传即原地恢复；原节点已被删除或停用时返回
     * {@code 10101} / {@code 10109}，此时<b>必须</b>显式指定新的挂载节点（PRD F1-8 规则 6）。
     */
    @Transactional(rollbackFor = Exception.class)
    public StudentUnarchivedVO unarchive(Long studentId, StudentUnarchiveReq req) {
        OrgStudent student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        OrgNode node = writeSupport.requireNodeInMyScope(
                student.getNodeId(), NodePath.NODE_TYPE_STUDENT);

        // 【顺序有意】已脱敏不可恢复 → 10209（不可逆，先说话）
        if (student.isAnonymized()) {
            throw new BizException(ErrorCode.STUDENT_ALREADY_ANONYMIZED);
        }
        // 仅 status ∈ {1, 2} 可恢复；对在读学员调用 → 10204
        if (student.isActive()) {
            throw new BizException(ErrorCode.STUDENT_NOT_ARCHIVED);
        }

        Long fromParentId = node.getParentId();
        String fromParentName = nodeNameOf(fromParentId);

        // 原父已删除或停用时必须另选（PRD F1-8 规则 6）。
        // 【只在不移动时才校验】要移走的人不该被「原父停用了」挡住 —— 那正是他要走的理由
        OrgNode currentParent = nodeMapper.selectById(fromParentId);
        boolean moving = req.getToParentNodeId() != null
                && !req.getToParentNodeId().equals(fromParentId);
        if (!moving) {
            if (currentParent == null) {
                throw new BizException(ErrorCode.NODE_NOT_FOUND);
            }
            if (currentParent.isDisabled()) {
                throw new BizException(ErrorCode.NODE_DISABLED);
            }
        }

        // 恢复：status → 0，并清空退课/归档四列
        // 【必须清空】留着 archive_time 且 archive_reason=2 的话，这名学员会在 30 日后
        // 被脱敏任务扫到 —— 一个已经复课在读的学员被脱敏，且不可逆
        studentMapper.clearLifecycleFields(studentId, TenantHelper.getUserId());

        // 【顺序有意，且这一步必须在 move 之前】
        // 恢复 = 「在原位重新计入分母」，退课/归档当初就是在原位减掉的。
        // 之后若还要换归属，那次迁移由 move 自己完成（它按【在读】数搬，而此刻本人已经是在读了）。
        //
        // 反过来写会双计：先 move 再 +1 的话，move 已经把他从旧链搬到新链（+1），
        // 这里再 +1 就是 2 —— 而这个错误【不会报错】，只是导师看板上多出一个不存在的学员。
        // 判据见 StudentLifecycleIT#unarchiveCanRemount（第一版正是这么写的，被它测出来）。
        if (currentParent != null) {
            nodeMapper.addStudentCount(OrgStudentService.ancestorChainOf(currentParent), 1);
            teacherMapper.addStudentCount(currentParent.getId(), 1);
        }

        OrgNode target = currentParent;
        String newAncestors = node.getAncestors();
        LocalDateTime changeTime = LocalDateTime.now();
        if (moving) {
            // 改父一律走模块 06 的移动事务（规则 5），不另写改父逻辑。
            // 它会写一条 change_type=2/3 的轨迹，本方法随后再写一条 change_type=6 ——
            // 两条都是发生过的事实：先「恢复」再「换了归属」。轨迹只增不改（PRD F1-9 规则 2）
            NodeMovedVO moved = nodeMoveService.move(node.getId(), req.getToParentNodeId(),
                    new NodeMoveOptions(req.getRemark(), false));
            newAncestors = moved.getNewAncestors();
            target = nodeMapper.selectById(req.getToParentNodeId());
        }

        OrgNode parentForCount = target == null ? currentParent : target;

        changeLogWriter.write(node.getId(), OrgNodeChangeLog.CHANGE_TYPE_UNARCHIVE,
                fromParentId, parentForCount == null ? fromParentId : parentForCount.getId(),
                req.getRemark(), node.getTenantId());

        StudentUnarchivedVO vo = new StudentUnarchivedVO();
        vo.setStudentId(studentId);
        vo.setNodeId(node.getId());
        vo.setRealName(node.getNodeName());
        vo.setStatus(OrgStudent.STATUS_ACTIVE);
        vo.setFromParentId(fromParentId);
        vo.setFromParentName(fromParentName);
        vo.setToParentId(parentForCount == null ? fromParentId : parentForCount.getId());
        vo.setToParentName(parentForCount == null ? fromParentName : parentForCount.getNodeName());
        vo.setNewAncestors(newAncestors);
        vo.setChangeType(OrgNodeChangeLog.CHANGE_TYPE_UNARCHIVE);
        vo.setChangeTime(changeTime);
        return vo;
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /** 退出统计分母：沿祖先链 {@code student_count - 1}，父为教师时其档案计数也 -1。 */
    private void decrementCounts(OrgNode node) {
        OrgNode parent = nodeMapper.selectById(node.getParentId());
        if (parent != null) {
            nodeMapper.addStudentCount(OrgStudentService.ancestorChainOf(parent), -1);
        }
        // 父不是教师时匹配 0 行、静默无事发生（org_teacher 有 uk_node_id）
        teacherMapper.addStudentCount(node.getParentId(), -1);
    }

    private void countAffectedTeacher(Map<Long, Integer> counts, OrgNode node) {
        OrgNode parent = nodeMapper.selectById(node.getParentId());
        if (parent != null && parent.getNodeType() != null
                && parent.getNodeType() == NodePath.NODE_TYPE_TEACHER) {
            counts.merge(parent.getId(), 1, Integer::sum);
        }
    }

    private List<AffectedTeacherVO> toAffectedVOs(Map<Long, Integer> counts) {
        List<AffectedTeacherVO> list = new ArrayList<>(counts.size());
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            AffectedTeacherVO vo = new AffectedTeacherVO();
            vo.setTeacherNodeId(entry.getKey());
            vo.setTeacherName(nodeNameOf(entry.getKey()));
            vo.setArchivedCount(entry.getValue());
            list.add(vo);
        }
        return list;
    }

    private String nodeNameOf(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        OrgNode node = nodeMapper.selectById(nodeId);
        return node == null ? null : node.getNodeName();
    }
}
