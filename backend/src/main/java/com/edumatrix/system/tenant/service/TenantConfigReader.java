package com.edumatrix.system.tenant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.common.tenantconfig.TenantConfigHelper;
import com.edumatrix.common.tenantconfig.TenantConfigKey;
import com.edumatrix.system.tenant.mapper.SysTenantConfigMapper;

/**
 * {@link TenantConfigHelper} 的实现：读当前租户的配置，读不到回落平台默认值。
 *
 * <p>SPI 的一半——接口在 {@code common/tenantconfig/}，实现在这里。消费方是
 * <b>另一个领域</b>的模块 12（水印开关）与模块 13（完播阈值），而检查③禁止
 * {@code vod} import {@code system}。与 {@code common/account/PasswordHasher}
 * （auth 实现）、{@code common/tenant/CurrentContextProvider}（模块 02 实现）同一形态。
 *
 * <h2>为什么不复用 {@link TenantConfigService} 的查询</h2>
 * <p>那个类服务的是 §6.1/§6.2，调用者只可能是 {@code org_admin}，租户条件<b>全靠插件注入</b>；
 * 而本类的调用者包含<b>无会话入口</b>（模块 13 的 XXL-Job 按租户分片落盘）与可能的超管会话
 * ——超管会话下插件走整体放行，<b>不注入任何租户条件</b>。两种上下文对"租户条件从哪来"
 * 的答案不同，混用一条查询就会在其中一种下静默出错，所以本类走
 * {@code SysTenantConfigMapper#selectValue} 那条<b>显式带 {@code tenant_id}</b> 的点查。
 *
 * <h2>三种读不到，一律回落默认值</h2>
 * <ol>
 *   <li><b>本租户没写过这个键</b>——正常情形，白名单表格里每个键的默认值就是为它准备的；
 *   <li><b>库里的值解析不出整数</b>——历史脏数据。记 WARN 后回落，不让一次心跳落盘失败；
 *   <li><b>租户上下文缺失</b>——记 <b>ERROR</b> 后回落。<b>既不猜一个租户，也不去掉租户条件</b>
 *       （契约 §2.8 规则 3 的同向处置）；之所以不像写侧那样抛异常，是因为调用点是
 *       完播判定与凭证签发这类热路径，让一次配置读取中断它们不成比例——
 *       但 ERROR 日志保证它不是<b>静默</b>降级。
 * </ol>
 */
@Service
public class TenantConfigReader implements TenantConfigHelper {

    private static final Logger log = LoggerFactory.getLogger(TenantConfigReader.class);

    private final SysTenantConfigMapper sysTenantConfigMapper;

    public TenantConfigReader(SysTenantConfigMapper sysTenantConfigMapper) {
        this.sysTenantConfigMapper = sysTenantConfigMapper;
    }

    @Override
    public int getInt(TenantConfigKey key) {
        Long tenantId = TenantHelper.getTenantIdOrNull();
        if (tenantId == null) {
            log.error("读取租户配置时租户上下文缺失：key={}，回落平台默认值 {}。"
                            + "无会话入口（事件消费/定时任务/Worker）必须用 TenantHelper#runWithTenant 包住（契约 §2.8 规则 1）",
                    key.configKey(), key.defaultValue());
            return key.defaultValue();
        }

        String raw = sysTenantConfigMapper.selectValue(tenantId, key.configKey());
        if (raw == null) {
            // 本租户未自定义 —— 最常见的一支，不记日志（它是正常路径，不是异常）
            return key.defaultValue();
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (!key.isWithinRange(value)) {
                // 写侧（§6.2）拦得住新写入，但库里可能有更早的、或绕过接口改的值
                log.warn("租户配置越界：tenantId={} key={} value={} 不在 {} 内，回落默认值 {}",
                        tenantId, key.configKey(), value, key.rangeHint(), key.defaultValue());
                return key.defaultValue();
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("租户配置无法解析为整数：tenantId={} key={} value={}，回落默认值 {}",
                    tenantId, key.configKey(), raw, key.defaultValue());
            return key.defaultValue();
        }
    }
}
