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
