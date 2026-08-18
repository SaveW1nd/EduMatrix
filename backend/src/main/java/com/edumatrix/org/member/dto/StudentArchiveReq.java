package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 接口 24 批量毕业归档（03-02 §6.9）。
 *
 * <h2>{@code archiveReason} 决定<b>脱不脱敏</b>，两条路后果相反</h2>
 * <ul>
 *   <li>{@code 1} 正常毕业（默认）：仅改学籍状态，<b>联系方式原样保留</b>——机构仍需联系校友；
 *   <li>{@code 2} 因监护人删除请求：启动 <b>30 日</b>脱敏倒计时，<b>该操作不可逆</b>，前端须二次确认。
 * </ul>
 *
 * <p>{@code studentIds} 与 {@code nodeId} <b>二选一</b>，同时传或都不传返回 {@code 400}。
 */
public class StudentArchiveReq {

    /** 归档原因：1 正常毕业（默认） 2 因监护人删除请求。 */
    private Integer archiveReason;

    /** 按名单归档。与 {@code nodeId} 二选一，单次最多 500 个。 */
    @Size(max = 500, message = "单次最多 500 个")
    private List<Long> studentIds;

    /** 按<b>子树</b>整批归档：归档该节点整棵子树内全部在读学员，节点本身不变、不删除。 */
    private Long nodeId;

    /** 归档说明，写入每条轨迹的 {@code reason}。 */
    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Integer getArchiveReason() {
        return archiveReason;
    }

    public void setArchiveReason(Integer archiveReason) {
        this.archiveReason = archiveReason;
    }

    public List<Long> getStudentIds() {
        return studentIds;
    }

    public void setStudentIds(List<Long> studentIds) {
        this.studentIds = studentIds;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
