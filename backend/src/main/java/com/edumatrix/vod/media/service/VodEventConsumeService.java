package com.edumatrix.vod.media.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumatrix.common.course.CourseCounterRefresher;
import com.edumatrix.common.media.VodEvent;
import com.edumatrix.common.media.VodEventSource;
import com.edumatrix.common.media.VodEventStream;
import com.edumatrix.common.media.VodNotReadyException;
import com.edumatrix.common.metrics.MetricsRegistry;
import com.edumatrix.common.operlog.OperLogWriter;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.vod.media.entity.VodVideo;
import com.edumatrix.vod.media.entity.VodVideoLookup;
import com.edumatrix.vod.media.mapper.VodEventLookupMapper;
import com.edumatrix.vod.media.mapper.VodVideoMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 转码事件消费（03-03 §7.2、契约 §2.8）。
 *
 * <h2>顺序是硬的：先落库成功，再删消息</h2>
 * <p>契约 §2.8：顺序反了就是丢事件 —— 删了消息而落库失败，这条转码完成事件永久消失，
 * 媒资卡在 {@code status=1}，而日志里一切正常。
 *
 * <h2>幂等靠状态机 CAS，不靠第二份存储（F-52）</h2>
 * <p>三段键 {@code vod_file_id + EventType + Status} 一段不少，只是承载它的是
 * {@code vod_video.status} 本身：{@code vod_file_id} 定位行，{@code EventType} 决定目标状态，
 * {@code Status} 决定走 →2 还是 →3。<b>跃迁已经发生过 = CAS 影响 0 行</b>。
 *
 * <table border="1">
 *   <caption>前置状态集</caption>
 *   <tr><th>事件</th><th>前置集</th><th>目标</th></tr>
 *   <tr><td>{@code FileUploadComplete}</td><td>{@code {0}}</td><td>1</td></tr>
 *   <tr><td>{@code TranscodeComplete(success)}</td><td>{@code {0,1,3}}</td><td>2</td></tr>
 *   <tr><td>{@code TranscodeComplete(fail)}</td><td>{@code {0,1}}</td><td>3</td></tr>
 * </table>
 * <p>成功分支含 {@code 0} 是因为 SMQ <b>不保证顺序</b>（转码成功事件先于上传完成事件到达时，
 * 若不含 0 会两条都跳过、媒资永久停在 0）；含 {@code 3} 是为了兜住
 * 「{@code retranscode} 云调实际成功但响应丢失、事务回滚到 3」这种失序（F-60）。
 *
 * <h2>⚠ CAS 落 0 行有两种含义，<b>必须分开</b></h2>
 * <ul>
 *   <li><b>A 真幂等</b>：行已在目标状态 → 重复投递，正常。INFO + 删消息；</li>
 *   <li><b>B 异常</b>：行处在前置集<b>之外</b>的状态 → 状态机被别的路径改过 / 事件乱序。
 *       <b>WARN + 指标，必须有人看得见</b> —— B 恰恰是「转码成功了但状态没推进」唯一的信号，
 *       正是契约 §2.8 描述 HTTP 回调陷阱时点名的那个形态。</li>
 * </ul>
 * <p>两种都当成「已处理、删消息」的话，B 就永远看不见了。
 */
@Service
public class VodEventConsumeService {

    private static final Logger log = LoggerFactory.getLogger(VodEventConsumeService.class);

