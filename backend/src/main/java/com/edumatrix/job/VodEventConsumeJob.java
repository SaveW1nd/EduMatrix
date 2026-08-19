package com.edumatrix.job;

import org.springframework.stereotype.Component;

import com.edumatrix.vod.media.service.VodEventConsumeService;
import com.xxl.job.core.handler.annotation.XxlJob;

/**
 * 转码事件消费（模块 09，03-03 §7.2）。<b>10 秒一轮，单次拉取上限 16 条。</b>
 *
 * <h2>两条触发路径都要登记，只登记一边 = 任务在切换那一刻静默消失</h2>
 * <table border="1">
 *   <caption>本 Job 的两个登记点</caption>
 *   <tr><th>{@code xxl.job.enabled}</th><th>谁触发</th><th>登记在哪</th></tr>
 *   <tr><td>{@code false} / 未配（<b>现状</b>，F-41）</td><td>Spring 调度</td>
 *       <td>{@link ScheduledJobTrigger#FIXED_DELAY_VOD_EVENT_CONSUME}</td></tr>
 *   <tr><td>{@code true}（将来）</td><td>XXL-Job 调度中心</td>
 *       <td>本类 {@link #execute()} 上的 {@code @XxlJob("vodEventConsume")}
 *           + <b>调度中心里手工登记的 cron</b></td></tr>
 * </table>
 *
 * <p><b>⚠ 两边的语义不同，切换时不是「照抄」而是「换语义」</b>：
 * 过渡期用的是 {@code fixedDelay}（<b>上一轮跑完之后</b>再等 10s，天然背压，永不重叠）；
 * 调度中心登记的是 cron {@code 0/10 * * * * ?}（<b>固定节拍</b>，上一轮没跑完时下一轮照样到点）。
 * 两者在队列慢或云端卡顿时行为差别很大。已登记 <b>F-68</b>。
 *
 * <p>{@code XxlJobHandlerRegistryTest#handlerNamesArePinned} 把 handler 名集合钉死 ——
 * 加这个 Job 会让它红，那正是「去调度中心登记」的提醒（漏登记 = 任务永远不跑且无人报告）。
 *
 * <h2>Job 类只做三件事：取上下文、分片、调领域 Service（05-工程结构.md §H 的边界）</h2>
 * <p>不写 SQL、不写业务判断、不注入 Mapper。租户上下文由
 * {@code VodEventConsumeService} 逐条从 {@code vod_file_id} 反查后用
 * {@code TenantHelper.runWithTenant} 包住（契约 §2.8 规则 1）—— 那是<b>逐条</b>的事，
 * 不是整轮的事，所以不在本类做。
 */
@Component
public class VodEventConsumeJob {

    private final VodEventConsumeService consumeService;

    public VodEventConsumeJob(VodEventConsumeService consumeService) {
        this.consumeService = consumeService;
    }

    /** XXL-Job 调度中心那条路径的入口（{@code xxl.job.enabled=true} 时才有执行器）。 */
    @XxlJob("vodEventConsume")
    public void execute() {
        run();
    }

    /**
     * 消费一轮。供测试直接调用（不经调度器），也供 {@link ScheduledJobTrigger} 委派。
     *
     * @return 本轮成功处理的消息条数
     */
    public int run() {
        return consumeService.consumeOnce();
    }
}
