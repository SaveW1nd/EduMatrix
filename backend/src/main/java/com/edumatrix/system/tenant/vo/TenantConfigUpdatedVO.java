package com.edumatrix.system.tenant.vo;

import java.time.LocalDateTime;

/**
 * 修改租户配置的响应（03-01 §6.2）。
 *
 * <p><b>字段比 {@link TenantConfigItemVO} 少两个</b>（无 {@code defaultValue} / {@code description}），
 * 照 §6.2 的响应示例逐字。不复用列表行 VO 是因为多出来的字段会变成"接口实际返回什么"
 * 与"分册写着返回什么"之间的差，而这类差没有任何检查抓得到。
 *
 * <p>{@code isDefault} 在这里<b>恒为 {@code false}</b>：刚写入过的键当然不再是默认值。
 * 保留这个字段是为了让前端一份解析逻辑同时吃 §6.1 与 §6.2 的返回。
 */
public class TenantConfigUpdatedVO {

    private String configKey;
    private String configValue;
    private Boolean isDefault;
    private LocalDateTime updateTime;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
