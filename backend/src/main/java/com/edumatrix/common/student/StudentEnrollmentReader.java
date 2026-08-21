package com.edumatrix.common.student;

/**
 * 学籍读取 SPI —— 接口在 {@code common/}、实现在 {@code org/member/}。
 *
 * <p><b>为什么要这个 SPI</b>：模块 12 校验链第 1 步「学生在读」要读 {@code org_student}，
 * 而 {@code vod} 领域<b>不得 import {@code org} 领域</b>（检查③ 拦的是 import 语句，
 * 不区分 Service / Mapper / 实体）。{@code common/} 不进检查③ 的扫描范围，故做交汇点。
 * 与 {@code common/course/LessonVisibilityChecker}、{@code common/account/UserNameReader} 同构。
 *
 * <p><b>按节点查而不是按 userId 查</b>：{@code org_student.node_id} 是一名学生一个节点
 * （私域设计），既有的 {@code selectByNodeId} / {@code selectStudentStatus} 都按它查；
 * 而校验链第 3 步的授权判定同样以「本学生节点」为准（{@code target_node_id}），
 * 两步用同一个 key 才不会出现「查学籍用 A、查授权用 B」这种对不上的形态。
 */
public interface StudentEnrollmentReader {

    /** 在读状态：{@code org_student.status = 0}。1 已退课 / 2 已归档等一律视为不在读。 */
    int STATUS_ACTIVE = 0;

    /**
     * 按学生节点读学籍。
     *
     * @return 该节点没有学生档案行时返回 {@code null}（教师 / 管理员的节点即属此类）
     */
    Enrollment byNodeId(Long nodeId);

    /**
     * @param studentId {@code org_student.id}，写审计时用（{@code vod_play_auth_log.student_id}）
     * @param status    {@code org_student.status}
     */
    record Enrollment(Long studentId, Integer status) {

        /** 唯一的「在读」判定入口，不要在调用方各写一次 {@code status == 0}。 */
        public boolean active() {
            return status != null && status == STATUS_ACTIVE;
        }
    }
}
