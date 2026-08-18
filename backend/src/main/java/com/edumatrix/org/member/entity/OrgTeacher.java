package com.edumatrix.org.member.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;

/**
 * {@code org_teacher} 教师档案（1:1 {@code org_node} 教师节点 / 1:1 {@code sys_user}）。
 *
 * <p><b>名下学员 = 该教师节点的直接子节点</b>，不在本表。本表没有学员名单列。
 *
 * <h2>{@code student_count} 是冗余计数，四个时刻维护，分散在两个模块</h2>
 * <p>DDL 列注释逐字：「名下在读学员数（冗余计数，<b>与 {@code org_node.student_count}
 * 同源同步</b>；<b>分配/转交/调岗</b>/归档时维护）」。四个时刻的落点：
 * <ul>
 *   <li><b>分配 / 转交 / 调岗</b> → 模块 06 的 {@code NodeMoveService} 步骤 6
 *       （三者全是移动事务的语义化封装，模块 07 规则 5「一律调用 06 的
 *       {@code NodeMoveService}，不得另写改父逻辑」，<b>模块 07 没有别的钩子能补这一笔</b>）；
 *   <li><b>建 / 删 / 退课 / 归档 / 恢复</b> → 本模块的建删与学籍事务。
 * </ul>
 * <p>04-实施计划.md 模块 07「对外产出 · 冗余维护」那一行把这个分工写死了：
 * 「维护<b>统一在 06 的移动事务</b>与本模块建删事务内」。
 */
@TableName("org_teacher")
public class OrgTeacher extends TenantEntity {

    private static final long serialVersionUID = 1L;

    /** 教师节点 ID（{@code org_node.id}，{@code node_type = 2}）。 */
    private Long nodeId;

    /** 账号 ID（{@code sys_user.id}）。 */
    private Long userId;

    /** 教师工号，机构内唯一（{@code 10201}）。 */
    private String teacherNo;

    /** 任教科目。 */
    private String subject;

    /** 职称。 */
    private String title;

    /** 入职日期。<b>纯日期，序列化为 {@code yyyy-MM-dd}</b>（03-02 分册导语）。 */
    private LocalDate entryDate;

    /** 名下<b>在读</b>学员数（冗余计数，口径见类注释）。 */
    private Integer studentCount;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTeacherNo() {
        return teacherNo;
    }

    public void setTeacherNo(String teacherNo) {
        this.teacherNo = teacherNo;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public Integer getStudentCount() {
        return studentCount;
    }

    public void setStudentCount(Integer studentCount) {
        this.studentCount = studentCount;
    }
}
