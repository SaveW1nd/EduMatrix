package com.edumatrix.common.resource;

import java.util.Collection;
import java.util.Map;

import com.edumatrix.common.response.PageResult;

/**
 * 「我可授权的资源」的<b>领域侧查询</b> —— 每类受管资源各注册一个实现（模块 11 新增）。
 *
 * <h2>为什么必须是 SPI，而不是模块 11 自己查那三张表</h2>
 * <p>接口 37（03-02 §9.1）要<b>按资源表分页</b>，还要按 {@code keyword} / {@code subject} /
 * {@code categoryId} 筛选。这些条件只能在 {@code crs_course} / {@code qb_question} /
 * {@code vod_video} 上表达，而 {@code check_backend_conventions.sh} 检查③
 * 禁止 {@code org} 域 import 那三个域。于是接口开在 {@code common/}、实现留在各自领域内 ——
 * 与 {@link ResourceOwnerProvider}、{@code common/course/LessonVisibilityChecker}
 * 是同一个形状。
 *
 * <h2>为什么不把这两个方法挂到 {@link ResourceOwnerProvider} 上（F-89 定案）</h2>
 * <p>那个接口的契约逐字是「<b>只回答归属是谁，不做任何权限判定</b>」。
 * 「分页查我能授哪些」是另一个问题，塞进去会让一个 SPI 同时表达两件事 ——
 * 而本项目已经为「一个名字指两件事」付过一次代价（E 定案：{@code owns} 与
 * {@code isOwner} 都叫 owner，<b>调错不会报错</b>）。
 * 批量归属查询仍留在 {@link ResourceOwnerProvider#ownerNodeIdsOf}，因为那确实还是同一个问题。
 *
 * <h2>实现方不做任何权限判定</h2>
 * <p>与 {@link ResourceOwnerProvider} 同一条纪律：判定全部在调用方
 *（{@code ResourceOwnerChecker.canRegrant}），实现方只按
 * {@link GrantableResourceQuery#getMyNodeId()} 与
 * {@link GrantableResourceQuery#getRegrantableIds()} 拼 SQL。
 * 三类资源的口径因此必然一致 —— 散到三个域各判一次，就会有三种写法。
 */
public interface GrantableResourceProvider {

    /** 本实现负责哪一类资源。同一类型注册两个实现会在启动时失败（见 {@link GrantableResourceReader}）。 */
    ResourceType resourceType();

    /**
     * 分页查「我可授权的资源」= {@code owner_node_id = 我} ∪
     * {@link GrantableResourceQuery#getRegrantableIds()}。
     *
     * <p><b>租户条件由插件注入</b>，实现里一个字不写（契约 §2.9）。
     */
    PageResult<GrantableResourceItem> page(GrantableResourceQuery query);

    /**
     * 批量取资源展示名（课程名 / 题干摘要 / 视频名）。
     *
     * <p>模块 11 的接口 39 {@code cascadeDetail}、接口 41 列表、接口 52 预检
     * 都要在响应里带资源名，而资源名在三张业务表里 —— 这是 03-02 §3.4
     * 「{@code resourceName} <b>模块 06 恒为 null</b>，模块 11 接上后才有值」
     * 那句话的落地点。
     *
     * @return 只含<b>查得到</b>的资源；不存在 / 已删除 / 跨租户的键<b>不出现</b>
     */
    Map<Long, String> namesOf(Collection<Long> resourceIds);
}
