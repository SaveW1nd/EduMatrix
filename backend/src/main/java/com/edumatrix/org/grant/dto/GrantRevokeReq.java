package com.edumatrix.org.grant.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 39 撤销资源授权（级联子树）（03-02 §9.3）的请求体。
 *
 * <h2>⚠ 这里<b>没有</b>、也永远不会有一个「要不要级联」的开关</h2>
 * <p>03-02 §9.3 逐字：「级联是<b>强制行为，不提供关闭开关</b>」。
 * 理由不是洁癖：逐级收缩模型下，撤销 N 对资源 X 的授权之后，
 * N 的下级持有的那些行<b>必然</b>是由 N 或 N 的上级授出的 ——
 * 只删 N 这一行就会留下「父级已无权、子级仍持有」的<b>悬挂授权</b>，
 * 而那批行<b>再也没有人能管理它们</b>（上级的列表里看不到，下级自己撤不了）。
 *
 * <p><b>把开关做进 DTO 是这条规则最可能的破坏方式</b>，所以它在编译期就不存在：
 * 字段不在这里，Service 的方法签名里也没有。要加它得同时改两处，
 * 而两处的注释都写着为什么不能加。
 */
public class GrantRevokeReq {

    @NotNull(message = "资源类型不能为空")
    @Min(value = 1, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    @Max(value = 3, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    private Integer resourceType;

    @NotEmpty(message = "资源不能为空")
    @Size(max = 500, message = "单次最多 500 个资源")
    private List<Long> resourceIds;

    /** 目标节点；<b>每个节点的整棵子树都会被级联撤销</b>。 */
    @NotEmpty(message = "目标节点不能为空")
    @Size(max = 500, message = "单次最多 500 个目标节点")
    private List<Long> targetNodeIds;

    /** 撤销原因，写入 {@code org_resource_grant.remark} 归档。 */
    @Size(max = 500, message = "撤销原因最长 500 字符")
    private String reason;

    public Integer getResourceType() {
        return resourceType;
    }

    public void setResourceType(Integer resourceType) {
        this.resourceType = resourceType;
    }

    public List<Long> getResourceIds() {
        return resourceIds;
    }

    public void setResourceIds(List<Long> resourceIds) {
        this.resourceIds = resourceIds;
    }

    public List<Long> getTargetNodeIds() {
        return targetNodeIds;
    }

    public void setTargetNodeIds(List<Long> targetNodeIds) {
        this.targetNodeIds = targetNodeIds;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
