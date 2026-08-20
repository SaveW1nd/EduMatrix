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
 * <h2>⚠ 长轮询由<b>队列侧</b>配置决定，客户端超时<b>必须大于它</b></h2>
 * <p><b>〔订正：原注释写「不带 {@code waitSeconds} —— 队列空时立刻返回空列表」，那是错的〕</b>
 * 不传参数时服务端<b>按队列上配的「长轮询时间」挂住连接</b>，并不是立刻返回。
 * 生产队列配的是 <b>15 秒</b>，而当时的 {@link #SOCKET_TIMEOUT_MS} 是 5 秒 ——
 * 于是<b>每一次空轮询都必然超时</b>：队列空的时候每 10 秒刷一条
 * {@code SocketTimeoutException: 5,000 milliseconds timeout}，
 * <b>而系统其实是好的</b>（首次生产运行时 13 条积压消息已全部正确处置、一条没丢）。
 *
 * <p><b>原注释给的理由现在也不成立了</b>：「带了会把调度线程占住」——
 * 消费任务后来配了<b>独立的 {@code vodEventTaskScheduler}</b>
 * （见 {@code common/config/SchedulerConfig}），占住它<b>自己那条线程</b>没有任何问题，
 * 两个合规日任务在另一个池上。<b>阻塞等待在这里是可接受的做法</b>，
 * 而且长轮询本身更优：有消息立刻返回、空队列时调用量更低。
 *
 * <p><b>所以不改队列配置，改客户端超时</b> —— 不该让配置去迁就一个过期的代码假设。
 * 具体取值与它必须满足的关系见 {@link #SOCKET_TIMEOUT_MS}。
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

    /**
     * 轻量消息队列<b>长轮询时间的协议上限</b>：30 秒。任何队列的「长轮询时间」配置都 ≤ 它。
     *
     * <p><b>钉这个上限、而不是钉当前生产那个 15 秒</b>，理由是<b>15 秒是控制台上的一个值，
     * 我们看不见它什么时候被改</b>：钉 15 秒的话，运维把长轮询调到 20~30 秒的那一天，
     * 代码会重新开始每轮超时，<b>而没有任何测试会红</b> ——
     * 那正是本次这个缺陷的原样复发。钉上限则<b>对任何队列配置都成立</b>，
     * 把「对不对取决于控制台上一个我们看不见的值」变成「结构上对」。
     */
    static final int MNS_MAX_LONG_POLL_MS = 30_000;

    /**
     * 读超时。<b>必须大于队列的「长轮询时间」配置</b>，否则每一次空轮询都超时 ——
     * 而那时系统其实是好的，只是每 10 秒刷一条 ERROR（首次生产运行实测）。
     *
     * <p>取 <b>35 秒</b> = {@link #MNS_MAX_LONG_POLL_MS} 30 秒 + 5 秒余量。
     * 队列的长轮询时间是<b>部署级配置</b>，登记在 {@code 05-工程结构.md §F4}；
     * 生产当前配的是 <b>15 秒</b>，但本值按上限取，改配置不需要动代码。
     *
     * <p><b>{@code SmqClientTimeoutTest} 用一条断言把这个关系钉住</b> ——
     * 有人把它调小到 30 秒以下就红，而不是等生产刷 ERROR 才发现。
     *
     * <p><b>⚠ 与 {@code VodClient} 的读超时是两个值，不共用</b>：那边是
     * {@code GetPlayInfo} 之类的<b>请求-响应</b>调用，5 秒不回就是真的慢；
     * 这边是<b>长轮询</b>，30 秒不回是<b>正常的</b>。两者性质相反，
     * 共用一个值必然有一边是错的 —— 本次这个缺陷就是「按请求-响应的直觉给长轮询定超时」。
     */
    static final int SOCKET_TIMEOUT_MS = 35_000;

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
            // 不传 waitSeconds：由队列侧的「长轮询时间」配置决定挂多久（生产 15s）。
            // 客户端读超时必须大于它，见 SOCKET_TIMEOUT_MS
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
