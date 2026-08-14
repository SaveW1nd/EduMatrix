package com.edumatrix.system.menu.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.menu.mapper.SysMenuMapper;

/**
 * <b>防提权的唯一落点</b>：一个 {@code org_admin} 能分配出去的菜单，
 * 不得超出他<b>自己拥有</b>的菜单集合（03-01 §3.3 / §3.6）。
 *
 * <h2>为什么收敛到一个类</h2>
 * <p>它有三个调用点 —— §3.3 创建角色时的 {@code menuIds}、§3.6 为角色分配菜单、
 * §4.1 菜单树对 org_admin 的裁剪。三处各写一份的话，<b>加第四个入口时必然漏一处</b>，
 * 而漏了不报错：表现是某个租户的管理员给自己造了一个"超级教务主任"角色，
 * 拿到了他本人都没有的菜单，然后把自己挂上去。这类缺陷在被人利用之前完全无声。
 *
 * <h2>超管不受本类约束</h2>
 * <p>03-01 §4.1：「super_admin 返回全量菜单树」；§3.3/§3.6 的防提权条款只写给 org_admin。
 * 超管本就绑定了全部平台级菜单，对他做"不得超出自身"的判定要么恒真、要么在
 * 菜单初始化数据有遗漏时把超管自己锁死 —— 后者是把一个数据问题升级成功能不可用。
 *
 * <h2>越权菜单返回 400，不是 403</h2>
 * <p>03-01 §3.3 / §3.6 的原文逐字是「menuIds 含无效 ID 或<b>越权菜单</b>返回 400」。
 * 它与越界三分法不冲突：调用者<b>有</b> {@code system:role:assignMenu} 这个功能权限
 * （403 管的是这一层），被拒的是<b>请求体里的某个值不可用</b> —— 那是参数层面的拒绝。
 */
@Service
public class MenuScopeGuard {

    private static final Logger log = LoggerFactory.getLogger(MenuScopeGuard.class);

    private final SysMenuMapper sysMenuMapper;

    public MenuScopeGuard(SysMenuMapper sysMenuMapper) {
        this.sysMenuMapper = sysMenuMapper;
    }

    /**
     * 当前登录人拥有的菜单 ID 集合。<b>超管返回 {@code null} 表示「不设上界」</b>，
     * 而不是空集 —— 空集会被误读成「他什么都没有」。
     */
    public Set<Long> ownedMenuIdsOrUnbounded() {
        if (TenantHelper.isSuperAdminSession()) {
            return null;
        }
        Long userId = TenantHelper.getUserId();
        if (userId == null) {
            // 走到这里说明鉴权拦截器被绕过了。返回空集 = 什么都分配不了，
            // 与 SubtreeScopeHelper「拿不到我在树上的位置就返回空集而非放行」同向
            log.error("防提权判定取不到当前登录人。按空集处理（什么都不可分配），绝不退化为不设上界");
            return Set.of();
        }
        return new HashSet<>(sysMenuMapper.selectOwnedMenuIds(userId));
    }

    /**
     * 断言这批 {@code menuIds} 全部有效、且全部在调用者自身的菜单集合内。
     *
     * <p>两道校验都通向 400（§3.3 / §3.6 原文）：
     * <ol>
     *   <li><b>存在性</b> —— 传了一个不存在或已删除的菜单 ID；
     *   <li><b>防提权</b> —— 传了一个自己没有的菜单 ID（仅对非超管）。
     * </ol>
     *
     * <p>空数组是合法输入：§3.6 明写「传 {@code []} 表示清空该角色全部菜单」。
     */
    public void assertAssignable(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new HashSet<>(menuIds);
        long existing = sysMenuMapper.countExisting(List.copyOf(distinct));
        if (existing != distinct.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "menuIds 含不存在或已删除的菜单");
        }

        Set<Long> owned = ownedMenuIdsOrUnbounded();
        if (owned == null) {
            // 超管：不设上界（03-01 §4.1「super_admin 返回全量菜单树」）
            return;
        }
        for (Long menuId : distinct) {
            if (!owned.contains(menuId)) {
                // 契约 §7.1：越权拒绝要带得出「他在探什么」，但响应体不回明细
                log.warn("防提权拦截：userId={} 尝试分配自身没有的菜单 menuId={}（03-01 §3.6）",
                        TenantHelper.getUserId(), menuId);
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "menuIds 超出您自身拥有的菜单范围，不可分配");
            }
        }
    }

    /**
     * 单个菜单是否在调用者的可见/可分配范围内（供 §4.1 菜单树裁剪使用）。
     *
     * <p>{@code owned == null} 即上界不设（超管），一律可见 —— 与
     * {@link #ownedMenuIdsOrUnbounded} 的返回约定保持一致。
     */
    public boolean isVisibleTo(Set<Long> owned, Long menuId) {
        return owned == null || (menuId != null && owned.contains(menuId));
    }
}
