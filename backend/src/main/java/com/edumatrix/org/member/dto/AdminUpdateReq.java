package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 接口 9 修改管理员（03-02 §4.3）。
 *
 * <p><b>上级变更不走本接口</b>，必须使用接口 4（移动节点）—— 否则 {@code ancestors} 重算
 * 与异动轨迹都会缺失，而 {@code ancestors} 正是鉴权依据。密码重置走 03-02 接口 6。
 */
public class AdminUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String realName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "\\d{11}", message = "须为 11 位手机号")
    private String phone;

    /** 留空表示<b>不修改</b>。 */
    @Size(max = 30, message = "最长 30 字符")
    private String username;

    /** 留空表示<b>不修改</b>。 */
    @Size(max = 100, message = "最长 100 字符")
    private String nodeName;

    private Integer sort;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

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
