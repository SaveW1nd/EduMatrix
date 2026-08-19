package com.edumatrix.integration.aliyun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.aliyun.mns.client.CloudAccount;
import com.aliyun.mns.client.CloudQueue;
import com.aliyun.mns.client.MNSClient;
import com.aliyun.mns.common.http.ClientConfiguration;
import com.aliyun.mns.model.Message;
import com.aliyun.mns.model.QueueMeta;
import com.edumatrix.common.media.VodEvent;
import com.edumatrix.common.media.VodEventPayloadParser;
import com.edumatrix.common.media.VodEventSource;

/**
 * 阿里云<b>轻量消息队列</b>（原 MNS）—— 转码事件的来源（03-03 §7.2）。
 *
 * <h2>拉模式，无入站端点</h2>
 * <p>契约 §2.8 定案：去掉免登录 HTTP 回调端点之后，<b>全部免登录接口里再没有一个能写库</b>。
 * 本类只往外拉，不开任何端口。鉴权靠 AK/SK，天然免疫伪造与重放。
 *
 * <h2>不做长轮询</h2>
 * <p>{@code batchPopMessage(n)} 不带 {@code waitSeconds} —— 带了会把调度线程占住到 30s，
 * 与 10s 的触发间隔和单轮预算都冲突。队列空时立刻返回空列表。
 *
 * <h2>用 {@code getMessageBodyAsRawString()}，把「要不要 Base64 解码」交给解析器</h2>
 * <p>消息体是否 Base64 <b>取决于队列的编码设置</b>（控制台同时展示两种），猜错一边的后果是
 * <b>每条消息都解析失败</b>。所以这里不猜：取原始串，由
 * {@link VodEventPayloadParser} 两种都试。那一段有单元测试钉住，且用的是真实报文。
 *
 * <h2>「配错了静默丢弃」这条只能靠指标发现</h2>
 * <p>点播服务写队列失败（未授权 / Endpoint 非公网 / 队列名不对）时重试 2 次、共 3 次即<b>丢弃</b>，
 * 三种成因<b>全是配置错误</b>。那时队列侧永远空着，{@code vod_event_queue_depth} 恒为 0、
 * 消费任务也一切正常 —— 只有 {@code vod_event_last_consume_lag_seconds} 能暴露它（契约 §7.1）。
 * 所以 {@link #queueDepth()} 取不到时返回 {@code -1} 而不是 0：<b>假的 0 会把唯一的信号抹掉。</b>
 */
@Component("smqClient")
@ConditionalOnExpression("'${edumatrix.vod.smq.queue-name:}'.trim() != ''"
        + " and '${edumatrix.vod.smq.endpoint:}'.trim() != ''")
public class SmqClient implements VodEventSource, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SmqClient.class);

    /** 云调用必须有硬超时：没有它，「卡死」是无界的，隔离只保证不传染、不保证自己会好。 */
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int SOCKET_TIMEOUT_MS = 5000;

    private final String queueName;
    private final MNSClient client;

    public SmqClient(@Value("${edumatrix.vod.smq.endpoint}") String endpoint,
                     @Value("${edumatrix.vod.smq.queue-name}") String queueName,
                     @Value("${edumatrix.file.oss.access-key-id}") String accessKeyId,
                     @Value("${edumatrix.file.oss.access-key-secret}") String accessKeySecret) {
        this.queueName = queueName.trim();
        ClientConfiguration configuration = new ClientConfiguration();
        configuration.setConnectionTimeout(CONNECT_TIMEOUT_MS);
        configuration.setSocketTimeout(SOCKET_TIMEOUT_MS);
        this.client = new CloudAccount(accessKeyId, accessKeySecret, endpoint.trim(), configuration)
                .getMNSClient();
        // 与 OssClient 那行「对象存储 = …」同一个用途：一条可 grep 的、能证明「接到哪儿了」的事实。
        // endpoint 与队列名都是非敏感值；【不打印任何凭据】
        log.info("转码事件队列 = 阿里云轻量消息队列 endpoint={} queue={}（拉模式，无入站端点）",
                endpoint.trim(), this.queueName);
    }

    @Override
    public List<VodEvent> receive(int max) {
        int size = Math.min(Math.max(max, 1), MAX_BATCH);
        List<Message> messages;
        try {
            messages = queue().batchPopMessage(size);
        } catch (Exception e) {
            // 拉取失败不外抛：外抛会让整轮消费中断，而下一轮 10 秒后还会来。
            // 但必须记 ERROR —— 长期拉不到的后果由 vod_event_last_consume_lag_seconds 兜住
            log.error("从轻量消息队列拉取失败 queue={}：{}", queueName, describe(e));
            return Collections.emptyList();
        }
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<VodEvent> events = new ArrayList<>(messages.size());
        for (Message message : messages) {
            // 取【原始】串：是否 Base64 由解析器两种都试（队列编码设置决定，猜错则每条都失败）
            events.add(VodEventPayloadParser.parse(
                    message.getReceiptHandle(), message.getMessageBodyAsRawString()));
        }
        return events;
    }

    @Override
    public void delete(String receiptHandle) {
        try {
            queue().deleteMessage(receiptHandle);
        } catch (Exception e) {
            // 删除失败不外抛：那条消息会在不可见时长到期后重投，而重投会被 CAS 幂等判定拦下。
            // 【但绝不能反过来】——落库失败时不许删，那才是丢事件（契约 §2.8）
            log.warn("删除已消费的消息失败 queue={}：{}。该消息将在不可见时长到期后重投，"
                    + "由状态机 CAS 幂等拦下（不会重复写库，可能多一次 GetPlayInfo）", queueName, describe(e));
        }
    }

    @Override
    public long queueDepth() {
        try {
            QueueMeta meta = queue().getAttributes();
            Long active = meta == null ? null : meta.getActiveMessages();
            return active == null ? -1L : active;
        } catch (Exception e) {
            log.warn("读取队列属性失败 queue={}：{}", queueName, describe(e));
            return -1L;
        }
    }

    private CloudQueue queue() {
        return client.getQueueRef(queueName);
    }

    @Override
    public void destroy() {
        client.close();
    }

    /** 只取异常类型与 message 的前若干字符，<b>不打全文</b>——SDK 异常可能回显请求细节。 */
    private static String describe(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return "[" + e.getClass().getSimpleName() + "] "
                + (message.length() > 200 ? message.substring(0, 200) + "…" : message);
    }
}
