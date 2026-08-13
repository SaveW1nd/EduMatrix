package com.edumatrix.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * POST 创建类接口的防重（00-通用约定 §7.2）。
 *
 * <p>客户端生成 UUID 放进请求头 {@code X-Request-Id}；同一用户携带相同 {@code X-Request-Id}
 * 的请求在 <b>5 分钟</b>内重复到达时，服务端<b>直接返回首次处理结果，不重复执行</b>。
 * Redis key：{@code idem:{userId}:{requestId}}。
 *
 * <p><b>只给真正需要的接口加。</b>00-通用约定 §7.3 列了四类<b>业务层天然防重</b>的场景，
 * 它们靠唯一约束或业务规则兜住，不需要本注解：
 * <ul>
 *   <li>心跳上报 —— 服务端间隔合理性校验（&lt;8s 丢弃返回 {@code 20002}），重复/伪造上报不会多计时长；
 *   <li>答卷提交 —— {@code UK(homework_id, student_id)}，重复提交返回 {@code 30003}；
 *   <li>资源授权 —— {@code UK(resource_type, resource_id, target_node_id)}，重复授权返回 {@code 10303}；
 *   <li>学生 Excel 导入 —— 以任务为单位异步执行，重复上传生成新任务，不叠加写入。
 * </ul>
 * 给它们再加一层，只是多一处会配错的地方。
 *
 * <p><b>GET / PUT / DELETE 不需要它</b>（§7.1）：GET 天然幂等；PUT 重复提交同一修改结果一致；
 * DELETE 对已逻辑删除的资源重复调用返回 {@code code=200} 而不报错。
 *
 * <p><b>节点移动类接口不要用它，也不要让客户端自动重试</b>（§7.5）：移动接口对客户端
 * <b>不可自动重试</b> —— 超时后节点可能已移动成功，盲目重试会把节点再移到另一位置。
 * 客户端应改为重新拉取节点详情确认当前 {@code parentId}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等窗口（秒）。默认 300 = 5 分钟（00-通用约定 §7.2）。
     *
     * <p>改大要想清楚：窗口内的第二次请求拿到的是<b>缓存的首次结果</b>，
     * 而不是重新执行的结果。
     */
    int ttlSeconds() default 300;

    /**
     * 缺少 {@code X-Request-Id} 请求头时是否直接放行。
     *
     * <p>默认 {@code true} —— §7.2 的原话是「服务端支持<b>可选</b>请求头 {@code X-Request-Id}」，
     * 「对创建类接口<b>建议</b>一律携带」。它是建议不是强制，服务端不能因为客户端没带就拒绝，
     * 那会让老版本前端整片报错。
     */
    boolean allowMissingRequestId() default true;
}
