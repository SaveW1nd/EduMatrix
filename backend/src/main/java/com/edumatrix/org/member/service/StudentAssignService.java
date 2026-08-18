package com.edumatrix.org.member.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.org.member.dto.AssignTeacherBatchReq;
import com.edumatrix.org.member.dto.AssignTeacherReq;
import com.edumatrix.org.member.dto.TransferAdminReq;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.vo.BatchAssignedVO;
import com.edumatrix.org.member.vo.DetachedTeacherVO;
import com.edumatrix.org.member.vo.StudentAssignedVO;
import com.edumatrix.org.member.vo.TransferredVO;
import com.edumatrix.org.node.entity.OrgNode;
import com.edumatrix.org.node.mapper.OrgNodeMapper;
import com.edumatrix.org.node.service.NodeMoveOptions;
import com.edumatrix.org.node.service.NodeMoveService;
import com.edumatrix.org.node.vo.NodeMovedVO;

/**
 * 分配导师 / 批量分配 / 转交管理员（03-02 §6.5~§6.7，接口 20 / 21 / 22）。
 *
 * <h2>三个动作都是 {@code NodeMoveService} 的语义化封装，<b>本类不写一行改父逻辑</b></h2>
 * <p>模块 07 规则 5 与 03-02 §3.4 都写死了这一条。{@code changeType} 也不由本类决定 ——
 * {@code NodeMoveService#inferChangeType} 按 {@code (movingType, targetType)} 推断：
 * 学生→教师 = 2 分配导师，学生→管理员 = 3 转交管理员。
 * <b>本类只负责「选谁、往哪搬、校验够不够」，搬这个动作一律交出去。</b>
 *
 * <h2>⚠ 事务边界：本类的事务<b>包着</b> {@code NodeMoveService} 的事务</h2>
 * <p>{@code NodeMoveService#move} 自带 {@code @Transactional}，被本类的方法调用时走
 * {@code REQUIRED} 加入外层事务 —— 也就是说<b>它的「方法返回」离提交还很远</b>。
 * 这正是 {@code NodeMoveService#registerAfterCommit} 那段注释预告的情形，
 * 逐字：「本方法可能被<b>模块 07 的更外层事务</b>包着……此刻清缓存等于在提交前清，
 * 而那一瞬间别的请求会用<b>旧数据</b>把缓存重新填回来，<b>且再也不会自己好</b>」。
 * 它用 {@code afterCommit} 注册，所以在<b>最外层</b>（即本类的事务）提交后才清缓存 ——
 * <b>本类因此不得在方法内部提前提交、也不得把批量拆成多个事务</b>，
 * 两者都会让缓存在树还没定型时被清。
 *
 * <h2>批量：整批成功或整批回滚，<b>不做部分成功</b></h2>
 * <p>模块 07 规则 6 与「禁止事项」。两条具体做法：
 * <ol>
 *   <li><b>校验全部先做完再执行</b>（{@code StudentStatusGuard#assertAllActive} 一次查完整个名单）。
 *       边遍历边执行的话，第 300 个失败时前 299 次的写入虽会随事务回滚，
 *       但<b>报出来的错误码取决于遍历顺序</b> —— 同一个名单换个顺序会报不同的码；
 *   <li><b>按节点 id 升序遍历名单</b>，见 {@link #moveAllInLockOrder} 的注释。
 * </ol>
 */
@Service
public class StudentAssignService {

    private final NodeMoveService nodeMoveService;
    private final StudentStatusGuard statusGuard;
    private final MemberWriteSupport writeSupport;
    private final OrgNodeMapper nodeMapper;

    public StudentAssignService(NodeMoveService nodeMoveService,
                                StudentStatusGuard statusGuard,
                                MemberWriteSupport writeSupport,
                                OrgNodeMapper nodeMapper) {
        this.nodeMoveService = nodeMoveService;
        this.statusGuard = statusGuard;
        this.writeSupport = writeSupport;
        this.nodeMapper = nodeMapper;
    }

