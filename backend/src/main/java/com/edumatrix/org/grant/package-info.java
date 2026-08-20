/**
 * 模块 11 · 资源授权引擎 —— {@code org_resource_grant} 的<b>写侧</b>与查询接口。
 *
 * <h2>读侧不在这里</h2>
 * <p>「某节点能否使用某资源」是<b>全系统唯一口径</b>，落在
 * {@code common/grant/ResourceGrantReader}（需方定案 F）。本包<b>不另写一份</b> ——
 * 散成两份的后果是「有的地方回溯了祖先链、有的地方没回溯」，而<b>两种写法都返回 200</b>。
 * 「能不能再下发」同理，落在 {@code common/resource/ResourceOwnerChecker.canRegrant}。
 *
 * <h2>三个谓词，三个动词，别调错</h2>
 * <table border="1">
 *   <caption>{@code common/resource/ResourceOwnerChecker}</caption>
 *   <tr><th>动词</th><th>谓词</th><th>依据</th></tr>
 *   <tr><td><b>写</b>（编排 / 编辑 / 上下架）</td><td>{@code isOwner}</td><td>契约 §2.5 规则 8</td></tr>
 *   <tr><td><b>用</b>（学习 / 备课 / 组卷 / 可见性）</td><td>{@code canUse}</td><td>契约 §2.5 规则 1、4</td></tr>
 *   <tr><td><b>再下发</b>（本模块的接口 37 / 38 / 40）</td><td>{@code canRegrant}</td>
 *       <td>契约 §2.5 规则 1 + 规则 9</td></tr>
 * </table>
 *
 * <h2>三个权限维度互相独立，只有第一个随树收缩（04 §B 模块 11 规则 18）</h2>
 * <table border="1">
 *   <caption>{@code @SaCheckPermission} 通过 ≠ 有权授权</caption>
 *   <tr><th>维度</th><th>载体</th><th>是否逐级收缩</th></tr>
 *   <tr><td>数据范围</td><td>{@code org_node.ancestors} 子树</td><td><b>是</b>，自动生效</td></tr>
 *   <tr><td>功能权限</td><td>{@code sys_role_menu} → {@code sys_menu.perms}</td>
 *       <td><b>否</b>，由角色定、与树位置无关</td></tr>
 *   <tr><td>资源可用性</td><td>{@code org_resource_grant}</td>
 *       <td><b>既不收缩也不继承</b>，逐级显式授权、不回溯祖先链</td></tr>
 * </table>
 * <p>因此：下级管理员持有 {@code org:grant:grant} 只说明他能执行「下发」这个<b>动作</b>，
 * <b>不说明他能下发哪些资源</b>。拥有性一律走 {@code canRegrant}，
 * <b>不得因为注解已放行就跳过</b>，不满足返回 {@code 10301}。
 *
 * <p>而 {@code 10301} 的响应<b>不区分「资源不存在」与「你无权」</b>（契约 §2.5 规则 1、
 * PRD FR-1 规则 2）—— 与 F-42 是同一条防探测的推理。
 *
 * <h2>跨领域怎么读资源</h2>
 * <p>检查③ 禁止 {@code org} 域 import {@code course} / {@code question} / {@code vod}。
 * 资源的归属、名称与可授权清单一律经 {@code common/resource} 的两个 SPI 取：
 * {@code ResourceOwnerProvider}（归属）与 {@code GrantableResourceProvider}（清单 + 名称）。
 */
package com.edumatrix.org.grant;
