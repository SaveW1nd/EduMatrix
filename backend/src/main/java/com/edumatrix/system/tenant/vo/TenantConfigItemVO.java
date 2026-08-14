package com.edumatrix.system.tenant.vo;

import java.time.LocalDateTime;

/**
 * 租户配置项（03-01 §6.1 列表行，§6.2 修改后的回显）。
 *
 * <p>§6.1 固定返回<b>键白名单内全部配置项</b>，含本租户从未自定义过的键——
 * 那些键在 {@code sys_tenant_config} 里<b>没有行</b>，是按
 * {@code common/tenantconfig/TenantConfigKey} 补出来的，此时
 * {@code isDefault = true}、{@code updateTime = null}。
 *
 * <p><b>{@code isDefault} 用 {@code Boolean} + {@code getIsDefault()} 而不是
 * {@code boolean} + {@code isDefault()}</b>：后者被 Jackson 序列化成
 * {@code "default"}（去掉 is 前缀），字段名当场对不上分册。
 */
public class TenantConfigItemVO {

    private String configKey;

    /** 当前生效值（统一字符串）；本租户未自定义时等于 {@link #defaultValue}。 */
    private String configValue;

    /** 平台默认值。 */
    private String defaultValue;

    /** {@code true} 表示本租户未自定义（{@code sys_tenant_config} 无记录，回落默认值）。 */
    private Boolean isDefault;

    /** 配置项说明（含值域），可直接用于设置页展示。 */
    private String description;

    /** 最近修改时间；未自定义时为 {@code null}。 */
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

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
