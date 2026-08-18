package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.Size;

/**
 * 接口 25 归档恢复（03-02 §6.10）。
 *
 * <p><b>已脱敏（{@code anonymized_at} 非空）不可恢复 → {@code 10209}</b>：
 * 脱敏不可逆，原值不存于任何地方，恢复出来的会是一个联系不上的账号，
 * 且违背了当初对监护人「已删除」的承诺。
 */
public class StudentUnarchiveReq {

    /**
     * 恢复后的挂载父节点 ID；<b>留空表示原地恢复</b>。
     *
     * <p>PRD F1-8 规则 6：原节点已被删除或停用时<b>必须</b>显式指定新的挂载节点，
     * 否则返回 {@code 10101} / {@code 10109}。
     */
    private Long toParentNodeId;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Long getToParentNodeId() {
        return toParentNodeId;
    }

    public void setToParentNodeId(Long toParentNodeId) {
        this.toParentNodeId = toParentNodeId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
