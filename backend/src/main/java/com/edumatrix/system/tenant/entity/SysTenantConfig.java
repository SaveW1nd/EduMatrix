package com.edumatrix.system.tenant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.TenantEntity;
import com.edumatrix.common.tenantconfig.TenantConfigKey;

/**
 * {@code sys_tenant_config} 租户配置表（03-01 §6，02-数据库设计 §4.1.10）。
 *
 * <p>KV 存储：{@code config_key} + {@code config_value} <b>统一字符串</b>，
 * 类型与取值范围由服务层<b>按键</b>解析校验——权威定义在
 * {@link TenantConfigKey}（契约 §5 末的白名单，穷举只有两个键）。
 *
 * <p>{@code uk_tenant_config_key(tenant_id, config_key, deleted_at)}：
 * 同租户同键唯一，§6.2 的写入按它 UPSERT，故 PUT 幂等可安全重试。
 *
 * <p><b>本租户未写入过的键在表里就是没有行</b>，读取时回落平台默认值——
 * 不预置默认行（开通租户时也不写），否则"平台改了默认值"就无法惠及存量租户，
 * 而 §6.1 要求把未自定义的键也列出来（{@code isDefault = true}），它是<b>算出来的</b>不是查出来的。
 */
@TableName("sys_tenant_config")
public class SysTenantConfig extends TenantEntity {

    private static final long serialVersionUID = 1L;

    private String configKey;

    private String configValue;

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
}
