package com.edumatrix.org.member.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.org.member.entity.OrgTeacher;

/**
 * {@code org_teacher} 的读写。
 *
 * <p>{@link #addStudentCount} 是从模块 06 的 {@code NodeMemberMapper#addTeacherStudentCount}
 * 迁来的（那张清单逐字写着「交给模块 07 的 {@code org/member}」），
 * <b>SQL 与整段注释逐字保留</b> —— 那段注释的作用是拦住下一个对着
 * 02-数据库设计 §3.1.3 逐行核对的人，删了它这一步就会被当成多余的。
 */
@Mapper
public interface OrgTeacherMapper extends BaseMapper<OrgTeacher> {

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
     * <p>而「分配导师 / 转交管理员 / 教师调岗」<b>全部</b>是模块 06 移动事务的语义化封装
     * （模块 07 规则 5：「一律调用 06 的 {@code NodeMoveService}，不得另写改父逻辑」）——
     * 模块 07 <b>没有别的钩子</b>能补这一笔。不在那里维护，接口 4 被直接调用时
     * {@code org_teacher.student_count} 就会<b>静默变陈旧</b>。
     *
     * <p><b>下一个人对着 §3.1.3 逐行核对时会发现那里多了一步 —— 那是有意的，不是照抄遗漏。</b>
     * （04-实施计划.md 模块 06 的「涉及表」原写着 {@code org_teacher} 只读，
     * 与模块 07 那一行冲突；已随模块 06 订正为「写」。）
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
    int addStudentCount(@Param("teacherNodeId") Long teacherNodeId,
                        @Param("delta") int delta);

    /** 工号机构内唯一（{@code 10201}）。租户条件由插件注入，「机构内」天然成立。 */
    @Select("SELECT COUNT(1) FROM org_teacher "
            + " WHERE teacher_no = #{teacherNo} AND deleted_at = 0 "
            + "   AND (#{excludeId} IS NULL OR id <> #{excludeId})")
    long countByTeacherNo(@Param("teacherNo") String teacherNo,
                          @Param("excludeId") Long excludeId);

    /** 按节点 id 取档案；管理员节点无档案行，返回 {@code null}。 */
    @Select("SELECT * FROM org_teacher WHERE node_id = #{nodeId} AND deleted_at = 0")
    OrgTeacher selectByNodeId(@Param("nodeId") Long nodeId);
}
