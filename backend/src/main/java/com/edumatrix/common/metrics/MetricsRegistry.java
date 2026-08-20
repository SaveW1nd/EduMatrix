package com.edumatrix.common.metrics;

/**
 * 监控指标名常量（契约 §7.1「必须落地的监控指标」）。
 *
 * <p><b>埋点一律引用本类的常量，不写字面量。</b>指标名写错的后果是告警规则匹配不上，
 * <b>而且不报错</b> —— 指标照常上报，只是没有任何一条规则在看它。
 *
 * <p><b>关于「十项」</b>：契约 §7.1 的表格是 10 行，但第 9 行
 * （{@code tree_move_depth} / {@code tree_move_subtree_size}）里是<b>两个</b>指标名，
 * 所以本类是 <b>11 个</b>常量。这不是多登记了一个。
 *
 * <p>各指标由哪个模块埋点见下表的注释；模块 01 只负责<b>登记名字</b>与
 * {@link #API_PERMISSION_DENIED_TOTAL} 这一项的实际打点（它落在全局异常处理器上，
 * 属于公共层）。
 */
public final class MetricsRegistry {

    private MetricsRegistry() {
    }

    // ======================================================================
    // 标签键
    // ======================================================================

    /** {@link #HEARTBEAT_REJECT_TOTAL} 的标签：被哪条防刷规则拒绝（03-03 §8.3.1 的规则编号）。 */
    public static final String TAG_RULE = "rule";

    /** {@link #API_PERMISSION_DENIED_TOTAL} 的标签：403 / 404 / 10107。 */
    public static final String TAG_CODE = "code";

    /**
     * {@link #VOD_CALLBACK_ORPHAN_TOTAL} 的标签：孤儿的<b>成因</b>（模块 09）。
     *
     * <p>三种成因性质完全不同，混成一个数字就没法处置：
     * <ul>
     *   <li>{@code parse_failed} —— <b>我们的代码错</b>：报文形状与解析器对不上。
     *       这是本模块最可能的生产事故（假实现全绿而生产上每条消息都解析失败）；</li>
     *   <li>{@code video_not_found} —— <b>数据问题</b>：按 {@code (provider, vod_file_id)}
     *       反查不到媒资行；</li>
     *   <li>{@code unexpected_status} —— <b>状态机被别的路径改过</b>：CAS 前置集之外。</li>
     * </ul>
     *
     * <p><b>用标签而不是新造指标名</b>：契约 §7.1 的 11 个名字是穷举、不得另造，
     * 而 {@link #HEARTBEAT_REJECT_TOTAL}{@code {rule}} 与
     * {@link #API_PERMISSION_DENIED_TOTAL}{@code {code}} 是既有的带标签先例。
     * 告警线「&gt; 0」对总量仍然成立。
     */
    public static final String TAG_REASON = "reason";

    /**
     * 标签名：租户。
     *
     * <p>巡检类指标必须带它 —— 不带的话多个租户的值互相覆盖，
     * 运维看到 {@code grant_dangling_count = 3} <b>不知道是哪个机构的 3</b>，
     * 而这个指标的处置动作（一键回收 / 补授上级）必须落到具体机构才做得了。
     */
    public static final String TAG_TENANT = "tenant";

    // ======================================================================
    // 契约 §7.1 十项（11 个名字）
    // ======================================================================

    /**
     * Gauge，告警线 &gt; 180s。模块 13。
     * <p>心跳落盘滞后即学习时长丢失，<b>且用户无感知</b>。
     */
    public static final String HEARTBEAT_FLUSH_LAG_SECONDS = "heartbeat_flush_lag_seconds";

    /**
     * Counter，标签 {@link #TAG_RULE}，告警线：单租户 5min 内 &gt; 1000。模块 13。
     * <p>按 8 条防刷规则分标签；突增说明有人在刷，或前端发版出错。
     */
    public static final String HEARTBEAT_REJECT_TOTAL = "heartbeat_reject_total";

    /**
     * Gauge，告警线 <b>&gt; 0</b>。模块 11。
     * <p>契约 §2.5 规则 6 的<b>真悬挂</b>授权，目标值恒为 0。
     * <p><b>{@code crossScopeCount}（跨管辖，节点移动导致、合法保留且已降级只读）
     * 必须单独打点，不进本指标也不进告警</b> —— 合并计数会让任何一次教师调岗或学员转交
     * 都使指标永久非 0，持续假警报，最终结果是运维关掉告警、真悬挂也没人看。
     */
    public static final String GRANT_DANGLING_COUNT = "grant_dangling_count";

