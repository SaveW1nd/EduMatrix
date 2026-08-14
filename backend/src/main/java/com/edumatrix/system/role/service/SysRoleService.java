package com.edumatrix.system.role.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.system.menu.service.MenuScopeGuard;
import com.edumatrix.system.role.dto.RoleCreateReq;
import com.edumatrix.system.role.dto.RoleMenuAssignReq;
import com.edumatrix.system.role.dto.RolePageQuery;
import com.edumatrix.system.role.dto.RoleUpdateReq;
import com.edumatrix.system.role.entity.SysRole;
import com.edumatrix.system.role.entity.SysRoleMenu;
import com.edumatrix.system.role.mapper.SysRoleMapper;
import com.edumatrix.system.role.mapper.SysRoleMenuMapper;
import com.edumatrix.system.role.vo.RoleCreatedVO;
import com.edumatrix.system.role.vo.RoleDetailVO;
import com.edumatrix.system.role.vo.RoleListVO;

/**
 * 角色管理（03-01 §3.1~§3.6）—— <b>本模块的核心，写侧收紧全在这一层</b>。
 *
 * <h2>读放宽、写收紧，是同一件事的两半</h2>
 * <p>契约 §2.9 让 {@code sys_role} / {@code sys_role_menu} 的 {@code tenant_id = 0}
 * 四行对所有租户<b>可读</b>（否则全员零权限、系统开箱不可用），但
 * <b>可读不等于可写</b> —— 它们是全平台所有租户共用的同一行。
 * 写侧的三条断言全部委托给 {@link PresetRoleGuard}，防提权委托给 {@link MenuScopeGuard}，
 * 本类<b>不自己判</b>：判定散落就是漏一处的开始。
 *
 * <h2>租户过滤全部交给插件</h2>
 * <p>§3.1 数据权限原文：「org_admin 仅本租户角色 + 平台预置角色（即
 * {@code tenant_id = 本租户 OR tenant_id = 0}，靠契约 §2.9 的插件放行实现，
 * <b>不要在业务代码里手写这个 OR</b>）」。所以本类没有一处 {@code tenant_id} 条件。
 */
@Service
public class SysRoleService {

    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final PresetRoleGuard presetRoleGuard;
    private final MenuScopeGuard menuScopeGuard;

    public SysRoleService(SysRoleMapper sysRoleMapper,
                          SysRoleMenuMapper sysRoleMenuMapper,
                          PresetRoleGuard presetRoleGuard,
                          MenuScopeGuard menuScopeGuard) {
        this.sysRoleMapper = sysRoleMapper;
        this.sysRoleMenuMapper = sysRoleMenuMapper;
        this.presetRoleGuard = presetRoleGuard;
        this.menuScopeGuard = menuScopeGuard;
    }

    // =====================================================================
    // §3.1 分页查询角色
    // =====================================================================

    public PageResult<RoleListVO> page(RolePageQuery query) {
        Page<SysRole> page = new Page<>(
                PageResult.normalizePageNum(query.getPageNum()),
                PageResult.normalizePageSize(query.getPageSize()));

        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<SysRole>()
                .like(hasText(query.getRoleName()), SysRole::getRoleName, query.getRoleName())
                .eq(hasText(query.getRoleKey()), SysRole::getRoleKey, query.getRoleKey())
                .eq(query.getStatus() != null, SysRole::getStatus, query.getStatus())
                .orderByAsc(SysRole::getSort)
                .orderByAsc(SysRole::getId);

        Page<SysRole> result = sysRoleMapper.selectPage(page, wrapper);
        List<RoleListVO> list = result.getRecords().stream().map(SysRoleService::toListVO).toList();
        return PageResult.of(result.getTotal(), list);
    }

    // =====================================================================
    // §3.2 查询角色详情
    // =====================================================================

    /**
     * 角色详情，含 {@code menuIds} 回显。
     *
     * <p>「org_admin 仅本租户角色及平台预置角色，<b>跨租户返回 404</b>」（§3.2 数据权限）——
     * 而这条不需要写判断：租户插件注入的过滤条件让跨租户的 {@code selectById} 直接返回
     * {@code null}，{@link #requireRole} 于是抛 404。<b>不暴露存在性</b>，与三分法一致。
     */
    public RoleDetailVO detail(Long roleId) {
        SysRole role = requireRole(roleId);
        RoleDetailVO vo = new RoleDetailVO();
        vo.setId(role.getId());
        vo.setRoleName(role.getRoleName());
        vo.setRoleKey(role.getRoleKey());
        vo.setStatus(role.getStatus());
        vo.setPreset(role.isPreset());
        vo.setMenuIds(sysRoleMenuMapper.selectMenuIdsByRole(roleId));
        vo.setCreateTime(role.getCreateTime());
        vo.setRemark(role.getRemark());
        return vo;
    }

