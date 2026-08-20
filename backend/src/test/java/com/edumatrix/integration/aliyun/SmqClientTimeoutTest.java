package com.edumatrix.integration.aliyun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拉消息的读超时与队列长轮询时间的关系 —— <b>这条守的是一个已经在生产上发生过的缺陷</b>。
 *
 * <h2>发生了什么</h2>
 * <p>首次生产运行时，队列空的每一轮都刷一条
 * {@code SocketTimeoutException: 5,000 milliseconds timeout}。
 * 根因是代码假设与队列配置对不上：{@code SmqClient} 的读超时是 5 秒，
 * 而队列的<b>长轮询时间配的是 15 秒</b> —— 不传 {@code waitSeconds} 时服务端
 * <b>按队列上配的值挂住连接</b>，并不是「立刻返回空列表」（原注释写错了）。
 *
 * <p><b>功能其实是通的</b>：13 条积压消息全部按孤儿路径正确处置
 * （{@code parse_failed=1}、{@code video_not_found=12}），{@code vod_event_queue_depth=0}，一条没丢。
 * 也就是说这是一条<b>纯噪声的 ERROR</b> —— 而纯噪声的 ERROR 最危险的地方在于：
 * 它会让人习惯「这个模块本来就一直报错」，然后<b>真出事的那条也没人看</b>。
 *
 * <h2>为什么钉的是协议上限 30 秒，而不是当前生产的 15 秒</h2>
 * <p><b>15 秒是控制台上的一个值，我们看不见它什么时候被改。</b>
 * 钉 15 秒的话，运维把长轮询调到 20~30 秒的那一天，代码会重新开始每轮超时，
 * <b>而没有任何测试会红</b> —— 本次这个缺陷原样复发。
 * 钉上限（MNS 的长轮询时间最大 30 秒，任何队列配置都 ≤ 它）则<b>对任何配置都成立</b>，
 * 把「对不对取决于控制台上一个我们看不见的值」变成「结构上对」。
 *
 * <p>这与检查⑧ 那条的教训是同一个形状：<b>要钉的是不变量，不是当前取值</b>。
 */
class SmqClientTimeoutTest {

    /**
     * 读超时必须<b>严格大于</b>长轮询的协议上限。
     *
     * <p>变异：把 {@code SOCKET_TIMEOUT_MS} 调回 5000（或任何 ≤ 30000 的值）→ 本条红。
     */
    @Test
    @DisplayName("拉消息的读超时 > 长轮询协议上限 30s（调小到 30s 以下就红）")
    void socketTimeoutMustExceedLongPollingUpperBound() {
        assertThat(SmqClient.SOCKET_TIMEOUT_MS)
                .as("拉消息的读超时（%d ms）必须【大于】队列长轮询时间的协议上限（%d ms）。"
                                + "小于等于它 = 队列空时每一轮都 SocketTimeoutException，"
                                + "而系统其实是好的 —— 首次生产运行就是这么刷了一屏 ERROR。"
                                + "长轮询时间是控制台上的配置（生产当前 15s），"
                                + "所以这里钉的是【协议上限】而不是当前值",
                        SmqClient.SOCKET_TIMEOUT_MS, SmqClient.MNS_MAX_LONG_POLL_MS)
                .isGreaterThan(SmqClient.MNS_MAX_LONG_POLL_MS);
    }

    /**
     * 拉消息的超时与点播 API 的超时<b>必须是两个值</b>。
     *
     * <p>两者性质相反：{@code GetPlayInfo} 是<b>请求-响应</b>，5 秒不回就是真的慢；
     * 拉消息是<b>长轮询</b>，30 秒不回是<b>正常的</b>。
     * 共用一个值必然有一边是错的 —— 而本次这个缺陷正是
     * 「按请求-响应的直觉给长轮询定超时」。
     *
     * <p>本条断言的是<b>两者不相等</b>：它拦的不是「某个值不对」，
     * 而是将来有人「统一一下超时配置」把两者合并。
     */
    @Test
    @DisplayName("拉消息的超时与 GetPlayInfo 的超时是两个值（合并的那天必有一边是错的）")
    void queuePollingAndVodApiTimeoutsAreSeparate() {
        assertThat(SmqClient.SOCKET_TIMEOUT_MS)
                .as("拉消息（长轮询，30s 不回是正常的）与点播 API（请求-响应，5s 不回就是慢）"
                        + "性质相反，不能共用一个超时值")
                .isNotEqualTo(VodClient.READ_TIMEOUT_MS);
        assertThat(SmqClient.SOCKET_TIMEOUT_MS)
                .as("长轮询这一侧必须明显更宽松")
                .isGreaterThan(VodClient.READ_TIMEOUT_MS);
    }
}
