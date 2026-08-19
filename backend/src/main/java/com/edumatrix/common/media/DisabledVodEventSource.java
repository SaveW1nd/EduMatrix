package com.edumatrix.common.media;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 队列<b>未配置</b>时的空实现 —— 与 {@code integration/aliyun/SmqClient} <b>互为反面</b>，
 * 装配条件写在同一个属性上（{@code edumatrix.vod.smq.queue-name} / {@code .endpoint}），
 * 与 {@code common/file/LocalObjectStorage} ↔ {@code OssClient} 同型。
 *
 * <h2>为什么是「默认关掉 + 一条 WARN」，而不是启动失败</h2>
 * <p>{@code OssClient} 的两条启动自检（桶必须私有、区域必须中国大陆）<b>让应用启动失败</b>，
 * 那是对的 —— 它们是<b>合规基线</b>，配错的后果是全部课程封面、讲义、导出报表当场对全互联网敞开。
 *
 * <p>本项不同：VOD/SMQ 缺配置的后果是<b>功能不可用</b>，模块 01~08 的全部功能与本模块的
 * <b>读</b>接口都不依赖它。而生产机 {@code /etc/edumatrix/db.env} 里
 * <b>目前一个 VOD/SMQ 的键都没有</b>（需方仍在配模板组）。让它启动失败，
 * 等于「一部署新版本这台已经在跑的机器就起不来」—— 把一次功能缺失升级成一次全站停机。
 *
 * <p>但<b>「静默不工作」同样不接受</b>，所以：① 启动打一条<b>写明后果</b>的 WARN；
 * ② 消费任务见到本实现直接空转、<b>不再逐轮刷日志</b>（刷了就没人看了）；
 * ③ {@code vod_event_last_consume_lag_seconds} 这个 Gauge <b>不注册</b> ——
 * 注册了它会在每台未配 VOD 的环境上一路涨过 600s 告警线，持续假警报，
 * 最后运维把这条告警关掉，而它正是唯一能发现「配错了静默丢弃」的那一条（契约 §7.1 逐字）。
 */
@Component
@ConditionalOnExpression("'${edumatrix.vod.smq.queue-name:}'.trim() == ''"
        + " or '${edumatrix.vod.smq.endpoint:}'.trim() == ''")
public class DisabledVodEventSource implements VodEventSource {

    private static final Logger log = LoggerFactory.getLogger(DisabledVodEventSource.class);

    public DisabledVodEventSource() {
        // 与 OssClient 那行「对象存储 = …」同一个用途：给一条可 grep 的、
        // 能证明「它到底接到哪儿了」的事实。这里的事实是「哪儿都没接」
        log.warn("转码事件队列 = 未配置（ALIYUN_SMQ_ENDPOINT / ALIYUN_SMQ_QUEUE_NAME 为空）—— "
                + "转码事件不会被消费，已上传的媒资将永久停在 status=0 上传中 / 1 转码中，"
                + "课时无法选用它们，而应用本身不会有任何异常");
    }

    @Override
    public List<VodEvent> receive(int max) {
        return Collections.emptyList();
    }

    @Override
    public void delete(String receiptHandle) {
        // 没有消息可删
    }

    @Override
    public long queueDepth() {
        // -1 = 取不到。绝不返回 0 —— 恒为 0 正是「配错了静默丢弃」的表现（契约 §7.1）
        return -1L;
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
