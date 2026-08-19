package com.edumatrix.common.resource;

import java.util.Optional;

/**
 * 受管资源类型（契约 §2.5「受管资源」+ §5 核心枚举 {@code resource_type}）。
 *
 * <p><b>穷举三种</b>：契约 §2.5 逐字「受管资源：课程（{@code crs_course}）、
 * 题目（{@code qb_question}）、视频（{@code vod_video}）」。
 * {@code crs_material}（图文资料）<b>不在其中</b> —— 它不进 {@code org_resource_grant}，
 * 学生端可见性走「所属课时 → 课程 → 课程授权」（03-03 §4.1 权限栏）。
 *
 * <p>取值与 {@code org_resource_grant.resource_type} 的 DDL 注释逐字一致，
 * 不得另造（契约 §5「文档中不得另造值」）。
 */
public enum ResourceType {

    /** 课程 {@code crs_course}。模块 08 注册 owner 提供方。 */
    COURSE(1),

    /** 题目 {@code qb_question}。模块 10 注册 owner 提供方。 */
    QUESTION(2),

    /** 视频 {@code vod_video}。模块 09 注册 owner 提供方。 */
    VIDEO(3);

    private final int code;

    ResourceType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Optional<ResourceType> of(Integer code) {
        if (code == null) {
            return Optional.empty();
        }
        for (ResourceType type : values()) {
            if (type.code == code) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
