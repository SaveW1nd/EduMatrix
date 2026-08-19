package com.edumatrix.vod.media.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.edumatrix.vod.media.entity.VodVideoLookup;

/**
 * 转码事件消费<b>唯一</b>的一次跨租户查询：按 {@code vod_file_id} 反查媒资行取 {@code tenant_id}。
 *
 * <h2>为什么必须绕过租户插件</h2>
 * <p>契约 §2.8 规则 1：事件消费<b>没有会话</b>，上下文必须由数据显式携带 ——
 * 从 {@code vod_video.vod_file_id} 反查行、取其 {@code tenant_id}，再
 * {@code TenantHelper.runWithTenant(...)} 包住后续全部操作。
 * 而<b>这第一次查询本身</b>就需要租户上下文：{@code EduMatrixTenantLineHandler#getTenantId}
 * 走的是 {@code requireTenantId()}，取不到<b>直接抛</b>。
 * 也就是「租户是这次查询的<b>结果</b>而不是<b>前提</b>」—— 与登录时按用户名查
 * {@code sys_user} 是同一形状（{@code TenantHelper#ignore} 的 Javadoc 逐字写着那是唯一已知场景）。
 *
 * <h2>为什么用 {@code @InterceptorIgnore} 而不是 {@code TenantHelper.ignore(...)}</h2>
 * <p><b>作用面更窄</b>：注解只豁免<b>这一个 mapped statement</b>，
 * 而 {@code ignore(...)} 豁免整个 lambda 里的<b>所有</b> SQL —— 后者一旦有人往里多塞一句，
 * 那句也跟着跨租户了，且不会报错。
 *
 * <p><b>代价已配套处置</b>：{@code scripts/check_backend_conventions.sh} 的检查⑤
 * 把两种写法合并成一个数字「跨租户逃生舱 = N 处」并逐条列出位置 ——
 * 否则这处豁免对审计清单是隐形的。<b>脚本先于本文件落地</b>（模块 09 提交 1），
 * 反过来的话中间那段时间就是一次「以为存在、实际从未生效的保障」。
 *
 * <h2>投影刻意只取四列</h2>
 * <p>跨租户查询能取到什么就该只取消费链路真正需要的：定位行（{@code id}）、
 * 设上下文（{@code tenant_id}）、做 CAS 前置判定（{@code status}）、判要不要刷冗余（{@code duration}），
 * 外加 {@code deleted_at} 决定是不是孤儿。<b>取整行等于让一次豁免顺带把整张表跨租户暴露给调用方。</b>
 */
@Mapper
public interface VodEventLookupMapper {

    /**
     * 按 {@code (provider, vod_file_id)} 反查。
     *
     * <h2>为什么条件里必须带 {@code provider}</h2>
     * <p>唯一键是 {@code uk_provider_file(provider, vod_file_id, deleted_at)}，
     * DDL 注释逐字说它就是「转码事件消费的幂等定位」；{@code provider} 列的注释还写着
     * 「默认值写错会让整批媒资落在错误的唯一键分区上，转码事件消费时按
     * {@code (provider, vod_file_id)} 反查将<b>定位不到</b>」。只按 {@code vod_file_id} 查
     * 在腾讯兼容位启用后会撞。
     *
     * <h2>⚠ 刻意<b>不带</b> {@code deleted_at = 0}</h2>
     * <p>带了的话，转码中被人工删除的媒资、其后续事件会被算成<b>孤儿</b>，
     * 而 {@code vod_callback_orphan_total} 的告警线是 <b>&gt; 0</b> ——
     * 每删一个转码中的视频就是一次假警报，最后没人看这条告警。
     * 与契约 §7.1 对 {@code grant_dangling_count} 那条「跨管辖单独打点、不进告警」是同一条纪律。
     * 已删除的行由调用方识别后<b>删消息 + INFO</b>，不计孤儿。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, tenant_id AS tenantId, status, duration, deleted_at AS deletedAt "
            + "  FROM vod_video WHERE provider = #{provider} AND vod_file_id = #{vodFileId}")
    VodVideoLookup findByProviderAndFileId(@Param("provider") int provider,
                                           @Param("vodFileId") String vodFileId);
}
