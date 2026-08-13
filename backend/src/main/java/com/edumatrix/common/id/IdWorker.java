package com.edumatrix.common.id;

/**
 * 雪花算法 ID 生成器（契约 §1：ID 由 Java 侧生成，bigint，非自增）。
 *
 * <p><b>全库主键一律由它生成</b>（05-工程结构.md §E）。MyBatis-Plus 的
 * {@code id-type: assign_id} 也被接到这里 —— {@code MybatisPlusConfig} 注册的
 * {@code IdentifierGenerator} 直接委派给本类，因此「实体不写 id 让 MP 填」与
 * 「业务代码显式 {@code IdWorker.nextId()}」用的是同一个序列，不会出现两套。
 *
 * <p><b>为什么不是自增</b>：ID 要在事务提交前就确定 —— 组织树移动、作业发布预建答卷、
 * 授权批量写入这些操作都要先拿到 ID 再拼后续语句。另外雪花 ID 天然含时间序，
 * 而 {@code qb_question.id} 契约要求「永不复用」，自增在分库或数据迁移后守不住这一条。
 *
 * <p><b>前端一律按字符串收发</b>（00-通用约定 §5）：19 位雪花 ID 超过 JavaScript
 * {@code Number.MAX_SAFE_INTEGER}（2^53-1），直接传数字会静默丢精度 ——
 * 表现是列表点进去 404，而不是报错。序列化由 {@code JacksonConfig} 全局处理，
 * <b>不逐字段加注解</b>（漏一个字段就是一次线上事故）。
 *
 * <h2>workerId 必须逐台不同</h2>
 * <p>{@code SNOWFLAKE_WORKER_ID} / {@code SNOWFLAKE_DATACENTER_ID} 是部署级参数
 * （05-工程结构.md §F4，本项由模块 01 新增登记），各 0~31。两台机器配同一个号会产生
 * <b>重复主键</b>，而重复主键只在写入时才报错、报的还是唯一键冲突，不会指向配置。
 * 单机开发用默认的 0/0 即可。
 *
 * <h2>时钟回拨</h2>
 * <p>回拨在 5ms 内自旋等待，超过 5ms 直接抛异常。<b>不容忍大幅回拨</b>：
 * 静默沿用旧时间戳会产生重复 ID，而重复 ID 落到 {@code qb_question} 上就直接破了
 * 「物理 ID 恒定、永不复用」这条契约。宁可这次写入失败，也不要发一个可能重复的 ID。
 */
public class IdWorker {

    /** 起始时间戳：2026-01-01 00:00:00 UTC+8。此后 69 年可用。 */
    private static final long EPOCH = 1767196800000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    public static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);          // 31
    public static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);  // 31

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);          // 4095

    /** 可容忍的时钟回拨上限（毫秒）。超过它就抛异常，不发可能重复的 ID。 */
    private static final long MAX_TOLERATED_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /** 进程内单例。由 {@code IdConfig} 在启动时用配置值初始化。 */
    private static volatile IdWorker instance = new IdWorker(0L, 0L);

    public IdWorker(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "SNOWFLAKE_WORKER_ID 必须在 0~" + MAX_WORKER_ID + " 之间，当前值 " + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "SNOWFLAKE_DATACENTER_ID 必须在 0~" + MAX_DATACENTER_ID + " 之间，当前值 " + datacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /** 由 Spring 装配时调用一次，把配置里的 workerId / datacenterId 装进来。 */
    public static void setInstance(IdWorker idWorker) {
        instance = idWorker;
    }

    public static IdWorker getInstance() {
        return instance;
    }

    /** 生成下一个 ID。全库主键的唯一来源。 */
    public static long nextId() {
        return instance.next();
    }

    public synchronized long next() {
        long timestamp = currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_TOLERATED_BACKWARD_MS) {
                throw new IllegalStateException(
                        "检测到时钟回拨 " + offset + "ms，超出可容忍上限 " + MAX_TOLERATED_BACKWARD_MS
                                + "ms，拒绝生成 ID。继续发号会产生重复主键，而 qb_question 契约要求"
                                + "「物理 ID 恒定、永不复用」——宁可本次写入失败");
            }
            // 小幅回拨：等到时间追上来
            while (timestamp < lastTimestamp) {
                timestamp = currentTimeMillis();
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 本毫秒 4096 个序号用完，自旋到下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTs) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    /** 单独抽出来便于测试注入时钟。 */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public long getWorkerId() {
        return workerId;
    }

    public long getDatacenterId() {
        return datacenterId;
    }
}
