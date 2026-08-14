package com.edumatrix.auth.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前用户信息（03-01 §1.5，字段与顺序逐字对齐）。
 *
 * <h2>{@code nodeId} 是前后端共同的数据权限锚点</h2>
 * <p>所有列表接口的可见范围 = 以该节点为根的子树（契约 §2.4）。
 * <b>本响应不返回 {@code dataScope}，也不返回任何群体归属类字段</b>（§1.5 末尾明写）——
 * 数据范围由树决定，不在角色上配置。
 *
 * <h2>平台超管的三个空值</h2>
 * <p>{@code nodeId = "0"}（树根标识）、{@code nodeType = null}、{@code tenant = null}
 * 是 §1.5 字段说明写死的。{@code nodePath} / {@code nodePathIds} 文档未定义，
 * 取<b>空串与空数组</b>而不是 null：面包屑的口径是「自机构根节点起」，
 * 而超管不属于任何机构 —— 空是它的真实状态，且前端可以直接渲染成「无」，
 * 不必为这一个字段多写一处判空。
 *
 * @param roles 用户全部角色；<b>学生的 {@code perms} 为空数组是设计意图</b>
 *              （F-1 定案②：学生端接口一律不加 {@code @SaCheckPermission}），
 *              而 {@code roles} 里查得到 {@code student} 这一行，正说明
 *              {@code tenant_id = 0} 的平台级放行是生效的 —— 两者一起看才能区分
 *              「设计如此」与「租户插件失效导致的全员零权限」
 */
public record MeVO(Long userId,
                   String username,
                   String realName,
                   Integer userType,
                   String phone,
                   String avatar,
                   Integer status,
                   boolean needChangePassword,
                   LocalDateTime lastLoginTime,
                   Long nodeId,
                   String nodeName,
                   Integer nodeType,
                   String nodePath,
                   List<Long> nodePathIds,
                   TenantVO tenant,
                   List<RoleVO> roles,
                   List<String> perms) {

    /**
     * 所属租户（机构）。平台超管为 {@code null}。
     *
     * @param rootNodeId 机构根节点；契约 §2.1：其值与 {@code tenantId} 相同
     */
    public record TenantVO(Long tenantId, String name, Long rootNodeId, LocalDateTime expireTime) {
    }

    /**
     * 角色。{@code roleKey} 对齐契约 §3 的四个值，<b>不含 {@code dataScope}</b>。
     */
    public record RoleVO(Long roleId, String roleName, String roleKey) {
    }
}