    // =====================================================================
    // 接口 20 §6.5 分配导师
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public StudentAssignedVO assign(Long studentId, AssignTeacherReq req) {
        // 单条：不在读 → 10203（批量才是 10208，两个码文案不同，见 StudentStatusGuard）
        OrgStudent student = statusGuard.assertActive(studentId);
        OrgNode target = requireTargetOfType(req.getToTeacherNodeId(), NodePath.NODE_TYPE_TEACHER);

        OrgNode before = nodeMapper.selectById(student.getNodeId());
        Long fromParentId = before.getParentId();
        Integer fromParentType = typeOf(fromParentId);

        NodeMovedVO moved = nodeMoveService.move(student.getNodeId(), target.getId(),
                new NodeMoveOptions(req.getReason(), false));

        StudentAssignedVO vo = new StudentAssignedVO();
        vo.setStudentId(studentId);
        vo.setNodeId(student.getNodeId());
        vo.setRealName(moved.getNodeName());
        vo.setFromParentId(fromParentId);
        vo.setFromParentName(moved.getFromParentName());
        vo.setFromParentType(fromParentType);
        vo.setToParentId(target.getId());
        vo.setToParentName(target.getNodeName());
        vo.setToParentType(target.getNodeType());
        vo.setNewAncestors(moved.getNewAncestors());
        vo.setChangeType(moved.getChangeType());
        vo.setChangeTime(moved.getChangeTime());
        return vo;
    }

    // =====================================================================
    // 接口 21 §6.6 批量分配导师
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public BatchAssignedVO assignBatch(AssignTeacherBatchReq req) {
        OrgNode target = requireTargetOfType(req.getToTeacherNodeId(), NodePath.NODE_TYPE_TEACHER);
        List<OrgStudent> students = statusGuard.assertAllActive(req.getStudentIds());

        List<NodeMovedVO> moved = moveAllInLockOrder(students, target, req.getReason());

        BatchAssignedVO vo = new BatchAssignedVO();
        vo.setAssignedCount(moved.size());
        vo.setToTeacherNodeId(target.getId());
        vo.setToTeacherName(target.getNodeName());
        vo.setChangeType(moved.isEmpty() ? null : moved.get(0).getChangeType());
        vo.setChangeTime(moved.isEmpty() ? null : moved.get(moved.size() - 1).getChangeTime());
        return vo;
    }

    // =====================================================================
    // 接口 22 §6.7 转交给其他管理员
    // =====================================================================

    /**
     * <p><b>转交后 {@code teacherNodeId} 变为 {@code null}</b>（§6.7）：学员回到「已归属该管理员、
     * 尚未分配导师」状态。原先有导师的，其 {@code org_teacher.student_count - 1} ——
     * 这一笔由 {@code NodeMoveService} 的步骤 6 完成，本类不重复维护。
     */
    @Transactional(rollbackFor = Exception.class)
    public TransferredVO transferAdmin(TransferAdminReq req) {
        OrgNode target = requireTargetOfType(req.getToNodeId(), NodePath.NODE_TYPE_ADMIN);
        List<OrgStudent> students = statusGuard.assertAllActive(req.getStudentIds());

        // detachedTeachers 必须在【移动之前】统计：移完之后父节点已经是新管理员，
        // 「原导师是谁」就查不出来了
        Map<Long, Integer> detachedByTeacher = countDetachedTeachers(students);

        List<NodeMovedVO> moved = moveAllInLockOrder(students, target, req.getReason());

        TransferredVO vo = new TransferredVO();
        vo.setTransferredCount(moved.size());
        vo.setToNodeId(target.getId());
        vo.setToNodeName(target.getNodeName());
        vo.setToNodeType(target.getNodeType());
        vo.setChangeType(moved.isEmpty() ? null : moved.get(0).getChangeType());
        vo.setChangeTime(moved.isEmpty() ? null : moved.get(moved.size() - 1).getChangeTime());
        vo.setDetachedTeachers(toDetachedVOs(detachedByTeacher));
        return vo;
    }

    // =====================================================================
    // 批量的公共部分
    // =====================================================================

