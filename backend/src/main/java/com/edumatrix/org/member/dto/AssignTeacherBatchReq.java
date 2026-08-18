package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 接口 21 批量分配导师（03-02 §6.6）。
 *
 * <p><b>只接受显式 ID 名单</b>（分册原文：「本接口只接受显式 ID 名单，
 * 避免『筛选条件与执行时刻不一致』导致误操作」）—— 因此没有「按条件批量」的参数。
 *
 * <p><b>整批成功或整批回滚</b>：名单中任一学员校验失败即整体拒绝，<b>不做部分成功</b>
 * （模块 07 规则 6、「禁止事项」）。
 */
public class AssignTeacherBatchReq {

    /** 学生<b>档案 ID</b> 数组（{@code org_student.id}），不可为空，单次最多 500 个。 */
    @NotEmpty(message = "不能为空")
    @Size(max = 500, message = "单次最多 500 个")
    private List<Long> studentIds;

    /** 目标导师<b>节点 ID</b>（{@code node_type=2}）。 */
    @NotNull(message = "不能为空")
    private Long toTeacherNodeId;

    /** 分配原因，写入<b>每条</b>轨迹的 {@code reason}。 */
    @Size(max = 500, message = "最长 500 字符")
    private String reason;

    public List<Long> getStudentIds() {
        return studentIds;
    }

    public void setStudentIds(List<Long> studentIds) {
        this.studentIds = studentIds;
    }

    public Long getToTeacherNodeId() {
        return toTeacherNodeId;
    }

    public void setToTeacherNodeId(Long toTeacherNodeId) {
        this.toTeacherNodeId = toTeacherNodeId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
