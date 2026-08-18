package com.edumatrix.org.member.service;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.mapper.OrgStudentMapper;

/**
 * 学籍状态校验（04-实施计划.md 模块 07「对外产出」第一行）。
 *
 * <p>模块 15 发布作业、模块 11 授权目标选择器都调它 —— <b>那两个模块不再各写一遍
 * 「什么算在读」</b>。口径只有一个：{@code org_student.status = 0}。
 *
 * <h2>单条与批量的错误码<b>不同</b>，不可合并</h2>
 * <table border="1">
 *   <caption>两个码的分工</caption>
 *   <tr><th></th><th>码</th><th>文案</th><th>用在哪</th></tr>
 *   <tr><td>单条</td><td>{@code 10203}</td><td>学生已归档/已退课，不可执行该操作</td>
 *       <td>接口 18 / 20 / 23</td></tr>
 *   <tr><td>批量</td><td>{@code 10208}</td><td>退课/归档名单包含状态不符的学员</td>
 *       <td>接口 21 / 22 / 24</td></tr>
 * </table>
 * <p>批量场景用 {@code 10203} 会让前端说出「这个学生已归档」——<b>而操作者选了 500 个，
 * 他要知道的是「名单里有不合格的」</b>。两句话指向的下一步动作完全不同。
 *
 * <h2>批量一律<b>整批拒绝</b>，不做部分成功</h2>
 * <p>模块 07 规则 6 与「禁止事项」都写死了这一条。落地方式是
 * {@link #assertAllActive} <b>先把整个名单查完再判</b>，而不是边遍历边执行 ——
 * 后者会在第 300 个失败时已经写了 299 行（虽然事务会回滚，但错误码取决于遍历顺序，
 * 同一个名单换个顺序会报不同的码）。
 */
@Service
public class StudentStatusGuard {

    private final OrgStudentMapper studentMapper;

    public StudentStatusGuard(OrgStudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    /**
     * 单条：不在读 → {@code 10203}。
     *
     * @param studentId 学生<b>档案 ID</b>（{@code org_student.id}）
     */
    public OrgStudent assertActive(Long studentId) {
        OrgStudent student = studentMapper.selectById(studentId);
        if (student == null) {
            // 路径 {id} 上的对象不存在 / 跨租户被插件过滤 —— 一律 404，不暴露存在性（契约 §2.4）
            throw new BizException(ErrorCode.NODE_NOT_FOUND);
        }
        if (!student.isActive()) {
            throw new BizException(ErrorCode.STUDENT_ARCHIVED_OR_QUIT);
        }
        return student;
    }

    /**
     * 批量：<b>先全部查出再判</b>，名单含状态不符者 → {@code 10208}，整批拒绝。
     *
     * <p>查不到的 id（不存在 / 已删除 / 跨租户）同样计入「状态不符」——
     * 它们确实不是在读学员，且<b>不能因此暴露「这个 id 在别的租户存在」</b>。
     *
     * @return 按<b>节点 id 升序</b>排好的名单。顺序不是无所谓的，见
     *         {@code StudentAssignService#assignBatch} 里那段关于跨调用加锁顺序的注释
     */
    public List<OrgStudent> assertAllActive(Collection<Long> studentIds) {
        List<Long> ids = studentIds.stream().distinct().toList();
        List<OrgStudent> found = studentMapper.selectList(
                new LambdaQueryWrapper<OrgStudent>().in(OrgStudent::getId, ids));
        if (found.size() != ids.size()) {
            throw new BizException(ErrorCode.BATCH_CONTAINS_INVALID_STATUS_STUDENT);
        }
        for (OrgStudent student : found) {
            if (!student.isActive()) {
                throw new BizException(ErrorCode.BATCH_CONTAINS_INVALID_STATUS_STUDENT);
            }
        }
        return found.stream()
                .sorted(java.util.Comparator.comparing(OrgStudent::getNodeId))
                .toList();
    }
}
