package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 接口 8 新建下级管理员（03-02 §4.2）。 */
public class AdminCreateReq {

    /** 上级节点 ID，<b>须为管理员节点</b>（{@code node_type=1}）；父为教师 → {@code 10105}，为学生 → {@code 10106}。 */
    @NotNull(message = "不能为空")
    private Long parentNodeId;

    /** 真实姓名，最长 30 字符；同时作为节点名称默认值。 */
    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String realName;

    /** 手机号，11 位，本租户内唯一（{@code 10013}）。 */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "\\d{11}", message = "须为 11 位手机号")
    private String phone;

    /** 登录账号，全平台唯一（{@code 10001}）；留空默认取 {@code phone}。 */
    @Size(max = 30, message = "最长 30 字符")
    private String username;

    /** 节点名称，同父节点下唯一（{@code 10102}）；留空默认取 {@code realName}。 */
    @Size(max = 100, message = "最长 100 字符")
    private String nodeName;

    /** 同级排序号，默认 0。 */
    private Integer sort;

    /**
     * 初始密码，8~20 位且<b>同时含字母与数字</b>；留空由服务端随机生成 ≥12 位强口令。
     *
     * <p>两种情况下明文都<b>仅在本次响应返回一次</b>，不落库、不可再查（PRD §7.3）。
     * <b>不接受手机号后 6 位等可由账号推导的弱口令</b>——用户名即手机号，
     * 同源意味着拿到名单即可登录任意账号（PRD F1-3 规则 3）。
     */
    @Size(min = 8, max = 20, message = "长度须为 8~20 位")
    private String initPassword;

    /** 权限模板 ID。<b>本模块不实现套用</b>（模块 11/17 的交付物），传了会留一条 WARN。 */
    private Long templateId;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Long getParentNodeId() {
        return parentNodeId;
    }

    public void setParentNodeId(Long parentNodeId) {
        this.parentNodeId = parentNodeId;
    }

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

    public String getInitPassword() {
        return initPassword;
    }

    public void setInitPassword(String initPassword) {
        this.initPassword = initPassword;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
