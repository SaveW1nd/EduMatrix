package com.edumatrix.auth.session;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.edumatrix.auth.mapper.AuthPermMapper;

import cn.dev33.satoken.stp.StpInterface;

/**
 * Sa-Token 的权限数据源 —— {@code @SaCheckPermission} / {@code @SaCheckRole} 的取值口。
 *
 * <h2>没有它，全系统的 {@code @SaCheckPermission} 从来没生效过</h2>
 * <p>Sa-Token 校验注解时调 {@link StpInterface#getPermissionList}，而该接口<b>没有默认实现</b>：
 * 容器里找不到实现 Bean 时返回<b>空集合</b>，于是每一个带注解的接口一律 403。
 * 这不是"权限收得太严"，是<b>权限体系整体未接线</b> —— 表现与契约 §2.9 那个
 * 「每个非超管用户零权限」一模一样，排查时极易混淆：
 * {@code /auth/me} 的 {@code perms} 明明有 94 条，业务接口却全 403。
 *
 * <p><b>为什么落在 {@code auth/} 而不是 {@code system/}</b>：本类是
 * {@code sys_user_role → sys_role → sys_role_menu → sys_menu.perms} 那条装配链路的
 * <b>第二个消费方</b>（第一个是 {@code MeService}），与它共用 {@link AuthPermMapper}。
 * 放进 {@code system/} 就要 {@code import com.edumatrix.auth.mapper.*}，
 * 撞 05-工程结构.md §A1 的第三条硬约束（{@code scripts/check_backend_conventions.sh} 检查③）。
 * 而 Sa-Token 是<b>按 Bean 类型自动发现</b>本类的 —— {@code system} 侧只写注解，
 * 一行 import 都不需要，跨领域依赖为零。
 *
 * <h2>不做跨请求缓存，只做请求内记忆化</h2>
 * <p>03-01 §3.6 承诺「分配后拥有该角色的在线用户权限<b>即时生效</b>」。任何跨请求缓存
 * 都要为此配一套失效机制（失效点是 §3.6 分配菜单与 §2.3 改用户角色两处），
 * 而失效漏一处的表现是「改了权限但那个人还是进得去」—— 不报错的安全缺陷。
 * 所以<b>不跨请求缓存</b>。
 *
 * <p>但 Sa-Token 是<b>每遇到一个注解就调一次</b>本方法的，不做任何处理就是每请求多次
 * 相同的 JOIN 查询。因此按<b>请求</b>记忆化：结果存进
 * {@link RequestContextHolder} 的请求属性，随请求结束自然消亡。
 * 「即时生效」一分不打折 —— 权限变更后的<b>下一个请求</b>就是新值。
 *
 * <p><b>为什么用请求属性而不是 {@code ThreadLocal}</b>：ThreadLocal 要配一个
 * {@code finally} 清理点（{@code TraceIdFilter} 那样的过滤器），漏清就是线程池复用时
 * 把上一个用户的权限带给下一个 —— 而那是越权，不是性能问题。请求属性没有这个失败模式。
 *
 * <p><b>待观察，现在不做</b>：若管理端出现慢查询，再引入带失效的跨请求缓存，
 * 失效点是 03-01 §3.6（为角色分配菜单）与 §2.3（修改用户角色）两处。
 */
@Component
public class SaTokenPermissionProvider implements StpInterface {

    /** 请求属性键：本次请求已装配的 perms。 */
    private static final String ATTR_PERMS = SaTokenPermissionProvider.class.getName() + ".perms";
    /** 请求属性键：本次请求已装配的 roleKeys。 */
    private static final String ATTR_ROLES = SaTokenPermissionProvider.class.getName() + ".roles";

    private final AuthPermMapper authPermMapper;

    public SaTokenPermissionProvider(AuthPermMapper authPermMapper) {
        this.authPermMapper = authPermMapper;
    }

    /**
     * 权限标识列表（{@code sys_menu.perms}）。
     *
     * <p>与 {@code /auth/me} 的 {@code perms} <b>同一条查询、同一个结果</b> ——
     * 前端按 {@code perms} 显隐按钮，后端按本方法放行接口，两者必须同源，
     * 否则会出现「按钮在但点了 403」或「按钮没了但接口开着」。
     *
     * <p><b>学生恒为空数组是设计意图，不是故障</b>：04-实施计划.md §E 的
     * <b>F-1 定案②</b> —— 「{@code student} 不绑任何菜单行；学生端接口一律不加
     * {@code @SaCheckPermission}，只按角色（{@code sys_user.user_type}）与数据权限判定」。
     * 该定案是模块 02 <b>回原文逐条核对后</b>得出的实现口径（依据见 F-1②），
     * 因此空数组不会挡住任何学生功能。
     *
     * <p>与「放行失效」那个故障的区分方法：<b>看 {@code roles}</b> —— 里面查得到
     * {@code student} 这一行，就说明 {@code tenant_id = 0} 的平台级放行是通的
     * （与 {@code AuthPermMapper} / {@code MeService} 记的是同一条判别法）。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = toUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        return memoize(ATTR_PERMS, userId, () -> authPermMapper.selectPerms(userId));
    }

    /**
     * 角色标识列表（{@code sys_role.role_key}，契约 §3 的四个值 + 租户自建角色）。
     *
     * <p>本模块的接口一律用 {@code @SaCheckPermission} 而不是 {@code @SaCheckRole} ——
     * 契约 §3.1 把 {@code perms} 定为「线上鉴权依据」，角色只是 perms 的来源。
     * 但 {@code StpInterface} 的两个方法必须成对实现（只实现一个，另一个会走到
     * 接口的抽象方法上），且租户自建角色（如"教务主任"）的存在意味着
     * <b>按 role_key 硬编码判断是错的</b>，本方法只供确需按角色分支的场景使用。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = toUserId(loginId);
        if (userId == null) {
            return Collections.emptyList();
        }
        return memoize(ATTR_ROLES, userId, () -> authPermMapper.selectRoles(userId).stream()
                .map(AuthPermMapper.RoleRow::getRoleKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(Collectors.toList()));
    }

    // =====================================================================

    /**
     * 请求内记忆化。无请求上下文时（单测、异步线程）<b>直接查</b>，不退化成缓存。
     *
     * <p>键里带 {@code userId}：同一次请求内 {@code loginId} 不会变，但带上它可以让
     * 「万一变了」表现为一次多余的查询，而不是<b>把 A 的权限当成 B 的</b>。
     */
    private List<String> memoize(String attributeKey, Long userId,
                                 java.util.function.Supplier<List<String>> loader) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return loader.get();
        }
        String key = attributeKey + ":" + userId;
        Object cached = attributes.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<String> typed = (List<String>) list;
            return typed;
        }
        List<String> loaded = loader.get();
        List<String> result = loaded == null ? Collections.emptyList() : loaded;
        attributes.setAttribute(key, result, RequestAttributes.SCOPE_REQUEST);
        return result;
    }

    private static Long toUserId(Object loginId) {
        if (loginId == null) {
            return null;
        }
        try {
            return Long.valueOf(loginId.toString());
        } catch (NumberFormatException e) {
            // loginId 恒为 sys_user.id（TokenService#openSession 里 StpUtil.login(user.getId())）。
            // 走到这里说明会话被人工构造过，按"无权限"处理而不是让它变成 500
            return null;
        }
    }
}
