package com.edumatrix.common.media;

import java.util.List;

/**
 * 转码事件的来源 —— 阿里云<b>轻量消息队列</b>（原 MNS）的窄视图（03-03 §7.2）。
 *
 * <p>接口在 {@code common/}、实现在 {@code integration/aliyun/}，与
 * {@code common/file/ObjectStorage} ← {@code integration/aliyun/OssClient} 同型
 * （05-工程结构.md §G2：部署级配置的消费方只有 {@code integration/aliyun/**}）。
 *
 * <h2>为什么要抽这一层</h2>
 * <p>不是为了「将来换云厂商」（契约 §1：阿里云是本期唯一实现），
 * 而是为了让消费链路能在<b>没有真云账号</b>的情况下被测到 ——
 * 生产上 {@code /etc/edumatrix/db.env} 里连 {@code ALIYUN_SMQ_*} 都还没有。
 *
 * <p><b>但要说清这一层证不了什么</b>：假实现用的是我们<b>按文档写的</b>报文形状。
 * 形状写错的话全部 IT 照样绿，而生产上每条消息都走解析失败分支。
 * 这就是 {@link VodEvent#rawBody()} 与「解析失败必须走孤儿处置、不得静默 continue」
 * 存在的理由 —— 至少让它在指标与日志上<b>响得出来</b>。
 *
 * <h2>拉模式，无入站端点</h2>
 * <p>契约 §2.8 定案：去掉免登录 HTTP 回调端点之后，<b>再没有一个免登录入口能写库</b>。
 * 本接口的任何实现都<b>不得</b>以 HTTP 端点的形式出现。
 */
public interface VodEventSource {

    /** 单次拉取上限（03-03 §7.2「单次拉取上限 16 条」，04 §B 模块 09 对外产出同）。 */
    int MAX_BATCH = 16;

    /**
     * 拉一批消息。
     *
     * <p><b>不做长轮询</b>：长轮询会把调度线程占住到 30s，与 10s 的触发间隔和单轮预算都冲突。
     * 队列空时应立刻返回空列表。
     *
     * @param max 本次最多取几条，实现方须自行收敛到 {@link #MAX_BATCH}
     * @return 取到的消息；队列空时返回空列表，<b>不返回 null</b>
     */
    List<VodEvent> receive(int max);

    /**
     * 删除一条消息。<b>只能在落库成功之后调</b>（契约 §2.8：顺序反了就是丢事件）。
     *
     * <p>删除失败不抛异常 —— 那条消息会在不可见时长到期后重投，
     * 而重投会被 CAS 幂等判定拦下（见 {@code VodEventConsumeService}）。
     */
    void delete(String receiptHandle);

    /**
     * 队列中的活跃消息数，供 {@code vod_event_queue_depth} 埋点（契约 §7.1，告警线 &gt; 1000）。
     *
     * @return 取不到时返回 {@code -1}（调用方据此<b>不上报</b>，而不是上报一个假的 0 ——
     *         恒为 0 正是「配错了静默丢弃」的表现，不能由我们自己制造出来）
     */
    long queueDepth();

    /** 本实现是不是「未配置」的空实现。为真时消费任务直接空转，不打日志。 */
    default boolean enabled() {
        return true;
    }
}
