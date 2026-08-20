package com.edumatrix.org.grant.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code cascadeDetail[]} 的一行 —— 一个 {@code (资源, 目标节点)} 组合的影响面。
 *
 * <p>03-02 §9.3：级联是强制行为，<b>响应通过 {@code cascadeDetail} 完整披露被连带撤销的节点，
 * 供操作者确认影响面</b>。不披露就等于静默地替操作者做了一个他不知道的决定。
 */
public class CascadeDetailVO {

    private Long resourceId;
    private String resourceName;
    private Long targetNodeId;
    private String targetNodeName;

    /** 被级联撤销的节点<b>样本，最多前 50 个</b>；完整数量见 {@link #cascadeNodeCount}。 */
    private List<CascadeNodeVO> cascadeNodes = new ArrayList<>();

    /** 被级联撤销的下级节点数（<b>不含</b>目标节点自身）。 */
    private Integer cascadeNodeCount;

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public Long getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(Long targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public String getTargetNodeName() {
        return targetNodeName;
    }

    public void setTargetNodeName(String targetNodeName) {
        this.targetNodeName = targetNodeName;
    }

    public List<CascadeNodeVO> getCascadeNodes() {
        return cascadeNodes;
    }

    public void setCascadeNodes(List<CascadeNodeVO> cascadeNodes) {
        this.cascadeNodes = cascadeNodes == null ? new ArrayList<>() : cascadeNodes;
    }

    public Integer getCascadeNodeCount() {
        return cascadeNodeCount;
    }

    public void setCascadeNodeCount(Integer cascadeNodeCount) {
        this.cascadeNodeCount = cascadeNodeCount;
    }
}
