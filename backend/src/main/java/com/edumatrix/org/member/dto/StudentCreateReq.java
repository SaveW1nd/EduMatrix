package com.edumatrix.org.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

/**
 * 接口 17 创建学生（03-02 §6.2）。
 *
 * <h2>{@code guardianConsent} 是<b>合规前置</b>，不是普通必填项</h2>
 * <p>PRD F7-1 的验收标准逐字：「未勾选『已取得监护人同意』→ 返回 {@code 400}，
 * <b>学生未被创建</b>」。本字段在<b>参数校验</b>阶段被拦下，请求根本进不到 Service ——
 * 这正是「无任何节点/账号/档案产生」最不容易写错的实现方式。
 * 个保法第 31 条对未成年人信息的处理前提，<b>不可省略也不可默认 true</b>。
 *
 * <h2>⚠ {@code @AssertTrue} 单独用<b>拦不住「不传」</b>，必须配 {@code @NotNull}</h2>
 * <p>Bean Validation 的约定是「<b>{@code null} 一律视为通过</b>」（{@code @AssertTrue}
 * 的 Javadoc 逐字：{@code null} elements are considered valid）。
 * 只标 {@code @AssertTrue} 时，{@code guardianConsent: false} 会被拒，
 * <b>而整个字段不传会被放行</b> —— 于是「未勾选」这三个字里最常见的那一种
 * （前端根本没提交这个字段）反而建档成功了。
 *
 * <p>这不是理论风险：<b>本项目第一版就是这么写的，被
 * {@code MemberCreateIT#missingGuardianConsentCreatesNothing} 当场测出来</b>
 * （显式 {@code false} 返回 400、不传返回 200）。只测 {@code false} 那一半是发现不了的 ——
 * 所以那个用例把两种形态<b>都</b>断言了。
 */
public class StudentCreateReq {

    /**
     * <b>已取得监护人同意</b>。必须为 {@code true}，否则 {@code 400}（PRD F7-1）。
     *
     * <p>服务端据此写 {@code sys_oper_log} 留痕（操作人 + 时间戳）——
     * 机构线下签署的知情同意书由机构自行留存，系统侧只承担留痕责任。
     */
    @NotNull(message = "须确认已取得监护人同意（PRD F7-1）")
    @AssertTrue(message = "须确认已取得监护人同意（PRD F7-1）")
    private Boolean guardianConsent;

    /**
     * 挂载父节点 ID（管理员或教师节点）；<b>留空默认取创建者所在节点</b>。
     *
     * <p>PRD F1-3 规则 4：管理员创建 → 「已归属该管理员、尚未分配导师」；
     * <b>教师创建 → 即刻成为其名下学员</b>。两种语义由「默认挂创建者节点」一条规则同时实现。
     */
    private Long parentNodeId;

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String realName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "\\d{11}", message = "须为 11 位手机号")
    private String phone;

    /** 学号，<b>机构内唯一</b>（{@code 10202}）。 */
    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字符")
    private String studentNo;

    @Size(max = 30, message = "最长 30 字符")
    private String username;

    /** 监护人姓名。<b>脱敏时覆写为姓氏 + {@code *}，绝不置 NULL</b>（契约 §2.2）。 */
    @Size(max = 30, message = "最长 30 字符")
    private String guardianName;

    /** 监护人电话。<b>脱敏时覆写为掩码</b>，理由同上。 */
    @Pattern(regexp = "^$|\\d{11}", message = "须为 11 位手机号")
    private String guardianPhone;

    /**
     * 初始密码，8~20 位且<b>同时含字母与数字</b>；留空由服务端随机生成 ≥12 位强口令。
     *
     * <p>两种情况下明文都<b>仅在本次响应返回一次</b>，不落库、不可再查（PRD §7.3）。
     * <b>不接受手机号后 6 位等可由账号推导的弱口令</b>——用户名即手机号，
     * 同源意味着拿到名单即可登录任意账号（PRD F1-3 规则 3）。
     */
    @Size(min = 8, max = 20, message = "长度须为 8~20 位")
    private String initPassword;

    /** 权限模板 ID。<b>本模块不实现套用</b>（模块 11/17）。 */
    private Long templateId;

    @Size(max = 500, message = "最长 500 字符")
    private String remark;

    public Boolean getGuardianConsent() {
        return guardianConsent;
    }

    public void setGuardianConsent(Boolean guardianConsent) {
        this.guardianConsent = guardianConsent;
    }

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

    public String getStudentNo() {
        return studentNo;
    }

    public void setStudentNo(String studentNo) {
        this.studentNo = studentNo;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getGuardianName() {
        return guardianName;
    }

    public void setGuardianName(String guardianName) {
        this.guardianName = guardianName;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }

    public void setGuardianPhone(String guardianPhone) {
        this.guardianPhone = guardianPhone;
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
