package com.edumatrix.org.node.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改节点请求（03-02 §3.3）。<b>只改展示属性。</b>
 *
 * <p><b>{@code parentId} 不可通过本接口修改</b>，必须走接口 4（移动节点）——
 * 否则 {@code ancestors} 重算与异动轨迹都会缺失，而 {@code ancestors} 正是鉴权依据。
 * <b>{@code nodeType} 与 {@code refUserId} 一经创建不可变更</b>
 * （02-数据库设计 §3.1.5：原地改 {@code node_type} 会让既有子树瞬间违规）。
 * 三者都<b>不在本 DTO 里</b> —— 不给字段比给了再拒绝更难写错。
 */
public class NodeUpdateReq {

    /** 节点名称，同父节点下唯一（重复 → {@code 10102}），最长 100 字符。 */
    @NotBlank(message = "不能为空")
    @Size(max = 100, message = "最长 100 字符")
    private String nodeName;

    /** 同级排序号。 */
    private Integer sort;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
