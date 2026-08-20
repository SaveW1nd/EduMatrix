package com.edumatrix.common.resource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「我可授权的资源列表」的一行（03-02 §9.1 接口 37 响应元素的<b>领域侧那一半</b>）。
 *
 * <h2>为什么只有一半</h2>
 * <p>§9.1 的响应行还有 {@code ownerNodeName}（在 {@code org_node}）与
 * {@code validStart} / {@code validEnd}（「<b>我自己持有该资源的有效期</b>」，
 * 在 {@code org_resource_grant}）—— 那两张表都在 {@code org} 域，
 * 三个资源领域读不到、<b>也不该读</b>。它们由模块 11 在拼 VO 时补齐。
 *
 * <p>这条分界与 {@link ResourceOwnerProvider} 的「只回答归属是谁」同源：
 * <b>资源领域只回答关于资源自身的事实</b>。
 */
public class GrantableResourceItem {

    /** {@code source = 1}：自有（{@code owner_node_id} = 我的节点），<b>永久可授出</b>。 */
    public static final int SOURCE_OWNED = 1;

    /** {@code source = 2}：受授权（由上级显式授予），<b>到期后不可再授出</b>。 */
    public static final int SOURCE_GRANTED = 2;

    private Long resourceId;

    /** 展示名：课程名 / 题干摘要 / 视频名。 */
    private String resourceName;

    private Long ownerNodeId;

    /** {@link #SOURCE_OWNED} 或 {@link #SOURCE_GRANTED}。 */
    private int source;

    /**
     * 按资源类型不同的扩展信息（§9.1 响应字段说明 {@code extra} 逐字）：
     * <ul>
     *   <li>课程：{@code subject} / {@code status} / {@code lessonCount} / {@code totalDuration}
     *   <li>题目：{@code questionType} / {@code difficulty} / {@code categoryName} / {@code currentVersion}
     *   <li>视频：{@code duration} / {@code status} / {@code sizeBytes}
     * </ul>
     * <p>用 {@link LinkedHashMap} 保序 —— 响应里字段顺序稳定，便于人肉比对与快照测试。
     */
    private Map<String, Object> extra = new LinkedHashMap<>();

    public GrantableResourceItem put(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public Long getOwnerNodeId() {
        return ownerNodeId;
    }

    public void setOwnerNodeId(Long ownerNodeId) {
        this.ownerNodeId = ownerNodeId;
    }

    public int getSource() {
        return source;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra == null ? new LinkedHashMap<>() : extra;
    }
}
