package com.edumatrix.system.tenant.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.common.tenantconfig.TenantConfigKey;
import com.edumatrix.system.tenant.dto.TenantConfigUpdateReq;
import com.edumatrix.system.tenant.entity.SysTenantConfig;
import com.edumatrix.system.tenant.mapper.SysTenantConfigMapper;
import com.edumatrix.system.tenant.vo.TenantConfigItemVO;
import com.edumatrix.system.tenant.vo.TenantConfigUpdatedVO;

/**
 * 租户配置的查询与修改（03-01 §6.1 / §6.2）。
 *
 * <h2>本组两个接口仅 {@code org_admin}</h2>
 * <p>§6.1 数据权限栏：租户配置是<b>机构级</b>配置，与节点子树无关，仅机构管理员可维护；
 * {@code teacher} / {@code student} 及 <b>{@code super_admin}（平台级无租户上下文）</b>
 * 调用返回 403。同样靠 {@code sys_role_menu} 的初始化数据实现
 * （{@code system:tenantConfig:list/edit} 只绑了 {@code org_admin}），本层不写 if。
 *
 * <h2>租户过滤全靠插件</h2>
 * <p>{@code sys_tenant_config} 带 {@code tenant_id} 且不在放行清单里（契约 §2.9），
 * 而调用者只可能是 {@code org_admin}（有会话租户）——所以这里的查询<b>一个租户条件都不写</b>，
 * 与 {@code SysRoleService} 同一形态。跨领域读取的那一条（{@code TenantConfigHelper}）
 * 不走本类，理由见 {@link TenantConfigReader}。
 */
@Service
public class TenantConfigService {

    private final SysTenantConfigMapper sysTenantConfigMapper;

    public TenantConfigService(SysTenantConfigMapper sysTenantConfigMapper) {
        this.sysTenantConfigMapper = sysTenantConfigMapper;
    }

    // =====================================================================
    // §6.1 查询租户配置列表
    // =====================================================================

    /**
     * 固定返回<b>键白名单内全部配置项</b>，含本租户未自定义过的键，按 {@code configKey} 升序。
     *
     * <p><b>以白名单为准、以库为辅</b>：先列出 {@link TenantConfigKey} 的全部枚举项，
     * 再用库里的行去覆盖。反过来（以库里的行为准）会有两个后果：未自定义的键根本不出现，
     * 而库里若残留一个已被移出白名单的键，它反而会被列出来——白名单是穷举，
     * <b>不在其中的行不该出现在任何响应里</b>。
     *
     * <p>非分页（§6.1：数组），所以返回 {@code List} 而不是 {@code PageResult}。
     */
    public List<TenantConfigItemVO> list() {
        Map<String, SysTenantConfig> saved = new LinkedHashMap<>();
        for (SysTenantConfig row : sysTenantConfigMapper.selectList(
                new LambdaQueryWrapper<SysTenantConfig>())) {
            saved.put(row.getConfigKey(), row);
        }

        List<TenantConfigItemVO> list = new ArrayList<>();
        for (TenantConfigKey key : TenantConfigKey.values()) {
            SysTenantConfig row = saved.get(key.configKey());
            TenantConfigItemVO vo = new TenantConfigItemVO();
            vo.setConfigKey(key.configKey());
            vo.setConfigValue(row == null ? key.defaultValueAsString() : row.getConfigValue());
            vo.setDefaultValue(key.defaultValueAsString());
            vo.setIsDefault(row == null);
            vo.setDescription(key.description());
            vo.setUpdateTime(row == null ? null : row.getUpdateTime());
            list.add(vo);
        }
        // 数组按 configKey 升序（§6.1 响应示例的标注）
        list.sort((a, b) -> a.getConfigKey().compareTo(b.getConfigKey()));
        return list;
    }

    // =====================================================================
    // §6.2 修改租户配置
    // =====================================================================

