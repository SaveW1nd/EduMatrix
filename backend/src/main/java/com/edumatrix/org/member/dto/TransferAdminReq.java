package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 接口 22 转交给其他管理员（03-02 §6.7）。
 *
 * <p><b>跨子树转交被禁止</b>——契约 §2.4 的直接推论：你不能把数据交给你看不见的人。
 * 确有跨部门转交需求时，须由两者的<b>共同上级</b>执行。
 */
public class TransferAdminReq {

    /** 学生档案 ID 数组，不可为空，单次最多 500 个。 */
    @NotEmpty(message = "不能为空")
    @Size(max = 500, message = "单次最多 500 个")
    private List<Long> studentIds;

    /** 目标<b>管理员</b>节点 ID（{@code node_type=1}）；目标为教师/学生 → {@code 10104}。 */
    @NotNull(message = "不能为空")
    private Long toNodeId;

    @Size(max = 500, message = "最长 500 字符")
    private String reason;

    public List<Long> getStudentIds() {
        return studentIds;
    }

    public void setStudentIds(List<Long> studentIds) {
        this.studentIds = studentIds;
    }

    public Long getToNodeId() {
        return toNodeId;
    }

    public void setToNodeId(Long toNodeId) {
        this.toNodeId = toNodeId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
