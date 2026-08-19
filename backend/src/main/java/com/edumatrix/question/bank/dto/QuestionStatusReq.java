package com.edumatrix.question.bank.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 接口 11 启用/停用题目（03-04 §2.7）。
 *
 * <p>目标状态只能是 {@code 1} 启用或 {@code 2} 停用，<b>不允许改回 0 草稿</b>。
 */
public class QuestionStatusReq {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "只能改为 1 启用 或 2 停用")
    @Max(value = 2, message = "只能改为 1 启用 或 2 停用")
    private Integer status;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