    // =====================================================================
    // §3.3 创建角色
    // =====================================================================

    /**
     * 创建角色。{@code roleName} / {@code roleKey} 重复返回 400（§3.3 原文）。
     *
     * <p><b>{@code menuIds} 走与 §3.6 完全相同的防提权校验</b> —— §3.3 的参数表明写
     * 「初始菜单权限，<b>等价于创建后调用 3.6</b>」。不共用同一条校验的话，
     * 这里就成了绕过 §3.6 的后门：建角色时把自己没有的菜单塞进去，再把自己挂上那个角色。
     */
    @Transactional(rollbackFor = Exception.class)
    public RoleCreatedVO create(RoleCreateReq req) {
        presetRoleGuard.assertRoleKeyNotPreset(req.getRoleKey());
        assertRoleNameAvailable(req.getRoleName(), null);
        assertRoleKeyAvailable(req.getRoleKey(), null);
        menuScopeGuard.assertAssignable(req.getMenuIds());

        SysRole role = new SysRole();
        role.setRoleName(req.getRoleName());
        role.setRoleKey(req.getRoleKey().trim());
        role.setStatus(req.getStatus() == null ? 0 : req.getStatus());
        role.setSort(0);
        role.setRemark(req.getRemark());
        // tenant_id 不手写：租户插件在 INSERT 时按会话注入（TenantEntity 类注释）。
        // 超管建角色时插件整体放行、不注入 —— 那种场景下建出的是 tenant_id = 0 的
        // 平台级角色，正是"超管扩充平台预置角色"的语义
        sysRoleMapper.insert(role);

        replaceRoleMenus(role.getId(), req.getMenuIds());

        SysRole saved = sysRoleMapper.selectById(role.getId());
        return new RoleCreatedVO(saved.getId(), saved.getRoleName(), saved.getRoleKey(),
                saved.getStatus(), saved.getCreateTime());
    }

    // =====================================================================
    // §3.4 修改角色
    // =====================================================================

