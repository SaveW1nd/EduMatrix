package com.edumatrix.common.operlog;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标在写操作的 Controller 方法上，由切面写入 {@code sys_oper_log}。
 *
 * <p><b>注解定义在模块 01（本包），切面实现在模块 05</b>（05-工程结构.md §C2 / §D）。
 * 分开是因为全库从第一天起就要能标注 —— 等模块 05 做完再回头补标，
 * 一定会漏掉前面四个模块的写接口。
 *
 * <p><b>切面已于模块 05 到位：{@link OperLogAspect}（F-25 关闭）。</b>
 * 当时那句「已标注的位置一个都不用改」得到了验证 —— 切面落地时
 * {@code org} 域已有的 19 处标注<b>一处未改</b>即自动生效。
 * 写入实现是 {@link OperLogWriter}（全库唯一往 {@code sys_oper_log} 插行的地方），
 * {@code params} 脱敏在 {@link SensitiveParamMasker}。
 *
 * <p><b>{@code module} / {@code action} 填什么</b>：{@code sys_oper_log} 的 DDL 注释写的是
 * 中文可读值 —— {@code module} 如「学生管理 / 作业管理」，{@code action} 如
 * 「新增 / 修改 / 删除 / 导出」。<b>它与契约 §3.1 的 {@code perms} 动作词表
 * （{@code add} / {@code edit} / {@code remove} …）不是一回事</b>：那套是线上鉴权依据，
 * 这套是给人看的审计记录。不要把两者混用，也不要为了"统一"把本注解改成英文动作词 ——
 * 操作日志是要给机构管理员在页面上看的。
 *
 * <p><b>敏感字段必须脱敏后再进 {@code params}</b>（DDL 注释明写）。K12 场景下
 * {@code guardian_phone} / {@code phone} 属敏感个人信息（契约 §7.2），
 * 原样写进操作日志等于绕过了脱敏。切面实现时按字段名白/黑名单处理。
 *
 * <p><b>本表保留 ≥ 6 个月且不参与"删除请求"的清理</b>（契约 §7.2 第 5 条、《网络安全法》第 21 条）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperLog {

    /** 业务模块，中文可读值，如「学生管理」。 */
    String module();

    /** 操作动作，中文可读值，如「新增」「毕业归档」。 */
    String action();

    /**
     * 是否记录请求参数。默认记录。
     *
     * <p>置 false 的场景：请求体过大（如富文本图文资料正文、批量授权 5000 行），
     * 或整体属于敏感信息且脱敏后已无审计价值。
     */
    boolean saveParams() default true;
}
