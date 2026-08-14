package com.edumatrix.org.node.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 节点停用 / 启用请求（03-02 §3.5）。
 *
 * <h2>没有 {@code cascade} 参数，这是有意的</h2>
 * <p>§3.5：「<b>停用效果按节点类型自动区分，无 {@code cascade} 参数</b>」——
 * 停用管理员节点即冻结其整棵子树，停用教师/学生节点仅本人。
 * 效果由 {@code node_type} 决定，给参数只会让调用方以为可以选。
 */
public class NodeStatusReq {

    /** 目标状态：0 正常 1 停用。 */
    @NotNull(message = "不能为空")
    @Min(value = 0, message = "只能是 0 正常 / 1 停用")
    @Max(value = 1, message = "只能是 0 正常 / 1 停用")
    private Integer status;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