    /**
     * Gauge，<b>不进告警</b>。模块 11。
     *
     * <p>契约 §2.5 规则 6 的<b>跨管辖</b>授权：节点移动（教师调岗 / 学员转交）的
     * <b>合法产物</b>，学生仍可正常使用、新上级看不到也无法再下发，<b>只作待办</b>。
     *
     * <p><b>契约 §7.1 只说了「{@code crossScopeCount} 单独打点」，没有给指标名</b>，
     * 本名由模块 11 定（已登记，需方可推翻）。取 {@code grant_} 前缀与
     * {@link #GRANT_DANGLING_COUNT} 对齐，读的人一眼能看出是一对。
     *
     * <p><b>它与 {@link #GRANT_DANGLING_COUNT} 从头到尾是两个变量、两个 Gauge、
     * 两个响应字段，中间没有任何一处相加</b>。合并计数会让任何一次教师调岗或学员转交
     * 都使指标永久非 0，持续假警报，最终结果是运维关掉告警、真悬挂也没人看。
     * 本项目在 F-20 已经为这条踩过一次。
     */
    public static final String GRANT_CROSS_SCOPE_COUNT = "grant_cross_scope_count";

    /**
     * Gauge，<b>不带任何标签</b>，值为巡检 {@code run()} 最近一次<b>跑完</b>的 epoch 秒。模块 11。
     *
     * <h2>它回答的是另一个问题：<b>巡检本身还活着吗</b></h2>
     * <p>{@link #GRANT_DANGLING_COUNT} 与 {@link #GRANT_CROSS_SCOPE_COUNT} 是
     * <b>per-tenant</b> 的，且只在<b>该租户被首次扫到之后</b>才注册。于是有两种情况
     * 它们一条序列都没有，而<b>那与「一切健康」在告警上长得一模一样</b>：
     * <ul>
     *   <li>调度器压根没触发（{@code @Scheduled} 被删、profile 门控误伤、切到调度中心却没登记）；
     *   <li>系统里<b>一个租户都还没有</b>（生产上线初期就是这样）。
     * </ul>
     *
     * <p>本指标<b>不带 tenant 标签、在构造器里注册</b>，因而<b>永远存在</b>；
     * 它与 per-tenant 那两个<b>不是二选一，各自能发现对方发现不了的事</b>：
     * <table border="1">
     *   <caption>三种故障，两种信号</caption>
     *   <tr><th>故障</th><th>job 级</th><th>per-tenant</th></tr>
     *   <tr><td>调度器没触发</td><td><b>发现得了</b></td><td>要先有租户</td></tr>
     *   <tr><td>0 租户</td><td><b>发现得了</b></td><td>发现不了</td></tr>
     *   <tr><td>单个租户连续失败</td><td>发现不了（job 照常完成）</td><td><b>发现得了</b></td></tr>
     * </table>
     *
     * <h2>⚠ 初值必须是 0，<b>绝不能照抄模块 09</b></h2>
     * <p>{@code VodEventConsumeService} 把它那个 lag 指标初始化为
     * {@code System.currentTimeMillis()}，注释写着「否则重启后立刻报 lag=∞」——
     * <b>那对一个 10 秒一轮的消费者是对的</b>：重启后几秒内就会有真实值，
     * 先给个当下时刻只是避免一次无意义的瞬时告警。
     *
     * <p><b>日任务照抄就错了</b>：初值取当下会让「任务从不触发」在每次重启后
     * <b>被掩盖整整一天</b>；而<b>当部署比一天更频繁时它被永久掩盖</b> ——
     * 每次发版都把计时器重置，告警<b>永远差最后一步</b>，而看起来一切正常。
     *
     * <p>初值 {@code 0} 的含义是<b>「从未跑过」</b>：{@code time() - 0} 是一个巨大的数，
     * 告警<b>立刻触发</b>。刚部署完看到它报警是<b>期望行为</b>，不是误报 ——
     * 那时巡检确实还没跑过。
     *
     * <h2>告警规则（两条，各管一件事）</h2>
     * <pre>
     * time() - grant_consistency_last_run_epoch_seconds &gt; 93600   # 26h：巡检没跑
     * grant_dangling_count &gt; 0                                    # 有真悬挂
     * </pre>
     * <p>26h = 24h 周期 + 2h 余量。<b>有了第一条之后，第二条才允许「缺席」</b> ——
     * 缺席由第一条负责喊，第二条只管有没有真悬挂。
     *
     * <h2>为什么不用那个 Redis 键代替</h2>
     * <p>{@code RedisKeys.grantHealthLastRun(tenantId)} 写在 {@code scanTenant()} <b>里面</b>、
     * 键还带租户后缀 —— <b>0 租户就一个键都不写</b>，与它要替换的 Gauge 是<b>同一个盲点</b>。
     * 那个键回答的是「<b>这个租户</b>上次被扫是什么时候」（也是 {@code detectedTime} 的来源），
     * 是对的问题，只是不在这个位置上。两个都留，各答各的。
     */
    public static final String GRANT_CONSISTENCY_LAST_RUN_EPOCH_SECONDS =
            "grant_consistency_last_run_epoch_seconds";

