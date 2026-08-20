package com.edumatrix.vod.media.dto;

import com.edumatrix.vod.media.entity.VodVideo;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 34 媒资禁用/启用（03-03 §7.6）。
 *
 * <p>§7.6 逐字：「{@code targetStatus} 仅允许 2（正常/启用）或 9（禁用），<b>其余值返回 400</b>」——
 * 所以这是<b>参数校验</b>不是业务判定，要在查库之前拦下。
 *
 * <p><b>{@code @NotNull} 与 {@code @AssertTrue} 必须同时有</b>：{@code @AssertTrue}
 * 作用在派生方法上，字段<b>不传</b>时 {@code targetStatus} 为 null，
 * 而那时派生方法若返回 true 就会放行 —— 本项目已就这一点踩过一次
 * （F 清单里 {@code @AssertTrue} 拦不住不传，是「以为存在的保障」五例之一）。
 */
public class VideoStatusReq {

    @NotNull(message = "目标状态不能为空")
    private Integer targetStatus;

    @Size(max = 500, message = "操作原因不得超过 500 字符")
    private String remark;

    /** 只允许 {@code 2 ↔ 9}；其余值 400。null 由 {@code @NotNull} 负责，这里放行以免两条消息叠加。 */
    @AssertTrue(message = "目标状态仅允许 2（正常）或 9（禁用）")
    public boolean isTargetStatusAllowed() {
        return targetStatus == null
                || targetStatus == VodVideo.STATUS_NORMAL
                || targetStatus == VodVideo.STATUS_DISABLED;
    }

    public Integer getTargetStatus() {
        return targetStatus;
    }

    public void setTargetStatus(Integer targetStatus) {
        this.targetStatus = targetStatus;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
