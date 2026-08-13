package com.edumatrix.common.idempotent;

import java.lang.reflect.Method;
import java.time.Duration;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.common.trace.TraceIdHolder;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@link Idempotent} 的切面实现。
 *
 * <h2>Key</h2>
 * <p>{@code idem:{userId}:{requestId}}（00-通用约定 §7.2 逐字规定），TTL 默认 300s。
 * <b>带 userId 是必需的</b>：{@code X-Request-Id} 由客户端生成，不同用户完全可能撞上同一个
 * UUID（甚至有客户端会写死一个）。不带 userId，A 的请求就会拿到 B 的处理结果。
 *
 * <h2>三个状态</h2>
 * <pre>
 * key 不存在        → 抢到执行权（SET NX），执行业务，把结果 JSON 写回同一个 key
 * key = 结果 JSON  → 直接反序列化返回，不重复执行            ← §7.2 原文定义的那一态
 * key = PROCESSING → 首次请求还在执行中，轮询等它写结果；
 *                    超过等待窗口仍未出结果 → HTTP 429 + Retry-After
 * </pre>
 *
 * <p><b>为什么先写 PROCESSING 占位、而不是执行完再写</b>：两个重复请求几乎同时到达时，
 * "执行完再写"会让两个都判定为首次、双双执行 —— 而这正是幂等要防的那件事。
 *
 * <p><b>业务抛异常时必须删掉占位</b>：失败的请求不应该被记成"已处理"，否则客户端重试
 * 会拿到一个空结果或一直被拒，而它本来是可以重试成功的。
 *
 * <h2>「执行中」这一态是 00-通用约定 §7.2 未定义的，本实现选 429 而非新增业务码</h2>
 * <p>§7.2 只写了「直接返回首次处理结果」，没说首次还没跑完时怎么办。选 429 的三条理由：
 * <ol>
 *   <li><b>客户端该做的动作完全一致</b>：§7.6 已定义「HTTP 429 → 读 {@code Retry-After}
 *       后再重试，不做即时重试」，正是这里想要的行为。语义偏差只在"为什么让你等"
 *       （限流 vs 前一次还没跑完），用 {@code Retry-After} 的取值就能消掉 ——
 *       限流场景给窗口剩余秒数，本场景给 1。前端一行代码都不用改；
 *   <li><b>幂等冲突属于框架层，不是业务语义</b>：被拦下的请求根本没进业务逻辑。
 *       按 §3.3「业务错误统一 HTTP 200，框架层错误 HTTP 码与 code 一致」，
 *       返回 HTTP 429 才是符合约定的做法；返回一个 {@code 1xxxx} 会让前端误以为
 *       业务上出了什么事；
 *   <li><b>不为一个中间态占掉 1xxxx 基础段的号位</b>：基础段只预留了 {@code 10010} 与
 *       {@code 10018~10099}，而废弃码保留号位不复用（§9.3 的 {@code 20017} 已有先例）——
 *       占了就再也改不动。
 * </ol>
 * <p><b>这条已记为待决项</b>：「§7.2 未定义『首次请求执行中』的中间态，实现取
 * 429 + {@code Retry-After: 1}，需确认或改为登记专用码」。
 *
 * <p>等待窗口 §7.2 没有定义（它只定了 Redis key 与 5 分钟 TTL），故做成配置项
 * {@code edumatrix.idempotent.wait-millis}，默认 1000ms。
 */
@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /** 幂等键前缀。00-通用约定 §7.2 写死为 {@code idem:{userId}:{requestId}}。 */
    public static final String KEY_PREFIX = "idem:";

    /** 请求头名。 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final String PROCESSING = "__PROCESSING__";

    /** 响应头：告诉客户端多久后再试（00-通用约定 §7.6 已定义客户端据此退避）。 */
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    /** 轮询间隔。 */
    private static final long WAIT_INTERVAL_MS = 50L;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 等待首次请求写出结果的总时长。§7.2 未定义此值，故可配，默认 1000ms。 */
    private final long waitTotalMillis;

    public IdempotentAspect(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                            long waitTotalMillis) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.waitTotalMillis = waitTotalMillis;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String requestId = currentRequestId();
        Long userId = TenantHelper.getUserId();

        if (requestId == null || requestId.isEmpty() || userId == null) {
            if (idempotent.allowMissingRequestId()) {
                // §7.2：X-Request-Id 是可选头，缺失时不能拒绝，否则老版本前端整片报错
                return pjp.proceed();
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少必填请求头：" + HEADER_REQUEST_ID);
        }

        String key = KEY_PREFIX + userId + ":" + requestId;
        Duration ttl = Duration.ofSeconds(idempotent.ttlSeconds());

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            try {
                Object result = pjp.proceed();
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), ttl);
                return result;
            } catch (Throwable ex) {
                // 失败的请求不算"已处理"：留着占位会让客户端的合法重试也被拒
                redisTemplate.delete(key);
                throw ex;
            }
        }

        return waitAndReadFirstResult(pjp, key);
    }

    private Object waitAndReadFirstResult(ProceedingJoinPoint pjp, String key) {
        long deadline = System.currentTimeMillis() + waitTotalMillis;
        while (true) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                // 首次请求失败并删了占位（或已过 TTL）：本次按新请求处理
                try {
                    return pjp.proceed();
                } catch (Throwable ex) {
                    throw sneaky(ex);
                }
            }
            if (!PROCESSING.equals(cached)) {
                log.info("幂等命中，直接返回首次结果 key={} traceId={}", key, TraceIdHolder.get());
                return deserialize(pjp, cached);
            }
            if (System.currentTimeMillis() >= deadline) {
                // 首次请求还在跑。既拿不到它的结果，也不能重复执行 ——
                // 让客户端按 §7.6 的退避策略稍后重试。Retry-After 给 1 秒：
                // 与限流场景同码同退避逻辑，只是等待时长不同，前端无需区分
                log.warn("幂等冲突：首次请求仍在处理中 key={} traceId={}", key, TraceIdHolder.get());
                setRetryAfter();
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "请求正在处理中，请稍后重试");
            }
            sleep();
        }
    }

    private Object deserialize(ProceedingJoinPoint pjp, String json) {
        try {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructType(method.getGenericReturnType()));
        } catch (Exception e) {
            throw new IllegalStateException("幂等缓存结果反序列化失败", e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(WAIT_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待幂等首次结果时被中断", e);
        }
    }

    private static String currentRequestId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getHeader(HEADER_REQUEST_ID);
    }

    /** 在响应上打 {@code Retry-After: 1}。客户端据此退避（00-通用约定 §7.6）。 */
    private static void setRetryAfter() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletResponse response = attrs.getResponse();
            if (response != null) {
                response.setHeader(HEADER_RETRY_AFTER, "1");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }
}
