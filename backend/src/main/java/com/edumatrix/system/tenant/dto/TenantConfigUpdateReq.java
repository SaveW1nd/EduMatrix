package com.edumatrix.system.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改租户配置请求（03-01 §6.2）。
 *
 * <p>{@code configValue} <b>统一字符串传输</b>，类型与值域<b>按键</b>由服务端校验
 * （{@code complete_rate_threshold}：60~100 的整数；{@code watermark_phone_mask}：0/1）——
 * 权威定义在 {@code common/tenantconfig/TenantConfigKey}，<b>不在这里写注解</b>：
 * 不同的键值域不同，注解表达不了"按键分叉"，而写死一套就等于给两个键取交集。
 */
public class TenantConfigUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 200, message = "最长 200 字")
    private String configValue;

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }
}
