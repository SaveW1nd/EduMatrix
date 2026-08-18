package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.Size;

/**
 * 接口 23 学生退课（03-02 §6.8）。
 *
 * <p>退课是<b>流失口径的唯一数据来源</b>（PRD F1-7 规则 6），且<b>不移动节点</b>——
 * 学员仍留在原导师 / 原管理员节点下，便于原责任人复盘与召回。
 */
public class StudentQuitReq {

    /** 退课原因，<b>必填</b>，写入 {@code org_student.quit_reason} 与轨迹 {@code reason}。 */
    @jakarta.validation.constraints.NotBlank(message = "不能为空")
    @Size(max = 500, message = "最长 500 字符")
    private String quitReason;

    public String getQuitReason() {
        return quitReason;
    }

    public void setQuitReason(String quitReason) {
        this.quitReason = quitReason;
    }
}