    /**
     * 按键写入/更新（命中 {@code uk_tenant_config_key} 则更新），<b>PUT 幂等可安全重试</b>。
     *
     * <p>校验分两层，<b>返回的码不同、含义也不同</b>：
     * <ul>
     *   <li>键不在白名单 → <b>{@code 10016}</b>「配置键不存在或不允许修改」（业务码，HTTP 200）；
     *   <li>值的类型不符或超出值域 → <b>400</b>，{@code msg} 提示该键的合法范围。
     * </ul>
     * 两者不可合并：前者是"这个键根本不存在"，后者是"键对、值不对"，前端的处置完全不同。
     *
     * <p>新值<b>即时生效，且只作用于此后的业务判定</b>（§6.2）：改
     * {@code complete_rate_threshold} 只影响后续心跳落盘时的完播判定，
     * <b>存量 {@code watch_status} 不回溯重算</b>；改 {@code watermark_phone_mask}
     * 只影响此后新发放的播放凭证。所以这里<b>不需要清任何缓存、不需要通知任何人</b>——
     * 消费方每次判定都现读（{@link com.edumatrix.common.tenantconfig.TenantConfigHelper}）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TenantConfigUpdatedVO update(String configKey, TenantConfigUpdateReq req) {
        TenantConfigKey key = TenantConfigKey.of(configKey);
        if (key == null) {
            // 白名单是穷举（契约 §5 末）。想加第三个键必须先改契约，不在这里放行
            throw new BizException(ErrorCode.CONFIG_KEY_NOT_ALLOWED);
        }
        int value = parseIntOrBadRequest(key, req.getConfigValue());
        if (!key.isWithinRange(value)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    key.configKey() + " 的合法范围是 " + key.rangeHint());
        }

        SysTenantConfig existing = sysTenantConfigMapper.selectOne(
                new LambdaQueryWrapper<SysTenantConfig>()
                        .eq(SysTenantConfig::getConfigKey, key.configKey())
                        .last("LIMIT 1"));

        // 统一按解析后的值写回字符串形态：入参 " 85 " 与 "85" 落库后是同一个值，
        // 否则读侧的 Integer.parseInt 会在某些历史行上失败并静默回落默认值
        String normalized = String.valueOf(value);
        if (existing == null) {
            SysTenantConfig row = new SysTenantConfig();
            row.setConfigKey(key.configKey());
            row.setConfigValue(normalized);
            // 显式写 tenant_id（契约 §2.8 规则 1「从数据显式取」）。插件在 org_admin 会话下
            // 也会注入，MyBatis-Plus 见列已存在即跳过，两者不冲突
            row.setTenantId(TenantHelper.requireTenantId());
            sysTenantConfigMapper.insert(row);
            existing = sysTenantConfigMapper.selectById(row.getId());
        } else {
            SysTenantConfig update = new SysTenantConfig();
            update.setId(existing.getId());
            update.setConfigValue(normalized);
            sysTenantConfigMapper.updateById(update);
            existing = sysTenantConfigMapper.selectById(existing.getId());
        }

        TenantConfigUpdatedVO vo = new TenantConfigUpdatedVO();
        vo.setConfigKey(key.configKey());
        vo.setConfigValue(existing.getConfigValue());
        // 刚写过的键当然不再是默认值
        vo.setIsDefault(false);
        vo.setUpdateTime(existing.getUpdateTime());
        return vo;
    }

    /**
     * 两个键都是 int（契约 §5 白名单的「类型」列）。解析失败 → 400。
     *
     * <p><b>不做隐式转换</b>（{@code "1.0"} / {@code "true"} 一律拒绝）：
     * 契约 §5 对判断题答案那条「{@code true != "true"}，服务端反序列化时必须做类型校验，
     * 收到字符串直接返回 400，不做隐式转换」是同一条纪律的另一处落点。
     */
    private int parseIntOrBadRequest(TenantConfigKey key, String rawValue) {
        try {
            return Integer.parseInt(rawValue == null ? "" : rawValue.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    key.configKey() + " 须为整数，合法范围 " + key.rangeHint());
        }
    }
}
