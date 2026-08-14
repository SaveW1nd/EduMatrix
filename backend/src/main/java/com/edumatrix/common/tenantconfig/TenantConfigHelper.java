package com.edumatrix.common.tenantconfig;

/**
 * 读取当前租户的配置值，<b>读不到时回落平台默认值</b>（04-实施计划.md 模块 04「对外产出」、
 * 02-数据库设计 §4.1.10）。SPI：本接口在 {@code common/}，实现
 * （{@code system/tenant/service/TenantConfigReader}）在 {@code system/} 领域。
 *
 * <h2>为什么是 SPI 而不是让消费方直接调 {@code system} 的 Service</h2>
 * <p>消费方在<b>另一个领域</b>：模块 13 判完播读 {@code complete_rate_threshold}、
 * 模块 12 发播放凭证读 {@code watermark_phone_mask}，两者都落在 {@code vod/}；
 * 而 05-工程结构.md §A1 的第三条硬约束禁止领域包互相 import
 * （{@code scripts/check_backend_conventions.sh} 检查③）。
 * 与 {@code common/account/PasswordHasher}、{@code common/account/SessionRevoker}、
 * {@code common/tenant/CurrentContextProvider} <b>形状完全相同</b>：
 * common 声明接口、另一个领域注册实现 Bean。<b>照既有先例走，不发明第四种做法</b>
 * （因此也不是 Java 的 {@code ServiceLoader}，就是一个 Spring Bean）。
 *
 * <h2>为什么实现不能整体搬进 {@code common/}</h2>
 * <p>判据是 {@code common/frozen/FrozenNodeCache} 当初进 {@code common} 的那条：
 * 它是<b>纯 Redis 结构 + 一条判定规则</b>。而本接口的实现要点查一张业务表
 * （{@code sys_tenant_config}）、处理"本租户无行则回落"、还要承载白名单的类型与值域
 * ——<b>那是业务逻辑</b>，而 {@code common} 是模块 01 的唯一产出地（05-工程结构.md §E）。
 *
 * <h2>租户上下文从哪来</h2>
 * <p>本接口<b>不收 {@code tenantId} 参数</b>，读的永远是"当前租户"，取值走
 * {@code TenantHelper} 的四条路径：Web 请求走会话；模块 13 的 XXL-Job 落盘按租户分片，
 * 每片用 {@code runWithTenant} 包住（契约 §2.8 规则 1）。
 * <b>租户上下文缺失时既不猜一个租户、也不去掉租户条件</b>：实现记 ERROR 后回落平台默认值
 * ——降级是有的，但不是<b>静默</b>降级。
 */
public interface TenantConfigHelper {

    /**
     * 取整型配置；本租户未写入过该键、或写入的值无法解析时<b>回落
     * {@link TenantConfigKey#defaultValue()}</b>。
     *
     * <p><b>不抛异常</b>：调用方是完播判定与水印签发这类<b>热路径</b>，
     * 让一次配置读取失败去中断一次心跳落盘或一次凭证签发是不成比例的；
     * 回落到平台默认值是契约 §5 白名单表格里逐键写死的行为。
     */
    int getInt(TenantConfigKey key);
}
