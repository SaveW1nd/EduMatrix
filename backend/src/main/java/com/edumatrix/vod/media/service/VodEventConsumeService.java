package com.edumatrix.vod.media.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.edumatrix.common.media.VodEventSource;

/**
 * 转码事件消费（03-03 §7.2、契约 §2.8）。
 *
 * <h2>⚠ 本提交只交付「调度隔离」那一半，消费逻辑在后续提交</h2>
 * <p>拆开的理由是<b>可回滚</b>：调度线程池的隔离与消费逻辑是两件独立的事，
 * 混在一个提交里出问题时只能整体退回。
 *
 * <p><b>本提交的行为是安全的，不是半成品</b>：本提交里 {@link VodEventSource} 的唯一实现是
 * {@code common/media/DisabledVodEventSource}（云 SDK 要到下一个提交才装配），
 * 也就是说<b>此刻队列里根本取不到任何消息</b>，空转即正确行为。
 * 万一装配出意外（真实现提前出现），这里<b>只记 ERROR、一条都不取、一条都不删</b> ——
 * 绝不出现「取了消息却没落库还把它删了」这种丢事件的形态（契约 §2.8：先落库成功再删消息）。
 */
@Service
public class VodEventConsumeService {

    private static final Logger log = LoggerFactory.getLogger(VodEventConsumeService.class);

    private final VodEventSource eventSource;

    public VodEventConsumeService(VodEventSource eventSource) {
        this.eventSource = eventSource;
    }

    /**
     * 消费一轮。
     *
     * @return 本轮成功处理的消息条数
     */
    public int consumeOnce() {
        if (!eventSource.enabled()) {
            // 未配置队列：空转。【不逐轮打日志】—— 10s 一轮，打了就把日志淹掉，
            // 最后没人看。启动时 DisabledVodEventSource 已经打过一条写明后果的 WARN
            return 0;
        }
        log.error("转码事件消费逻辑尚未交付，而 VodEventSource 已装配为真实现（{}）—— "
                        + "本轮不拉取、不删除任何消息，消息留在队列里等待消费逻辑上线。"
                        + "见 03-03 §7.2 与 04-实施计划.md §B 模块 09",
                eventSource.getClass().getName());
        return 0;
    }
}
