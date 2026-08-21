package com.edumatrix.common.tenantconfig;

/**
 * 租户配置键白名单（契约 §5 末，<b>穷举，只有两个</b>）。
 *
 * <h2>为什么是枚举而不是 {@code String}</h2>
 * <p>契约 §5 对这张表用的词是<b>穷举</b>——枚举是"穷举"这个语义的<b>类型级表达</b>，
 * {@code String} 不是。消费方（模块 12 取水印开关、模块 13 判完播）拿到的是
 * {@link TenantConfigHelper#getInt(TenantConfigKey)}，于是"传一个白名单外的键"
 * 在它们的调用点上<b>根本写不出来</b>；用 {@code String} 的话那就成了一次运行期才发现的
 * 拼写错误，而它的表现是<b>静默回落到某个默认值</b>——不报错。
 *
 * <h2>为什么键名、默认值、合法范围三者放在同一处</h2>
 * <p>写侧（03-01 §6.2 的校验：不在白名单 → {@code 10016}，超范围 → 400）与
 * 读侧（{@link TenantConfigHelper} 读不到时回落默认值）<b>用的必须是同一份定义</b>。
 * 分成两份副本迟早分叉，而分叉<b>不报错</b>：写侧按 60~100 拒了非法值，
 * 读侧却回落到另一个默认值，两边各自"按自己的规则执行"。
 *
 * <h2>本枚举在 {@code common/} 而实现在 {@code system/tenant/}</h2>
 * <p>SPI 的完整理由见 {@link TenantConfigHelper}。放 {@code common/tenantconfig/}
 * 而不是 {@code common/tenant/}：后者装的是<b>租户上下文与插件</b>
 * （{@code TenantHelper} / {@code TenantLineHandler} / {@code CurrentContextProvider}），
 * 与"这个租户的完播阈值是多少"是两件事；一字之差并排站着会误导
 * ——{@code common/account/} 那次的教训（"口令哈希不是 session 的事"）同理。
 *
 * <h2>不得加第三个键</h2>
 * <p>契约 §5 末与 04-实施计划.md 模块 04 禁止事项：<b>新增配置键须先在契约登记，
 * 再扩充白名单</b>，不得在实现里私自扩键。尤其<b>云厂商选择 / AK/SK / 转码模板组 ID /
 * 队列名 / KMS 配置属平台部署级参数</b>（契约 §5「平台部署级参数（定案）」段、§E 的 F-5），
 * 一律走环境变量，不入 {@code sys_tenant_config}。
 */
public enum TenantConfigKey {

    /**
     * 完播判定阈值百分比：{@code watched_duration >= duration × 阈值%} 即置为已完成
     * （契约 §5「完播判定规则」）。判定时机为 XXL-Job 落盘时（唯一判定时机），
     * 修改只影响此后的判定，<b>存量 {@code watch_status} 不回溯重算</b>（03-01 §6.2）。
     *
     * <p><b>F-113（2026-08-21 需方定案）：第一版不消费本键</b> —— 完播判定整体延后，
     * {@code watch_status} 只在 0/1 之间流转、不写 2（PRD F2-9）。本键、默认值与取值区间
     * 原样保留，将来启用即可直接用。
     *
     * <p><b>并且：本项目不做完播回算。</b> 上面那句「修改只影响此后的判定」说的是<b>改阈值</b>，
     * 而 F-113 说的是<b>开功能</b> —— 两者不是一回事，但结论相同：<b>都只对此后的落盘生效</b>。
     * 写明是因为不写会被读成两句话打架。需方原话：「我们不会重算的，如果后续要，
     * 也只会要每个学生在那堂课上花了多少时间」—— 那个数是 {@code vod_watch_progress.watched_duration}，
     * <b>第一版从模块 13 上线那天起就在存</b>，不需要额外工作。
     */
    COMPLETE_RATE_THRESHOLD("complete_rate_threshold", 90, 60, 100,
            "完播判定阈值（百分比），60~100 的整数"),

    /**
     * 播放器跑马灯水印中手机号是否中间四位脱敏：{@code 1} 脱敏（{@code 138****5678}，<b>默认</b>）
     * / {@code 0} 不脱敏。
     *
     * <h2>已定案的语义边界（契约 §5 表格 + §7.2 第 2 条，F-2 定案）</h2>
     * <ul>
     *   <li><b>本键只作用于管理端预览</b>（{@code viewer_type ∈ {1,2}}）：被水印的是机构
     *       自己的员工，威慑对象也是他自己；
     *   <li><b>学生端播放（{@code viewer_type = 3}）一律脱敏、不读本键、不可关闭</b>——
     *       受《个人信息保护法》第 31 条约束的是未成年人信息，"允许租户选择不脱敏"本身不成立。
     * </ul>
     *
     * <p><b>默认值是 1（脱敏），不是 0。</b>契约 §7.2 记着这次订正的理由逐字：
     * 此前默认 0 且对学生端生效——<b>一个租户什么都不配就已经违反本条</b>，
     * 而对象是 K12 未成年人的手机号。<b>这不是措辞不一致，是默认即违规。</b>
     *
     * <p>03-01 §6 导语表格的「默认值」列与 §6.1 响应示例目前仍写 0（与同格描述文字
     * 「{@code 1} 脱敏（<b>默认</b>）」自相矛盾），按权威顺序（契约 &gt; 分册）取 1；
     * 分册待订正，已登记为 04-实施计划.md §E 的 <b>F-24</b>。
     */
    WATERMARK_PHONE_MASK("watermark_phone_mask", 1, 0, 1,
            "播放器水印手机号是否中间四位脱敏：1 脱敏（默认）/ 0 不脱敏；仅作用于管理端预览，学生端一律脱敏");

    private final String configKey;
    private final int defaultValue;
    private final int minValue;
    private final int maxValue;
    private final String description;

    TenantConfigKey(String configKey, int defaultValue, int minValue, int maxValue, String description) {
        this.configKey = configKey;
        this.defaultValue = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.description = description;
    }

    /** {@code sys_tenant_config.config_key} 的字面值。 */
    public String configKey() {
        return configKey;
    }

    /** 平台默认值：本租户未写入过该键时的回落值（02-数据库设计 §4.1.10）。 */
    public int defaultValue() {
        return defaultValue;
    }

    /** 平台默认值的字符串形态——{@code config_value} 统一字符串存储，响应亦然。 */
    public String defaultValueAsString() {
        return String.valueOf(defaultValue);
    }

    /** 配置项说明（含值域），可直接用于 03-01 §6.1 的设置页展示。 */
    public String description() {
        return description;
    }

    /** 合法范围提示语，用于超范围时 400 的 {@code msg}（§6.2：「msg 提示该键的合法范围」）。 */
    public String rangeHint() {
        return minValue + "~" + maxValue;
    }

    public boolean isWithinRange(int value) {
        return value >= minValue && value <= maxValue;
    }

    /**
     * 按字面键名反查。<b>不在白名单内返回 {@code null}</b>，由调用方翻成 {@code 10016}
     * （03-01 §6.2 / 00-通用约定 §9.2）——本类不抛异常，因为"键不存在"在写侧是业务码、
     * 在读侧根本不可能发生（读侧的入参已经是枚举）。
     */
    public static TenantConfigKey of(String configKey) {
        if (configKey == null) {
            return null;
        }
        String trimmed = configKey.trim();
        for (TenantConfigKey key : values()) {
            if (key.configKey.equals(trimmed)) {
                return key;
            }
        }
        return null;
    }
}
