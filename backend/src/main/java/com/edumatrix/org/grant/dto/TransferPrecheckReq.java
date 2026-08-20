package com.edumatrix.org.grant.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 52 归属变更影响面预检（03-02 §6.12）的请求体。
 *
 * <p><b>为什么是 {@code POST} 而不是 {@code GET}</b>：500 个 ID 放不进 query string
 *（§6.12 逐字）。与接口 36 按标签批量选人同为读语义，方法选择只取决于入参体积。
 */
public class TransferPrecheckReq {

    /** 学生<b>档案 ID</b>（{@code org_student.id}），单次最多 500 个；预检接口 20 时传 1 个。 */
    @NotEmpty(message = "学生不能为空")
    @Size(max = 500, message = "单次最多 500 个学生")
    private List<Long> studentIds;

    /**
     * 目标节点。{@code node_type = 2} 教师节点 → 预检接口 20 / 21；
     * {@code node_type = 1} 管理员节点 → 预检接口 22。
     */
    @NotNull(message = "目标节点不能为空")
    private Long toNodeId;

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
}
