package com.edumatrix.org.grant.vo;

/** {@code cascadeDetail[].cascadeNodes[]} 的一个元素（03-02 §9.3 响应示例）。 */
public class CascadeNodeVO {

    private Long nodeId;
    private String nodeName;

    /** 1 管理员 2 教师 3 学生。 */
    private Integer nodeType;

    public CascadeNodeVO() {
    }

    public CascadeNodeVO(Long nodeId, String nodeName, Integer nodeType) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Integer getNodeType() {
        return nodeType;
    }

    public void setNodeType(Integer nodeType) {
        this.nodeType = nodeType;
    }
}
