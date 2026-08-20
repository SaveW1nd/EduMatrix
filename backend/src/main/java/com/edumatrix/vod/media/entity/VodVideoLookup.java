package com.edumatrix.vod.media.entity;

/**
 * 事件消费反查的<b>窄投影</b>（见 {@code VodEventLookupMapper}）。
 *
 * <p><b>不是实体</b>，不继承 {@code TenantEntity} —— 它是一次跨租户查询的结果，
 * 复用实体会让人以为可以拿它去做写操作，而那条路必须先 {@code runWithTenant}。
 *
 * @param id        媒资 ID
 * @param tenantId  租户 —— 这次查询的<b>目的</b>就是它（契约 §2.8 规则 1）
 * @param status    CAS 前置判定用；顺带让「重复事件」在<b>调 GetPlayInfo 之前</b>就被挡掉
 * @param duration  判要不要发异步冗余刷新
 * @param deletedAt {@code != 0} 表示媒资已被人工删除 —— 删消息但<b>不计孤儿</b>
 */
public record VodVideoLookup(Long id, Long tenantId, Integer status, Integer duration, Long deletedAt) {

    public boolean deleted() {
        return deletedAt != null && deletedAt != 0L;
    }
}