    /**
     * <b>整轮</b>预算 —— 不是每条。剩余不够就停手，未处理的消息留在队列下轮再来。
     *
     * <h2>它与不可见时长是两个闸，量级差一个数量级</h2>
     * <table border="1">
     *   <caption>8s 是自设软闸，60s 才是真悬崖</caption>
     *   <tr><th>闸</th><th>值</th><th>从什么时候起算</th><th>撞上了会怎样</th></tr>
     *   <tr><td>本预算（软）</td><td>8s</td><td>本轮开始</td>
     *       <td>停手、剩余消息<b>不删</b>留到下一轮 —— <b>安全侧</b>，只是慢</td></tr>
     *   <tr><td>不可见时长（硬）</td><td><b>60s</b>（需方按契约 §1 上线前置第 2 条设，已核实队列配置）</td>
     *       <td><b>{@code receive} 那一刻，对整批 16 条同时起算</b></td>
     *       <td>未删的消息重新可见、被下一轮<b>重复消费</b>。有 CAS 幂等兜底不至于写错数据，
     *           但会白跑一次 {@code GetPlayInfo}</td></tr>
     * </table>
     * <p><b>「对整批同时起算」是这里最容易读错的一点</b>：不是每条各有 60s，
     * 是整批从取到手那一刻共用 60s。所以软闸留了 7 倍余量。
     */
    private static final long ROUND_BUDGET_MILLIS = 8000L;

    /** 孤儿成因标签 —— 用 {@code {reason}} 而不是新造指标名（契约 §7.1 的 11 个名字是穷举）。 */
    static final String REASON_PARSE_FAILED = "parse_failed";
    static final String REASON_VIDEO_NOT_FOUND = "video_not_found";
    /** CAS 落 0 行且当前状态在前置集之外 —— 见类注释 B。 */
    static final String REASON_UNEXPECTED_STATUS = "unexpected_status";

    private final VodEventSource eventSource;
    private final VodEventLookupMapper lookupMapper;
    private final VodVideoMapper videoMapper;
    private final VodPlayInfoResolver playInfoResolver;
    private final CourseCounterRefresher counterRefresher;
    private final OperLogWriter operLogWriter;
    private final MeterRegistry meterRegistry;
    private final java.util.concurrent.Executor refreshExecutor;

    public VodEventConsumeService(VodEventSource eventSource,
                                  VodEventLookupMapper lookupMapper,
                                  VodVideoMapper videoMapper,
                                  VodPlayInfoResolver playInfoResolver,
                                  CourseCounterRefresher counterRefresher,
                                  OperLogWriter operLogWriter,
                                  MeterRegistry meterRegistry,
                                  @org.springframework.beans.factory.annotation.Qualifier(
                                          com.edumatrix.common.config.SchedulerConfig.VOD_REFRESH_EXECUTOR)
                                  java.util.concurrent.Executor refreshExecutor) {
        this.eventSource = eventSource;
        this.lookupMapper = lookupMapper;
        this.videoMapper = videoMapper;
        this.playInfoResolver = playInfoResolver;
        this.counterRefresher = counterRefresher;
        this.operLogWriter = operLogWriter;
        this.meterRegistry = meterRegistry;
        this.refreshExecutor = refreshExecutor;
        registerGauges();
    }

    /** 距上次成功消费一条消息的毫秒时刻。启动时置为「现在」——否则重启后立刻报 lag=∞。 */
    private volatile long lastConsumeSuccessEpoch = System.currentTimeMillis();

    /**
     * 两个 Gauge（契约 §7.1）。
     *
     * <h2>⚠ 未配置队列时【不注册】lag 这条</h2>
     * <p>注册了它会在每台未配 VOD 的环境上一路涨过 600s 告警线、持续假警报，
     * 结局是运维把这条告警关掉 —— 而它正是唯一能发现「配错了静默丢弃」的那一条。
     *
     * <h2>⚠ lag 必须在<b>采集时现算</b>，不能由消费线程周期性 set</h2>
     * <p>后者在消费线程卡死时会永远停在最后一个值 —— <b>指标本身跟着一起卡住</b>，
     * 而这条指标恰恰是唯一能发现卡死的东西。所以用 {@code Gauge} 的取值函数。
     */
    private void registerGauges() {
        if (!eventSource.enabled()) {
            return;
        }
        io.micrometer.core.instrument.Gauge
                .builder(MetricsRegistry.VOD_EVENT_LAST_CONSUME_LAG_SECONDS, this,
                        self -> (System.currentTimeMillis() - self.lastConsumeSuccessEpoch) / 1000.0)
                .register(meterRegistry);
        // 队列积压：取不到时 VodEventSource 约定返回 -1，【不上报假的 0】——
        // 恒为 0 正是「配错了静默丢弃」的表现，不能由我们自己制造出来
        io.micrometer.core.instrument.Gauge
                .builder(MetricsRegistry.VOD_EVENT_QUEUE_DEPTH, eventSource, VodEventSource::queueDepth)
                .register(meterRegistry);
    }