    /**
     * 修改角色名称 / 状态 / 备注。
     *
     * <p><b>预置角色对 org_admin 全只读 → 400</b>（{@link PresetRoleGuard#assertWritable}）。
     * 不只是 {@code roleKey}：{@code roleName} 与 {@code status} 同样在列 ——
     * org_admin 停用平台预置的 {@code teacher} 会让全平台所有租户的教师瞬间失权。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long roleId, RoleUpdateReq req) {
        SysRole role = requireRole(roleId);
        presetRoleGuard.assertWritable(role);
        assertRoleNameAvailable(req.getRoleName(), roleId);

        sysRoleMapper.update(null, new LambdaUpdateWrapper<SysRole>()
                .eq(SysRole::getId, roleId)
                .set(SysRole::getRoleName, req.getRoleName())
                .set(SysRole::getStatus, req.getStatus())
                .set(SysRole::getRemark, req.getRemark()));
    }

    // =====================================================================
    // §3.5 删除角色（逻辑删除）
    // =====================================================================

    /**
     * 逻辑删除角色。
     *
     * <p>两道断言，顺序不可换：
     * <ol>
     *   <li><b>预置角色任何人不可删 → 400</b>（含 super_admin，§3.5 原文）；
     *   <li>被用户引用 → {@code 10008}。
     * </ol>
     * 反过来的话，一个没人用的预置角色会先通过引用检查、再被 400 挡住 —— 结果一样，
     * 但日志里会先出现一条"角色未被引用"的判定，误导排查方向。
     * 更重要的是：预置角色<b>一定</b>被引用（四个内置角色是所有账号的绑定目标），
     * 于是超管删预置角色会得到 {@code 10008} 而不是 400 ——
     * 那条提示语说的是"先给相关用户改派角色"，而它<b>根本不该被尝试</b>。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long roleId) {
        SysRole role = requireRole(roleId);
        presetRoleGuard.assertDeletable(role);

        if (sysRoleMapper.countUserBindings(roleId) > 0) {
            throw new BizException(ErrorCode.ROLE_IN_USE);
        }
        // 角色的菜单绑定随角色一并逻辑删除：留着就是指向已删角色的悬挂行，
        // 它不报错，只是 perms 装配时 JOIN 不上 —— 与 §4.4 的 10009 防的是同一类脏数据
        sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, roleId));
        sysRoleMapper.deleteById(roleId);
    }

    // =====================================================================
    // §3.6 为角色分配菜单
    // =====================================================================

    /**
     * 全量覆盖该角色的菜单绑定。
     *
     * <p>两道断言：
     * <ol>
     *   <li><b>预置角色对 org_admin 只读 → 400</b>。§3.6 原文：「本接口尤其不能放开 ——
     *       预置角色的菜单绑定被改写，等于<b>一次性改变全平台所有租户同类用户的功能权限</b>」；
     *   <li><b>防提权</b>：可分配的菜单不得超出自身拥有的集合。
     * </ol>
     *
     * <p>「分配后拥有该角色的在线用户权限<b>即时生效</b>」（§3.6 响应示例标题）——
     * 由 {@code SaTokenPermissionProvider} 不做跨请求缓存保证：下一个请求即是新值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, RoleMenuAssignReq req) {
        SysRole role = requireRole(roleId);
        presetRoleGuard.assertWritable(role);
        menuScopeGuard.assertAssignable(req.getMenuIds());
        replaceRoleMenus(roleId, req.getMenuIds());
    }

    // =====================================================================

    /**
     * 全量覆盖角色的菜单绑定：先逻辑删除既有行，再插入新行。
     *
     * <p><b>先删后插而不是求差集</b>：差集写法要处理"曾经删过又加回来"的行，
     * 而 {@code uk_role_menu(role_id, menu_id, deleted_at)} 的第三列正是为此设计的 ——
     * 逻辑删除写毫秒时间戳，同一 {@code (role_id, menu_id)} 可容纳任意多条已删除行，
     * 所以先删后插永远不会撞唯一键。这是 {@code deleted_at} 用时间戳而非 0/1 的直接收益
     * （{@code BaseEntity} 类注释里逐字记着这一条）。
     */
    private void replaceRoleMenus(Long roleId, List<Long> menuIds) {
        sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds == null || menuIds.isEmpty()) {
            return;
        }
        // 去重：同一个 menuId 在请求里出现两次会撞 uk_role_menu
        Set<Long> distinct = new LinkedHashSet<>(menuIds);
        List<SysRoleMenu> rows = new ArrayList<>(distinct.size());
        for (Long menuId : distinct) {
            rows.add(new SysRoleMenu(roleId, menuId));
        }
        rows.forEach(sysRoleMenuMapper::insert);
    }

    /**
     * 取角色，取不到即 404。
     *
     * <p>跨租户在这里自然收敛成 404：租户插件注入的过滤条件让 {@code selectById}
     * 返回 {@code null}（契约 §2.9 三分法「访问路径上的资源而该资源不在我的范围内 → 404，
     * 不暴露存在性」）。<b>不要为此写 tenant_id 判断</b> —— 写了就有两处真相。
     */
    private SysRole requireRole(Long roleId) {
        SysRole role = roleId == null ? null : sysRoleMapper.selectById(roleId);
        if (role == null) {
            throw BizException.notFound(roleId);
        }
        return role;
    }

    /** {@code roleName} 租户内唯一（§3.3 参数表）。重复 → 400。 */
    private void assertRoleNameAvailable(String roleName, Long excludeRoleId) {
        if (!hasText(roleName)) {
            return;
        }
        Long count = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleName, roleName)
                .ne(excludeRoleId != null, SysRole::getId, excludeRoleId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "roleName 已存在：" + roleName);
        }
    }

    /**
     * {@code roleKey} 租户内唯一（{@code uk_tenant_role_key}）。重复 → 400。
     *
     * <p>注意查询会命中<b>平台预置角色</b>（插件放行了 {@code tenant_id = 0}），
     * 这正是想要的：租户建一个叫 {@code teacher} 的自建角色必须被拒 ——
     * 虽然唯一键带 {@code tenant_id} 拦不住它，但两个同名 {@code role_key}
     * 会让任何按标识判断身份的代码读到不确定的那一行。
     * {@link PresetRoleGuard#assertRoleKeyNotPreset} 是同一条防线的第二道。
     */
    private void assertRoleKeyAvailable(String roleKey, Long excludeRoleId) {
        if (!hasText(roleKey)) {
            return;
        }
        Long count = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, roleKey.trim())
                .ne(excludeRoleId != null, SysRole::getId, excludeRoleId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "roleKey 已存在：" + roleKey);
        }
    }

    private static RoleListVO toListVO(SysRole role) {
        RoleListVO vo = new RoleListVO();
        vo.setId(role.getId());
        vo.setRoleName(role.getRoleName());
        vo.setRoleKey(role.getRoleKey());
        vo.setStatus(role.getStatus());
        vo.setPreset(role.isPreset());
        vo.setCreateTime(role.getCreateTime());
        vo.setRemark(role.getRemark());
        return vo;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
