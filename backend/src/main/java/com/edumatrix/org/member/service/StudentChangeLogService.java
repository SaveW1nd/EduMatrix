package com.edumatrix.org.member.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.mapper.OrgStudentMapper;
import com.edumatrix.org.member.vo.StudentChangeLogVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;
import com.edumatrix.org.node.mapper.NodeAccountMapper;
import com.edumatrix.org.node.mapper.OrgNodeChangeLogMapper;
import com.edumatrix.org.node.mapper.OrgNodeMapper;

/**
 * 学生异动轨迹（03-02 §6.11，接口 26）。
 *
 * <h2>只读，而且<b>永远只会是只读</b></h2>
 * <p>PRD F1-9 规则 2：轨迹<b>只增不改不删</b>，系统<b>不提供任何编辑/删除接口</b>。
 * 所以本类没有任何写方法，{@code org/member} 也没有对应的写 DTO ——
 * 写入一律在各自的业务事务里由 {@code NodeChangeLogWriter} 完成。
 *
 * <h2>「学员被移走后原上级立即查不到」由子树判定天然承担</h2>
 * <p>PRD F1-9 规则 7 / 契约 §2.4。本类不写第二套判断：
 * {@code SubtreeScopeHelper} 用的是<b>当前</b>的 {@code ancestors}，
 * 而移动事务在同一事务里重算了它、提交后又清了缓存 ——
 * 所以原上级下一次调本接口就已经落在子树之外，返回 404。
 * <b>轨迹本身一条都不删</b>：新上级看得到这名学员转入前的完整归属历史。
 */
@Service
public class StudentChangeLogService {

    /** 契约 §5 {@code change_type} 的中文名，与 §6.11 响应字段说明逐字一致。 */
    private static final Map<Integer, String> CHANGE_TYPE_NAMES = Map.of(
            OrgNodeChangeLog.CHANGE_TYPE_CREATE, "建档",
            OrgNodeChangeLog.CHANGE_TYPE_ASSIGN_TEACHER, "分配导师",
            OrgNodeChangeLog.CHANGE_TYPE_TRANSFER_ADMIN, "转交管理员",
            OrgNodeChangeLog.CHANGE_TYPE_TEACHER_REASSIGN, "教师调岗",
            OrgNodeChangeLog.CHANGE_TYPE_GRADUATE, "毕业归档",
            OrgNodeChangeLog.CHANGE_TYPE_UNARCHIVE, "归档恢复",
            OrgNodeChangeLog.CHANGE_TYPE_QUIT, "退课",
            OrgNodeChangeLog.CHANGE_TYPE_NODE_MOVE, "节点移动");

    private final OrgStudentMapper studentMapper;
    private final OrgNodeChangeLogMapper changeLogMapper;
    private final OrgNodeMapper nodeMapper;
    private final NodeAccountMapper accountMapper;
    private final MemberWriteSupport writeSupport;

    public StudentChangeLogService(OrgStudentMapper studentMapper,
                                   OrgNodeChangeLogMapper changeLogMapper,
                                   OrgNodeMapper nodeMapper,
                                   NodeAccountMapper accountMapper,
                                   MemberWriteSupport writeSupport) {
        this.studentMapper = studentMapper;
        this.changeLogMapper = changeLogMapper;
        this.nodeMapper = nodeMapper;
        this.accountMapper = accountMapper;
        this.writeSupport = writeSupport;
    }

    /**
     * 按 {@code change_time} <b>倒序</b>返回全部异动记录，<b>不分页</b>
     * （§6.11：「数据量小，不分页」）。
     *
     * <p><b>学生角色只能查自己</b>：{@code {id}} 非本人时返回 {@code 403}（§6.11 权限栏）——
     * 那是「我没资格做这件事」，契约 §2.4 三分法里正是 403，不是 404。
     * 管理员 / 教师走子树判定，越界返回 404。
     */
    public List<StudentChangeLogVO> list(Long studentId) {
        OrgStudent student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }

        if (isStudentSession()) {
            // 学生查他人 → 403（不是 404）。学生端接口不带 perms（契约 §3.1 边界 0），
            // 这一句就是它全部的权限判定
            Long myNodeId = accountMapper.selectNodeIdByUserId(TenantHelper.getUserId());
            if (!student.getNodeId().equals(myNodeId)) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
        } else {
            writeSupport.requireNodeInMyScope(student.getNodeId(), NodePath.NODE_TYPE_STUDENT);
        }

        List<OrgNodeChangeLog> logs = changeLogMapper.selectList(
                new LambdaQueryWrapper<OrgNodeChangeLog>()
                        .eq(OrgNodeChangeLog::getNodeId, student.getNodeId())
                        .orderByDesc(OrgNodeChangeLog::getChangeTime)
                        .orderByDesc(OrgNodeChangeLog::getId));

        Map<Long, String> nodeNames = loadNodeNames(logs);
        Map<Long, String> operatorNames = loadOperatorNames(logs);

        List<StudentChangeLogVO> list = new ArrayList<>(logs.size());
        for (OrgNodeChangeLog logRow : logs) {
            StudentChangeLogVO vo = new StudentChangeLogVO();
            vo.setId(logRow.getId());
            vo.setNodeId(logRow.getNodeId());
            vo.setChangeType(logRow.getChangeType());
            vo.setChangeTypeName(CHANGE_TYPE_NAMES.get(logRow.getChangeType()));
            vo.setFromParentId(logRow.getFromParentId());
            vo.setFromParentName(nodeNames.get(logRow.getFromParentId()));
            vo.setToParentId(logRow.getToParentId());
            vo.setToParentName(nodeNames.get(logRow.getToParentId()));
            vo.setChangeTime(logRow.getChangeTime());
            vo.setOperatorId(logRow.getOperatorId());
            vo.setOperatorName(operatorNames.get(logRow.getOperatorId()));
            vo.setReason(logRow.getReason());
            list.add(vo);
        }
        return list;
    }

    /** 当前会话是不是学生。学生的 {@code node_type} 是 3，节点即本人。 */
    private boolean isStudentSession() {
        Long myNodeId = accountMapper.selectNodeIdByUserId(TenantHelper.getUserId());
        if (myNodeId == null) {
            return false;
        }
        OrgNode node = nodeMapper.selectById(myNodeId);
        return node != null && node.getNodeType() != null
                && node.getNodeType() == NodePath.NODE_TYPE_STUDENT;
    }

    private Map<Long, String> loadNodeNames(List<OrgNodeChangeLog> logs) {
        List<Long> ids = new ArrayList<>();
        for (OrgNodeChangeLog logRow : logs) {
            if (logRow.getFromParentId() != null) {
                ids.add(logRow.getFromParentId());
            }
            if (logRow.getToParentId() != null) {
                ids.add(logRow.getToParentId());
            }
        }
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (OrgNode node : nodeMapper.selectByIds(distinct)) {
            names.put(node.getId(), node.getNodeName());
        }
        return names;
    }

    private Map<Long, String> loadOperatorNames(List<OrgNodeChangeLog> logs) {
        List<Long> ids = logs.stream()
                .map(OrgNodeChangeLog::getOperatorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (NodeAccountMapper.UserBriefRow row : accountMapper.selectUserBriefs(ids)) {
            names.put(row.getId(), row.getRealName());
        }
        return names;
    }
}
