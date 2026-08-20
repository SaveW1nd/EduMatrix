package com.edumatrix.org.grant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 接口 51 授权健康度巡检结果查询（03-02 §9.6）的查询参数。
 *
 * <p>{@code type} 三个取值<b>语义互不重叠</b>，且 {@code dangling} 与 {@code crossScope}
 * <b>不得合并</b>（契约 §2.5 规则 6）。
 */
public class GrantHealthQueryReq {

    /** {@code dangling} 真悬挂 / {@code crossScope} 跨管辖 / {@code expiring} 30 天内到期。 */
    @NotBlank(message = "type 不能为空")
    @Pattern(regexp = "dangling|crossScope|expiring",
            message = "type 只能是 dangling / crossScope / expiring")
    private String type;

    @Min(value = 1, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    @Max(value = 3, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    private Integer resourceType;

    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNum;

    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最大 100")
    private Integer pageSize;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
