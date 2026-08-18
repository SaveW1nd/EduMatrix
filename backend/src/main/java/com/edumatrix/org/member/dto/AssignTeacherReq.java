package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 接口 20 分配导师（03-02 §6.5）。<b>分配导师 = 把学生节点移动到该导师节点下。</b> */
public class AssignTeacherReq {

    /** 目标导师<b>节点 ID</b>（{@code org_node.id}，{@code node_type=2}）；非教师节点 → {@code 10104}。 */
    @NotNull(message = "不能为空")
    private Long toTeacherNodeId;

    /** 分配原因，写入 {@code org_node_change_log.reason}。 */
    @Size(max = 500, message = "最长 500 字符")
    private String reason;

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
