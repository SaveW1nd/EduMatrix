package com.edumatrix.course.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 接口 6 课程上下架（03-03 §1.6）。
 *
 * <p>{@code targetStatus} <b>只允许 1 或 2</b>，不允许传 0（§1.6 规则 1）——
 * 草稿是初始态，不是可流转到的目标态。
 */
public class CourseShelfReq {

    @NotNull(message = "不能为空")
    @Min(value = 1, message = "只允许 1 上架 或 2 下架")
    @Max(value = 2, message = "只允许 1 上架 或 2 下架")
    private Integer targetStatus;

    public Integer getTargetStatus() {
        return targetStatus;
    }

    public void setTargetStatus(Integer targetStatus) {
        this.targetStatus = targetStatus;
    }
}