    /** 消费一轮。 @return 本轮成功处理的消息条数 */
    public int consumeOnce() {
        if (!eventSource.enabled()) {
            // 未配置队列：空转。【不逐轮打日志】—— 10s 一轮，打了就把日志淹掉
            return 0;
        }
        long deadline = System.currentTimeMillis() + ROUND_BUDGET_MILLIS;
        List<VodEvent> events = eventSource.receive(VodEventSource.MAX_BATCH);
        int handled = 0;
        for (VodEvent event : events) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("本轮预算 {}ms 用尽，剩余 {} 条留到下一轮（消息未删，会重新可见）",
                        ROUND_BUDGET_MILLIS, events.size() - handled);
                break;
            }
            if (handleOne(event)) {
                handled++;
                lastConsumeSuccessEpoch = System.currentTimeMillis();
            }
        }
        return handled;
    }

    /** @return 是否已处置完毕（含「跳过并删消息」）；false 表示消息留在队列 */
    private boolean handleOne(VodEvent event) {
        if (!event.parsed()) {
            // 报文形状与解析器对不上是本模块【最可能的生产事故】——必须响得出来，绝不静默 continue
            orphan(REASON_PARSE_FAILED, event, "报文解析失败");
            return true;
        }
        if (!event.isHandledType()) {
            // 含 StreamTranscodeComplete：「可订阅用于观测，但不得用于状态跃迁」（§7.2）。
            // 【要删】——不删会无限重投，把 vod_event_queue_depth 顶成常亮告警
            log.warn("忽略不处理的事件类型并删除消息：{}", event.describe());
            eventSource.delete(event.receiptHandle());
            return true;
        }

        VodVideoLookup row = lookupMapper.findByProviderAndFileId(
                VodVideo.PROVIDER_ALIYUN, event.vodFileId());
        if (row == null) {
            orphan(REASON_VIDEO_NOT_FOUND, event, "按 (provider=2, vod_file_id) 反查不到媒资行");
            return true;
        }
        if (row.deleted()) {
            // 转码中被人工删除 —— 【不计孤儿】，否则每删一个转码中的视频就是一次假警报
            log.info("媒资已被删除，丢弃其后续事件（不计孤儿）：{} videoId={}", event.describe(), row.id());
            eventSource.delete(event.receiptHandle());
            return true;
        }

        return TenantHelper.runWithTenant(row.tenantId(), () -> apply(event, row));
    }

    private boolean apply(VodEvent event, VodVideoLookup row) {
        int[] expected = expectedStatuses(event);
        int target = targetStatus(event);
        int current = row.status() == null ? -1 : row.status();

        // 【幂等前置检查】用的是反查已经读到的 status，不额外查库、【不调 GetPlayInfo】。
        // 「第二次不产生第二次 GetPlayInfo」这条验收就靠它，且不依赖任何外部存储
        if (!contains(expected, current)) {
            return skipOrReport(event, row, current, expected, target);
        }

        try {
            return switch (target) {
                case VodVideo.STATUS_TRANSCODING -> advance(event, row, expected,
                        new LambdaUpdateWrapper<VodVideo>().set(VodVideo::getStatus, target), 0);
                case VodVideo.STATUS_NORMAL -> applySuccess(event, row, expected);
                default -> applyFailure(event, row, expected);
            };
        } catch (VodNotReadyException e) {
            // 云端未就绪：【不删消息、不改状态】，下一轮重来。
            // 与「返回成功但挑不到流」是两条路——混在一起会把一条好视频永久标成转码失败
            log.info("点播侧尚未就绪，本条留到下一轮：{}｜{}", event.describe(), e.getMessage());
            return false;
        }
    }

    /** {@code TranscodeComplete(success)}：反调 {@code GetPlayInfo} 挑流，挑不到一律置 3。 */
    private boolean applySuccess(VodEvent event, VodVideoLookup row, int[] expected) {
        VodPlayInfoResolver.Picked picked = playInfoResolver.pick(event);
        if (!picked.usable()) {
            // 契约 §1 部署约定第 3 条：挑不到必须置 3 并告警，【绝不可置 2】——
            // hls_url 为 NULL 而 status=2，课时能上架、学生点进去什么都播不了，且不报错
            log.error("挑流失败，置转码失败（绝不置 2）：{}｜{}", event.describe(), picked.reason());
            return advance(event, row, expected, new LambdaUpdateWrapper<VodVideo>()
                    .set(VodVideo::getStatus, VodVideo.STATUS_FAILED)
                    .set(VodVideo::getRemark, truncate(picked.reason())), 0);
        }
        LambdaUpdateWrapper<VodVideo> update = new LambdaUpdateWrapper<VodVideo>()
                .set(VodVideo::getStatus, VodVideo.STATUS_NORMAL)
                .set(VodVideo::getHlsUrl, picked.playUrl())
                .set(VodVideo::getDuration, picked.durationSeconds())
                .set(VodVideo::getRemark, null);
        if (picked.sizeBytes() != null) {
            update.set(VodVideo::getSizeBytes, picked.sizeBytes());
        }
        // 【cover_url 刻意不写】实测 GetPlayInfo 的 VideoBase.CoverURL 是
        //   http://outin-…/snapshots/….jpg?Expires=1787169594&Signature=…
        // 两个毛病：① http://（全站 HTTPS 下混合内容拦截）；② 带 Expires 时效签名，
        // 存进去迟早过期。注意【同一个响应里 PlayURL 是 https 而 CoverURL 是 http】，不能想当然。
        // 按模块 05 的 D-2「文件地址下发口径」先例：库里存可再生的标识、出参时现签，
        // 而不是把一条带签名的临时地址落库。本轮不写它（列保持 NULL），口径待需方定，
        // 已登记 F 清单。PRD F2-3 规则 1 提到 cover_url 由本事件回填，与 §7.2 映射表不一致，一并登记
        return advance(event, row, expected, update, picked.durationSeconds());
    }

    /** {@code TranscodeComplete(fail)}：失败原因写 {@code remark}。 */
    private boolean applyFailure(VodEvent event, VodVideoLookup row, int[] expected) {
        String reason = "转码失败 " + nullToEmpty(event.errorCode()) + " " + nullToEmpty(event.errorMessage());
        return advance(event, row, expected, new LambdaUpdateWrapper<VodVideo>()
                .set(VodVideo::getStatus, VodVideo.STATUS_FAILED)
                .set(VodVideo::getRemark, truncate(reason.trim())), 0);
    }

    /**
     * CAS 落库 → 成功才删消息 → 时长变了才发异步刷新。
     *
     * <p>{@code newDuration} 为 0 表示本次不涉及时长（上传完成 / 失败分支）。
     */
    private boolean advance(VodEvent event, VodVideoLookup row, int[] expected,
                            LambdaUpdateWrapper<VodVideo> update, int newDuration) {
        update.eq(VodVideo::getId, row.id()).in(VodVideo::getStatus, box(expected));
        int changed = videoMapper.update(null, update);
        if (changed == 0) {
            // 并发下已被处理 —— 到这里说明前置检查通过后状态又变了，属 A 档
            log.info("CAS 命中 0 行（并发下已被处理），删消息：{}", event.describe());
            eventSource.delete(event.receiptHandle());
            return true;
        }
        eventSource.delete(event.receiptHandle());
        if (newDuration > 0 && !Integer.valueOf(newDuration).equals(row.duration())) {
            refreshCountersAsync(row, newDuration);
        }
        return true;
    }

    /**
     * 前置检查未通过：区分 A（真幂等）与 B（前置集之外）。
     *
     * <p><b>两种都当成「已处理」的话，B 就永远看不见了</b> —— 而 B 恰恰是
     * 「转码成功了但状态没推进」唯一的信号。
     */
    private boolean skipOrReport(VodEvent event, VodVideoLookup row,
                                 int current, int[] expected, int target) {
        if (current == target) {
            log.info("行已在目标状态 {}，判定为重复投递（真幂等），删消息：{}", target, event.describe());
            eventSource.delete(event.receiptHandle());
            return true;
        }
        // B：状态机被别的路径改过 / 事件乱序 —— 必须有人看得见
        log.warn("状态在前置集之外：当前 {}，本事件要求 {} → {}。"
                        + "这可能是「转码成功了但状态没推进」——契约 §2.8 点名的那个形态。"
                        + "已删消息（留着会无限重投），请人工核对：{} videoId={}",
                current, java.util.Arrays.toString(expected), target, event.describe(), row.id());
        meterRegistry.counter(MetricsRegistry.VOD_CALLBACK_ORPHAN_TOTAL,
                MetricsRegistry.TAG_REASON, REASON_UNEXPECTED_STATUS).increment();
        operLogWriter.write(null, "媒资", "转码事件状态异常",
                "vodEventConsume", event.describe() + " current=" + current,
                null, OperLogWriter.STATUS_FAIL, "状态在前置集之外", 0, row.tenantId());
        eventSource.delete(event.receiptHandle());
        return true;
    }

    /**
     * 冗余刷新 —— <b>异步</b>，且<b>必须走 {@code CourseCounterRefresher}</b>
     * （04 §B 模块 09 接管事项第 2 条）：{@code crs_lesson.duration} 与
     * {@code crs_course.total_duration} 的写入点全库只有一处；而 {@code vod} 领域
     * 直写 {@code crs_lesson} 会直接命中约定检查③。
     *
     * <h2>⚠ 异步线程<b>不继承</b>租户上下文 —— 那个 {@code runWithTenant} 不是保险，是必需</h2>
     * <p>{@code TenantHelper} 的显式租户是 {@code ThreadLocal}；提交到别的线程之后它是空的，
     * 而租户插件拼条件走的是 {@code requireTenantId()}，取不到<b>直接抛</b>。
     * 漏了这一句的表现是：媒资状态推进成功、接口一切正常，而
     * <b>冗余刷新静默不发生</b>（异常被下面那个 catch 吞成一条 ERROR，
     * 而没有人在看那条 ERROR）—— 课时时长永远停在旧值。
     * {@code VodEventConsumeIT#asyncRefreshActuallyUpdatesRedundantColumns} 用
     * 「断言冗余值<b>真的变了</b>」把它钉住，而不是断言「提交了任务」。
     *
     * <h2>删消息的时刻：<b>在提交异步任务之前</b>，不等它完成</h2>
     * <p>契约 §2.8 的「先落库成功再删消息」保护的是<b>状态推进</b>那次写入 ——
     * 那一次已经在 {@code advance} 里 CAS 成功了。冗余刷新是它<b>之后</b>的第二件事，
     * 不在同一条保证里。
     *
     * <p><b>那么异步任务失败会怎样，为什么可以接受</b>：冗余值与真实值不一致，
     * 而<b>接口返回 200、字段齐全、数字是错的</b>。这确实是本项目的 1 号失败模式，
     * 但它<b>自愈</b>：{@code recountRedundant} 是<b>全量</b> {@code COUNT} / {@code SUM}
     * 而不是增量 ±1，所以<b>漏一次会在下一次任何课时变更时被重算回来</b> ——
     * 这正是 03-03 §7.2 第 862 行那段订正给出的同一条论证。
     *
     * <p><b>反过来「等异步完成再删消息」不成立</b>：那等于把异步又变回同步
     * （消费线程要等），§7.2 第 1585 行要避免的扇出耦合原样回来，白改一场。
     *
     * <p><b>代价如实写下</b>：自愈的触发条件是「下一次课时变更」，而一个媒资可能
     * 很久没有课时变更。也就是说这段时间里 {@code crs_course.total_duration} 会偏小。
     * 影响面是管理端列表上的一个数字，不影响播放与进度判定（那两条读 {@code crs_lesson.duration}
     * 与 {@code vod_video.duration}）。<b>不为它加重试</b>：重试队列本身又是一个要维护的东西，
     * 而收益只是让一个展示数字早几天正确。
     */
    private void refreshCountersAsync(VodVideoLookup row, int newDuration) {
        Long tenantId = row.tenantId();
        refreshExecutor.execute(() -> {
            try {
                // ⚠ 这一句删掉 = 冗余刷新静默不发生。见方法注释与那条 IT 的变异验证
                TenantHelper.runWithTenant(tenantId,
                        () -> counterRefresher.refreshByVideo(row.id(), newDuration));
            } catch (RuntimeException e) {
                log.error("冗余刷新失败 videoId={} duration={} tenantId={}（媒资状态已推进，不回滚；"
                        + "全量重算会在下一次课时变更时自愈）", row.id(), newDuration, tenantId, e);
            }
        });
    }

    /** 孤儿：三件事一件都不能少（契约 §2.8 规则 3「必须有人看见」）+ 删消息。 */
    private void orphan(String reason, VodEvent event, String what) {
        meterRegistry.counter(MetricsRegistry.VOD_CALLBACK_ORPHAN_TOTAL,
                MetricsRegistry.TAG_REASON, reason).increment();
        // tenant_id = 0：孤儿事件【不属于任何租户】，而 02-数据库设计 §1.1 已把 sys_oper_log
        // 列为承载 tenant_id=0 行的表。这些行只有超管读得到（普通租户严格过滤），
        // 而孤儿事件本来也只有平台运维能处置
        operLogWriter.write(null, "媒资", "转码事件孤儿", "vodEventConsume",
                what + "｜" + event.describe() + "｜raw=" + head(event.rawBody()),
                null, OperLogWriter.STATUS_FAIL, reason, 0, OperLogWriter.PLATFORM_TENANT_ID);
        log.error("孤儿事件（reason={}）：{}｜{}｜raw={}", reason, what, event.describe(),
                head(event.rawBody()));
        eventSource.delete(event.receiptHandle());
    }

    private static int[] expectedStatuses(VodEvent event) {
        if (event.isUploadComplete()) {
            return new int[]{VodVideo.STATUS_UPLOADING};
        }
        if (event.isTranscodeSuccess()) {
            return new int[]{VodVideo.STATUS_UPLOADING, VodVideo.STATUS_TRANSCODING,
                    VodVideo.STATUS_FAILED};
        }
        return new int[]{VodVideo.STATUS_UPLOADING, VodVideo.STATUS_TRANSCODING};
    }

    private static int targetStatus(VodEvent event) {
        if (event.isUploadComplete()) {
            return VodVideo.STATUS_TRANSCODING;
        }
        return event.isTranscodeSuccess() ? VodVideo.STATUS_NORMAL : VodVideo.STATUS_FAILED;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> box(int[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 497) + "...";
    }

    /** 原始报文只截前 500 字节进日志/留痕 —— 不整条打（可能很长，且含 OSS 直链）。 */
    private static String head(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= 500 ? raw : raw.substring(0, 500) + "...";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
