package com.edumatrix.common.entity;

/**
 * 带租户的实体基类：{@link BaseEntity} + {@code tenant_id}。
 *
 * <p><b>41 张表里 35 张继承它</b>；另外 6 张见 {@link BaseEntity} 类注释里的表格。
 *
 * <h2>不要手写 tenant_id 的值</h2>
 * <p>租户过滤与 INSERT 时的租户列填充<b>全部由租户插件自动完成</b>
 * （{@code common/tenant/PlatformRowTenantLineInnerInterceptor}）。业务代码：
 * <ul>
 *   <li><b>不写</b> {@code entity.setTenantId(...)} —— 插件会填，手写等于给了它一个
 *       覆盖机会（MyBatis-Plus 的 {@code ignoreInsert} 默认行为是：INSERT 语句里
 *       已给出租户列就不再注入）；
 *   <li><b>不写</b> {@code WHERE tenant_id = ?} —— 插件会注入；
 *   <li><b>绝不写</b> {@code OR tenant_id = 0} —— 平台级行的放行逻辑只写在插件里一处
 *       （契约 §2.9 明令），漏一处就是一次零权限或一次越权，且不会报错。
 * </ul>
 *
 * <p>字段留在这里是为了让实体与表逐列对应（05-工程结构.md §F2：实体类名 = 表名的大驼峰，
 * 字段与 {@code 02-数据库设计.md} 字段表逐列对应），<b>不是为了让业务代码去读写它</b>。
 *
 * <h2>无会话入口</h2>
 * <p>定时任务 / 事件消费 / 异步 Worker 没有会话，插件取不到租户。它们必须用
 * {@code TenantHelper.runWithTenant(tenantId, ...)} 包住（契约 §2.8 规则 1），
 * 租户值<b>从被处理的数据行里显式取</b>，不得依赖线程残留。
 */
public abstract class TenantEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 租户（机构）ID。由租户插件自动注入与过滤，业务代码不读不写。 */
    private Long tenantId;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