    /**
     * Histogram，告警线 P99 &gt; 2000。模块 11。
     * <p>{@code org_resource_grant} 单节点持有的授权行数。
     * <b>盯它而不是盯表总量</b> —— 点查的扫描行数只由单节点持有量决定，
     * 且该上界由人均持有量决定、不随机构人数变化。触发通常意味着有人把整个题库
     * 一次性授给了某个节点。
     */
    public static final String GRANT_ROWS_PER_NODE = "grant_rows_per_node";

    /**
     * Histogram，告警线 P99 &gt; 30min。模块 16。
     * <p>日结算跑不完则次日看板空白。
     */
    public static final String STAT_SETTLE_JOB_DURATION_SECONDS = "stat_settle_job_duration_seconds";

    /**
     * Counter，告警线 <b>&gt; 0</b>。模块 09。
     * <p>事件消费反查不到媒资行（契约 §2.8 规则 3），<b>每一次都是静默的数据丢失</b>。
     * <p><b>指标名保留 {@code callback} 字样</b>（虽然转码事件早已改为拉取消息队列）：
     * 改名会让既有告警规则与历史数据断档，代价大于命名精确性。契约 §7.1 已就此定案。
     */
    public static final String VOD_CALLBACK_ORPHAN_TOTAL = "vod_callback_orphan_total";

    /**
     * Gauge，告警线 &gt; 1000。模块 09。
     * <p>转码事件队列积压。消费任务挂了或处理变慢时先在这里体现。
     */
    public static final String VOD_EVENT_QUEUE_DEPTH = "vod_event_queue_depth";

    /**
     * Gauge，告警线 <b>&gt; 600s</b>。模块 09。
     * <p>距上次成功消费一条消息的时长。<b>这条防的是"配错了静默丢弃"</b> ——
     * 点播服务写队列失败（未授权 / Endpoint 非公网 / 队列名错）时重试 3 次即丢弃，
     * 队列侧永远空着，{@link #VOD_EVENT_QUEUE_DEPTH} 恒为 0、消费任务也一切正常，
     * <b>只有"太久没收到消息"能暴露它</b>。
     */
    public static final String VOD_EVENT_LAST_CONSUME_LAG_SECONDS = "vod_event_last_consume_lag_seconds";

    /**
     * Histogram。模块 06。与 {@link #TREE_MOVE_SUBTREE_SIZE} 同为契约 §7.1 表格第 9 行。
     */
    public static final String TREE_MOVE_DEPTH = "tree_move_depth";

    /**
     * Histogram，告警线：子树 &gt; 5000。模块 06。
     * <p>大子树移动会长时间持有 {@code ancestors} 重算的写锁。
     */
    public static final String TREE_MOVE_SUBTREE_SIZE = "tree_move_subtree_size";

    /**
     * Counter，标签 {@link #TAG_CODE}，告警线：单账号 5min 内 &gt; 100。<b>模块 01 埋点</b>。
     * <p>403 / 404 / 10107 突增 = 越权探测。打点位置在
     * {@code common/response/GlobalExceptionHandler} —— 三分法的三个出口都从那里过。
     */
    public static final String API_PERMISSION_DENIED_TOTAL = "api_permission_denied_total";
}
