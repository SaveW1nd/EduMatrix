package com.edumatrix.org.grant.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 接口 40 修改授权有效期（03-02 §9.4）的请求体。
 *
 * <h2>⚠ 「不传」与「传 null」是两个意思，必须分得开</h2>
 * <p>§9.4 参数表逐字：{@code validEnd}「<b>不传</b>保持原值。<b>传 {@code null}</b>
 * 表示改为长期有效」。而 Jackson 反序列化后这两种情况的字段值<b>都是 {@code null}</b> ——
 * 光看字段区分不了。
 *
 * <p>处置：靠<b>setter 是否被调用</b>区分。Jackson 只在 JSON 里<b>出现该键</b>时调 setter，
 * 于是「不传」→ setter 没被调用 → {@code validEndPresent = false}；
 * 「传 null」→ setter 被调用且值为 null → {@code validEndPresent = true}。
 *
 * <p><b>不区分的后果不是报错，是改错</b>：只想改 {@code validStart} 的请求
 * 会把 {@code validEnd} 一起抹成「永久有效」——
 * 一次续期把到期日删掉了，而<b>接口返回 200</b>。
 */
public class GrantValidityUpdateReq {

    @NotNull(message = "资源类型不能为空")
    @Min(value = 1, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    @Max(value = 3, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    private Integer resourceType;

    @NotNull(message = "资源 ID 不能为空")
    private Long resourceId;

    @NotNull(message = "目标节点不能为空")
    private Long targetNodeId;

    private LocalDateTime validStart;
    private boolean validStartPresent;

    private LocalDateTime validEnd;
    private boolean validEndPresent;

    /** JSON 里出现了 {@code validStart} 这个键（无论值是不是 null）。 */
    public boolean validStartPresent() {
        return validStartPresent;
    }

    /** JSON 里出现了 {@code validEnd} 这个键（无论值是不是 null）。 */
    public boolean validEndPresent() {
        return validEndPresent;
    }

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public Long getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(Long targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public LocalDateTime getValidStart() {
        return validStart;
    }

    public void setValidStart(LocalDateTime validStart) {
        this.validStart = validStart;
        this.validStartPresent = true;
    }

    public LocalDateTime getValidEnd() {
        return validEnd;
    }

    public void setValidEnd(LocalDateTime validEnd) {
        this.validEnd = validEnd;
        this.validEndPresent = true;
    }
}
