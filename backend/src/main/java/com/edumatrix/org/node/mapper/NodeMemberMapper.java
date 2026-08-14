package com.edumatrix.org.node.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code org_student} / {@code org_teacher} 的<b>三条窄读写</b>，服务移动事务。
 *
 * <h2>⚠ 临时构件，交接给模块 07 的 {@code org/member}</h2>
 * <p>登记在 {@code com.edumatrix.org.node} 的 {@code package-info}（模块 06 自己的那张清单，
 * 与 {@code system/user/entity/SystemOrgNode} 那张<b>分开</b>，理由见那里）。
 *
 * <p><b>这里不触发检查③</b>：{@code check_backend_conventions.sh} 的 {@code DOMAINS} 是
 * <b>顶层领域包</b>（{@code auth system org course vod question homework stat}），
 * 而 {@code node} / {@code member} 是 {@code org} 内部的子域（05-工程结构.md §A1 / §D）。
 * 之所以仍然开窄 Mapper 而不是等对方，是因为 {@code org/member} 要到模块 07 才存在，
 * 而移动事务<b>现在</b>就需要这三条。
 *
 * <p>租户条件由插件注入，这里一个字不写；{@code deleted_at = 0} 手写（注解 SQL 不受
 * {@code @TableLogic} 管）。
 */
@Mapper
public interface NodeMemberMapper {

    /**
     * 学生节点的学籍状态（{@code 0 在读 / 1 已退课 / 2 毕业归档}）；无档案行时返回 {@code null}。
     *
     * <p>§3.4 校验 10「被移动学生节点学籍状态为 0 在读」的判据，违反 → {@code 10203}。
     *
     * <p><b>返回 {@code null} 与「不是在读」不是一回事</b>，调用方要分开处理：
     * 03-01 §2.2 允许超管经 {@code /system/users} 建出<b>没有 {@code org_student} 档案</b>
     * 的学生节点（F-22 未定案，模块 03 已按「保留现状」落地）。那种节点<b>不是</b>
     * 「已退课/已归档」，用 {@code 10203} 拒绝它是错的 —— 判据见本方法调用处的注释。
     */
    @Select("SELECT status FROM org_student WHERE node_id = #{nodeId} AND deleted_at = 0")
    Integer selectStudentStatus(@Param("nodeId") Long nodeId);

    /**
     * 被移动子树内的<b>在读</b>学生数（{@code org_student.status = 0}），<b>含被移动节点自身</b>。
     *
     * <p>这是 §3.1.3 步骤 6 里 {@code movedStudentCnt} 的来源。
     *
     * <h2>口径必须与另外两处一致</h2>
     * <p>{@code StudentQuotaMapper#countActiveStudents} 与
     * {@code TenantOrgMapper#countActiveStudents} 都按 {@code org_student.status = 0} 计
     * （F-22 已定案：<b>不</b>按 {@code org_node} 里 {@code node_type = 3} 的节点数）。
     * {@code org_node.student_count} 的 DDL 注释逐字是「子树内<b>在读</b>学生节点总数」，
     * 与本口径自洽 —— 三处同口径，否则同一个租户会算出两个学生数。
     *
     * <h2>为什么是 JOIN 而不是先取子树 id 再 IN</h2>
     * <p>被移动子树可能有上万行，把 id 全捞回 Java 再拼 {@code IN} 是白付一次大结果集的代价；
     * 而这条只要一个数。{@code org_node} 侧走 {@code idx_ancestors} 前缀范围扫描，
     * {@code org_student} 侧走 {@code uk_node_id}。
     *
     * <p>两个前缀分支与逗号收边的理由同 {@code OrgNodeMapper#rebuildSubtreeAncestors}；
     * {@code n.id = #{movingNodeId}} 那一支是<b>被移动节点自身</b> ——
     * 它自己若是在读学生（分配导师/转交管理员的常规形态），也要计入。
     */
    @Select("SELECT COUNT(1) FROM org_student s "
            + " JOIN org_node n ON n.id = s.node_id AND n.deleted_at = 0 "
            + " WHERE s.status = 0 AND s.deleted_at = 0 "
            + "   AND (n.id = #{movingNodeId} "
            + "        OR n.ancestors = #{prefix} OR n.ancestors LIKE CONCAT(#{prefix}, ',%'))")
    long countActiveStudentsInSubtree(@Param("movingNodeId") Long movingNodeId,
                                      @Param("prefix") String prefix);

    /**
     * 教师档案的 {@code student_count} 增减。
     *
     * <h2>⚠ 这一步<b>不在</b> 02-数据库设计 §3.1.3 的 7 步模板里，是有意增补</h2>
     * <p>模板的步骤 6 只写 {@code org_node} 的两个冗余列。本项增补的依据有两条，
     * <b>都不是推测</b>：
     * <ol>
     *   <li>DDL 对 {@code org_teacher.student_count} 的列注释逐字：「名下在读学员数
     *       （冗余计数，<b>与 {@code org_node.student_count} 同源同步</b>；
     *       <b>分配/转交/调岗</b>/归档时维护）」；
     *   <li>04-实施计划.md 模块 07 的「对外产出 · 冗余维护」那一行逐字：
     *       「{@code org_teacher.student_count} 与 {@code org_node.child_count} /
     *       {@code student_count} 的维护<b>统一在 06 的移动事务</b>与本模块建删事务内」。
     * </ol>
     * <p>而「分配导师 / 转交管理员 / 教师调岗」<b>全部</b>是本模块移动事务的语义化封装
     * （模块 07 规则 5：「一律调用 06 的 {@code NodeMoveService}，不得另写改父逻辑」）——
     * 模块 07 <b>没有别的钩子</b>能补这一笔。不在这里维护，接口 4 被直接调用时
     * {@code org_teacher.student_count} 就会<b>静默变陈旧</b>。
     *
     * <p><b>下一个人对着 §3.1.3 逐行核对时会发现这里多了一步 —— 那是有意的，不是照抄遗漏。</b>
     * （04-实施计划.md 模块 06 的「涉及表」原写着 {@code org_teacher} 只读，
     * 与模块 07 那一行冲突；已随本模块订正为「写」。）
     *
     * <h2>调用方不必先判「父节点是不是教师」</h2>
     * <p>{@code org_teacher} 有 {@code uk_node_id}，<b>只有教师节点才有档案行</b>，
     * 于是 {@code WHERE node_id = ?} <b>本身就是那个类型判断</b>：父节点是管理员时匹配 0 行、
     * 静默无事发生。这样省掉一次「查旧父的 {@code node_type}」的点查，
     * 也去掉了「判断与实际数据不一致」的可能 —— 判断依据就是数据本身。
     *
     * @param teacherNodeId 教师<b>节点</b> id（{@code org_teacher.node_id}），
     *                      传旧父/新父即可，不是教师时无副作用
     */
    @Update("UPDATE org_teacher SET student_count = GREATEST(student_count + #{delta}, 0) "
            + " WHERE node_id = #{teacherNodeId} AND deleted_at = 0")
    int addTeacherStudentCount(@Param("teacherNodeId") Long teacherNodeId,
                               @Param("delta") int delta);
}
