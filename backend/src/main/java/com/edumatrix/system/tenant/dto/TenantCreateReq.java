package com.edumatrix.system.tenant.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建租户（开通机构）请求（03-01 §5.3）。<b>仅 {@code super_admin} 可调。</b>
 *
 * <p><b>无 {@code rootNodeId} / {@code parentNodeId} 参数</b>（§5.3 原文）：
 * 机构根节点由系统创建并固定挂在平台树根（{@code parent_id = 0}），不由调用方指定；
 * 其 id 亦不由调用方指定——它<b>就是</b>本次生成的租户 id（契约 §2.1）。
 *
 * <p><b>无密码参数</b>：初始密码由系统随机生成，仅在响应中一次性返回
 * （PRD F1-1 规则 6）。这与 03-01 §2.2 由调用方指定 {@code password} 且不回显
 * 是<b>两条相反的约定</b>，各自分册定义，不要互相看齐。
 */
public class TenantCreateReq {

    /** 机构名称，最长 50 字，<b>全局唯一</b>（重复返回 400）；同时作为机构根节点的 {@code node_name}。 */
    @NotBlank(message = "不能为空")
    @Size(max = 50, message = "最长 50 字")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String contactName;

    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^\\d{11}$", message = "须为 11 位数字")
    private String contactPhone;

    /**
     * 服务到期时间（{@code yyyy-MM-dd HH:mm:ss}），<b>须晚于当前时间</b>（早于返回 400）。
     *
     * <p>格式校验由 Jackson 承担（{@code JacksonConfig} 全局注册了该 pattern），
     * 格式不合法在反序列化阶段就是 400；「晚于当前时间」是业务判定，在 Service 里。
     */
    @NotNull(message = "不能为空")
    private LocalDateTime expireTime;

    /** 学生数上限，≥1。达到后新增/导入学生被拒（{@code 10207}，PRD F1-1 规则 5）。 */
    @NotNull(message = "不能为空")
    @Min(value = 1, message = "须 ≥ 1")
    private Integer maxStudentCount;

    /** 初始机构管理员用户名，4~30 位，<b>全平台唯一</b>（冲突返回 {@code 10001}）。 */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^\\w{4,30}$", message = "须为 4~30 位字母、数字或下划线")
    private String adminUsername;

    /**
     * 初始机构管理员姓名，最长 30 字。
     *
     * <p><b>它不是机构根节点的 {@code node_name}</b>：§5.3 步骤②表写「{@code node_name}
     * 默认取机构名称」，响应示例的 {@code adminNodePath} 亦是机构名，§5.0 的树形图与
     * §5.4「改机构名同步更新根节点 {@code node_name}」同向；只有 §5.3 参数表的括注
     * 写着「同时作为其节点的 {@code node_name}」——四比一，取机构名称。
     * 该处已登记为 04-实施计划.md §E 的 <b>F-24</b>，待分册订正。
     */
    @NotBlank(message = "不能为空")
    @Size(max = 30, message = "最长 30 字")
    private String adminRealName;

    @Size(max = 500, message = "最长 500 字")
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getMaxStudentCount() {
        return maxStudentCount;
    }

    public void setMaxStudentCount(Integer maxStudentCount) {
        this.maxStudentCount = maxStudentCount;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminRealName() {
        return adminRealName;
    }

    public void setAdminRealName(String adminRealName) {
        this.adminRealName = adminRealName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
