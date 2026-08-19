package com.edumatrix.common.operlog;

import java.util.LinkedHashMap;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code @OperLog} 的切面实现 —— <b>F-25 关闭点</b>。
 *
 * <p>{@code @OperLog} 的类注释逐字：「注解定义在模块 01（本包），<b>切面实现在模块 05</b>……
 * 在切面到位之前，本注解不产生任何行为，但<b>已标注的位置一个都不用改</b>」。
 * 本类到位后，全库 19 处已标注的位置<b>一处未改</b>即自动生效。
 *
 * <h2>切面的位置：{@code @Transactional} 之外，{@code @Idempotent} 之内</h2>
 * <pre>
 * IdempotentAspect   (LOWEST-1000)   ← 最外：重放的请求不该再记一条"新操作"
 *   OperLogAspect    (LOWEST-900)    ← 本类
 *     @Transactional (LOWEST)        ← 最内
 * </pre>
 * <ul>
 *   <li><b>必须在事务外层</b>：业务事务回滚时，那一行 {@code status=1} 的失败记录
 *       <b>必须留下来</b>。写在事务内则连日志一起回滚 ——「谁试过什么但失败了」
 *       正是审计最需要的部分，而它会消失得无声无息；</li>
 *   <li><b>放在幂等切面内层</b>：{@code X-Request-Id} 命中重放时直接返回首次结果、
 *       业务方法根本没执行，此时不该再记一条操作日志。</li>
 * </ul>
 * <p>顺序由 {@link #ORDER} 显式声明并由 {@code OperLogAspectOrderIT} 钉住 ——
 * 与 {@code IdempotentAspectOrderIT} 同一条理由：不写 {@code @Order} 就是把它交给运气，
 * 而顺序错了的表现（回滚掉的失败记录、重放请求被记两遍）没有任何异常与日志。
 *
 * <h2>失败也记，且业务码拒绝算失败</h2>
 * <p>{@link BizException}（如 {@code 10011} 文件类型不支持、{@code 10107} 目标越界）
 * 一律 {@code status=1}。契约 §7.1 的日志分级把越权拒绝列为要盯的事，
 * 只记成功的操作日志在排查越权时<b>正好缺掉关键那部分</b>。
 * <b>切面不吞异常、不改变业务结果</b> —— 记完原样重抛。
 *
 * <h2>{@code params} 里不进的三样</h2>
 * <ol>
 *   <li>{@code saveParams = false} 的方法整段不记（§3.6 重置密码已在用：请求体就是新密码明文）；</li>
 *   <li>{@link MultipartFile} / {@link HttpServletRequest} / {@link HttpServletResponse}
 *       类型的参数<b>跳过</b>。不跳的话，03-01 §7.1 上传接口一旦标注解，
 *       会把最大 100MB 的二进制序列化进一列 {@code TEXT}；</li>
 *   <li>口令与手机号由 {@link SensitiveParamMasker} 脱敏（契约 §7.2）。</li>
 * </ol>
 */
@Aspect
@Component
@Order(OperLogAspect.ORDER)
public class OperLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    /**
     * 见类注释。比 {@code IdempotentAspect.ORDER}（{@code LOWEST-1000}）大 → 在它内层；
     * 比 {@code @Transactional} 的默认 {@link Ordered#LOWEST_PRECEDENCE} 小 → 在事务外层。
     */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 900;

    private final OperLogWriter operLogWriter;
    private final ObjectMapper objectMapper;

    public OperLogAspect(OperLogWriter operLogWriter, ObjectMapper objectMapper) {
        this.operLogWriter = operLogWriter;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperLog operLog) throws Throwable {
        long startNanos = System.nanoTime();
        int status = OperLogWriter.STATUS_SUCCESS;
        String errorMsg = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            status = OperLogWriter.STATUS_FAIL;
            errorMsg = describe(t);
            throw t;
        } finally {
            int costMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
            record(joinPoint, operLog, status, errorMsg, costMs);
        }
    }

    private void record(ProceedingJoinPoint joinPoint, OperLog operLog,
                        int status, String errorMsg, int costMs) {
        try {
            operLogWriter.write(
                    TenantHelper.getUserId(),
                    operLog.module(),
                    operLog.action(),
                    currentMethodDescriptor(joinPoint),
                    operLog.saveParams() ? serializeParams(joinPoint) : null,
                    currentIp(),
                    status,
                    errorMsg,
                    costMs,
                    null);
        } catch (RuntimeException e) {
            // 兜底：连"组装日志行"本身都失败也不能改变业务结果
            log.error("组装 sys_oper_log 行失败 module={} action={}", operLog.module(), operLog.action(), e);
        }
    }

    /**
     * {@code sys_oper_log.method}：Web 请求下是 {@code "HTTP 方法 + 路径"}
     * （03-01 §8.2 响应字段说明逐字），无请求上下文时退回 Java 方法签名
     * （DDL 注释逐字「请求方法（HTTP 方法 + 路径<b>或 Java 方法签名</b>）」，两种都在册）。
     */
    private static String currentMethodDescriptor(ProceedingJoinPoint joinPoint) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            return request.getMethod() + " " + request.getRequestURI();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
    }

    /**
     * 参数 → 脱敏后的 JSON 文本。
     *
     * <p>用 {@code valueToTree} 再走 {@link SensitiveParamMasker} 而不是"序列化成字符串再正则替换"：
     * 正则要处理转义、嵌套与数组，而<b>漏掉一种嵌套形态的表现是手机号明文落库</b>，且没有任何报错。
     * 走 JSON 树则嵌套 DTO、List、Map 一视同仁。
     */
    private String serializeParams(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (isSkipped(arg)) {
                continue;
            }
            params.put(names != null && i < names.length ? names[i] : ("arg" + i), arg);
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            JsonNode tree = SensitiveParamMasker.mask(objectMapper.valueToTree(params));
            return objectMapper.writeValueAsString(tree);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("序列化 sys_oper_log.params 失败，本行 params 置空", e);
            return null;
        }
    }

    /** 见类注释「{@code params} 里不进的三样」第 2 条。 */
    private static boolean isSkipped(Object arg) {
        return arg == null
                || arg instanceof MultipartFile
                || arg instanceof MultipartFile[]
                || arg instanceof HttpServletRequest
                || arg instanceof HttpServletResponse;
    }

    /**
     * 失败信息：<b>业务异常记业务码 + msg，其余记类名 + message</b>。
     *
     * <p>不记堆栈：{@code error_msg} 是 {@code VARCHAR(2000)}，堆栈进去只能塞下最外面几帧，
     * 而那几帧恰恰是最没有信息量的。真正的堆栈在应用日志里，靠 {@code traceId} 串起来（契约 §7.1）。
     */
    private static String describe(Throwable t) {
        if (t instanceof BizException biz) {
            return "code=" + biz.getErrorCode().getCode() + " " + biz.getMessage();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /**
     * 客户端 IP。取值口径与 {@code auth/service/LoginLogService#currentIp} <b>必须一致</b>——
     * 部署形态是 Caddy 反代（{@code deploy/prod/Caddyfile} 显式补了 {@code X-Real-IP}），
     * 不取转发头的话两张日志表里的 IP 全是网关地址。
     *
     * <p><b>这里是一处有意的重复</b>：{@code LoginLogService} 在 {@code auth} 域，
     * 而 {@code check_backend_conventions.sh} 检查③ 禁止 {@code common} 之外的跨域 import；
     * 把取 IP 下沉到 {@code common} 又会让公共层开始认识 HTTP 转发头的部署细节。
     * 两处逻辑各 10 行、无状态、无分支差异，改一处要改另一处 —— 两边注释互指。
     */
    static String currentIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}
