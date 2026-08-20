package com.edumatrix.org.grant.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接口 38 授权资源给节点（03-02 §9.2）的请求体。
 *
 * <p>一次调用把 <b>N 个同类资源</b> 授权给 <b>M 个目标节点</b>（笛卡尔积）。
 * 两个数组各自上限 500，<b>而乘积另有 5000 的硬上限</b> —— 后者在
 * {@code GrantWriteService} 里判，因为它要先去重（见那里的注释）。
 */
public class GrantCreateReq {

    @NotNull(message = "资源类型不能为空")
    @Min(value = 1, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    @Max(value = 3, message = "资源类型只能是 1 课程 / 2 题目 / 3 视频")
    private Integer resourceType;

    @NotEmpty(message = "资源不能为空")
    @Size(max = 500, message = "单次最多 500 个资源")
    private List<Long> resourceIds;

    @NotEmpty(message = "目标节点不能为空")
    @Size(max = 500, message = "单次最多 500 个目标节点")
    private List<Long> targetNodeIds;

    /** 授权来源：1 手动选择（默认） 2 按节点批量 3 按标签批量 4 按名下全体 5 按权限模板。 */
    @Min(value = 1, message = "授权来源取值 1~5")
    @Max(value = 5, message = "授权来源取值 1~5")
    private Integer grantSource;

    /** 来源对象 ID（节点 / 标签 / 模板）；{@code grantSource = 1} 时留空。 */
    private Long sourceRefId;

    /** 重复授权是否跳过而非报错，默认 {@code false}（命中即整批回滚 {@code 10303}）。 */
    private Boolean ignoreDuplicate;

    public boolean ignoreDuplicate() {
        return Boolean.TRUE.equals(ignoreDuplicate);
    }

    /** 未指定时按 {@code 1 手动选择}（§9.2 参数表默认值）。 */
    public int grantSourceOrDefault() {
        return grantSource == null ? 1 : grantSource;
    }

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

    public Integer getGrantSource() {
        return grantSource;
    }

    public void setGrantSource(Integer grantSource) {
        this.grantSource = grantSource;
    }

    public Long getSourceRefId() {
        return sourceRefId;
    }

    public void setSourceRefId(Long sourceRefId) {
        this.sourceRefId = sourceRefId;
    }

    public Boolean getIgnoreDuplicate() {
        return ignoreDuplicate;
    }

    public void setIgnoreDuplicate(Boolean ignoreDuplicate) {
        this.ignoreDuplicate = ignoreDuplicate;
    }
}