    /**
     * 逐生调用 {@code NodeMoveService#move}，<b>按被移动节点 id 升序</b>。
     *
     * <h2>为什么必须排序：{@code NodeMoveService} 的 id 升序加锁只在<b>单次调用内部</b>成立</h2>
     * <p>{@code NodeMoveService#lockIds} 把「被移动节点 ∪ 旧父及祖先链 ∪ 新父及祖先链」
     * 排序后一次 {@code FOR UPDATE}，那保证的是<b>一次 move 内部</b>不自相矛盾。
     * 但批量是<b>同一个事务里连着调 n 次</b>，跨调用的加锁顺序由<b>名单顺序</b>决定 ——
     * 两个并发批量事务若名单顺序相反（A 先锁学员甲、B 先锁学员乙，然后互相等），
     * 就是一个典型的 AB-BA 死锁，而<b>模块 06 那套排序一点忙都帮不上</b>。
     *
     * <p>排序把「本事务的全部加锁点按 id 升序」这条纪律从单次 move <b>延伸到整个批量</b>，
     * 与 04-实施计划.md <b>§D 前置风险项 R2</b> 表格「撞车后的影响面」那一行的要求同源。
     * <b>这不改模块 06 一个字</b>，只是不让它的前提在批量场景下失效。
     *
     * <p><b>不拆事务</b>：拆了就破坏「整批成功或整批回滚」（规则 6 明令禁止部分成功），
     * 也会让 {@code afterCommit} 的缓存清除在树还没定型时触发。
     */
    private List<NodeMovedVO> moveAllInLockOrder(List<OrgStudent> students,
                                                 OrgNode target, String reason) {
        // assertAllActive 已按 nodeId 升序返回；这里再排一次是为了让「顺序是有意的」
        // 不依赖上游实现 —— 上游哪天改了返回顺序，这里不会静默失去保护
        List<OrgStudent> ordered = students.stream()
                .sorted(java.util.Comparator.comparing(OrgStudent::getNodeId))
                .toList();

        NodeMoveOptions options = new NodeMoveOptions(reason, false);
        List<NodeMovedVO> moved = new ArrayList<>(ordered.size());
        for (OrgStudent student : ordered) {
            // 任一失败即抛，外层事务整体回滚 —— 这就是「整批拒绝」的落地方式
            moved.add(nodeMoveService.move(student.getNodeId(), target.getId(), options));
        }
        return moved;
    }

    /**
     * {@code detachedTeachers}：本次转交导致学员脱离的<b>原导师</b>及数量。
     *
     * <p>§6.7 字段说明：「<b>原先无导师的学员不出现在此列表</b>」——
     * 所以只统计父节点是教师的那些。
     */
    private Map<Long, Integer> countDetachedTeachers(List<OrgStudent> students) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (OrgStudent student : students) {
            OrgNode node = nodeMapper.selectById(student.getNodeId());
            if (node == null) {
                continue;
            }
            Integer parentType = typeOf(node.getParentId());
            if (parentType != null && parentType == NodePath.NODE_TYPE_TEACHER) {
                counts.merge(node.getParentId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private List<DetachedTeacherVO> toDetachedVOs(Map<Long, Integer> counts) {
        List<DetachedTeacherVO> list = new ArrayList<>(counts.size());
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            OrgNode teacherNode = nodeMapper.selectById(entry.getKey());
            DetachedTeacherVO vo = new DetachedTeacherVO();
            vo.setTeacherNodeId(entry.getKey());
            vo.setTeacherName(teacherNode == null ? null : teacherNode.getNodeName());
            vo.setDetachedCount(entry.getValue());
            list.add(vo);
        }
        return list;
    }

    /**
     * 目标节点必须存在、在子树内、且是指定类型。
     *
     * <p><b>类型不符一律 {@code 10104}</b>（§6.5「目标节点必须是 {@code node_type=2} 教师节点，
     * 否则返回 {@code 10104}」；§6.7「目标为教师或学生节点 → {@code 10104}」）。
     * <b>这一条必须在本类判，不能指望 {@code NodeTypeRule}</b>：
     * 学生挂到管理员节点下是<b>合法</b>结构（{@code NodeTypeRule} 会放行），
     * 只是对「分配导师」这个语义来说选错了目标。<b>结构合法 ≠ 语义正确。</b>
     */
    private OrgNode requireTargetOfType(Long nodeId, int expectedType) {
        OrgNode target = writeSupport.requireParentInMyScope(nodeId);
        if (target.getNodeType() == null || target.getNodeType() != expectedType) {
            throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
        }
        // 目标已停用 → 10109（§6.5 / §6.6 / §6.7 错误码表都有这一条）。
        // NodeMoveService 的校验 8 也会判，这里先判是为了在批量场景下【一次都不写】就拒绝
        if (target.isDisabled()) {
            throw new BizException(ErrorCode.NODE_DISABLED);
        }
        return target;
    }

    private Integer typeOf(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        OrgNode node = nodeMapper.selectById(nodeId);
        return node == null ? null : node.getNodeType();
    }
}
