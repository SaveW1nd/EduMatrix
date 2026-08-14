package com.edumatrix.system.menu.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.system.menu.dto.MenuCreateReq;
import com.edumatrix.system.menu.dto.MenuUpdateReq;
import com.edumatrix.system.menu.entity.SysMenu;
import com.edumatrix.system.menu.mapper.SysMenuMapper;
import com.edumatrix.system.menu.vo.MenuCreatedVO;
import com.edumatrix.system.menu.vo.MenuTreeVO;

/**
 * 菜单管理（03-01 §4.1~§4.4）。
 *
 * <p><b>菜单是平台级数据</b>（{@code sys_menu} 无 {@code tenant_id}，契约 §2.1）：
 * 由平台超管统一维护菜单结构与权限标识，各租户通过角色-菜单关联获得可用子集。
 * §4.2/§4.3/§4.4 因此是<b>平台级维护接口，仅 {@code super_admin}</b>，
 * org_admin 及以下调用返回 403（由 Controller 上的 {@code @SaCheckPermission} 承担 ——
 * {@code system:menu:add/edit/remove} 三个标识在菜单初始化数据里只绑了 super_admin）。
 *
 * <p>只有 §4.1 查询菜单树对 org_admin 开放，且<b>只返回他自身权限范围内的子树</b>
 * （防提权，见 {@link MenuScopeGuard}）—— 那个树是"给角色分配菜单"弹窗的数据源，
 * 若把全量菜单给了 org_admin，他就能在界面上看到并勾选自己没有的菜单。
 */
@Service
public class SysMenuService {

    private final SysMenuMapper sysMenuMapper;
    private final MenuScopeGuard menuScopeGuard;

    public SysMenuService(SysMenuMapper sysMenuMapper, MenuScopeGuard menuScopeGuard) {
        this.sysMenuMapper = sysMenuMapper;
        this.menuScopeGuard = menuScopeGuard;
    }

    // =====================================================================
    // §4.1 查询菜单树
    // =====================================================================

    /**
     * 菜单树（非分页）。
     *
     * @param menuName 名称模糊匹配，<b>命中节点保留其祖先链</b>（§4.1 参数表原文）——
     *                 否则命中一个按钮却看不到它挂在哪个菜单下
     * @param visible  0 隐藏 1 显示；不传则不筛
     */
    public List<MenuTreeVO> tree(String menuName, Integer visible) {
        List<SysMenu> all = sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort)
                .orderByAsc(SysMenu::getId));

        // 防提权裁剪：超管得到 null（不设上界），org_admin 得到自身拥有的菜单集合
        Set<Long> owned = menuScopeGuard.ownedMenuIdsOrUnbounded();
        List<SysMenu> visibleToMe = all.stream()
                .filter(m -> menuScopeGuard.isVisibleTo(owned, m.getId()))
                .toList();

        Set<Long> kept = resolveKeptIds(visibleToMe, menuName, visible);
        return buildTree(visibleToMe, kept);
    }

    /**
     * 解析筛选后应保留的节点集合：命中节点 <b>+ 其全部祖先</b>。
     *
     * <p>不筛时返回全集。祖先补齐是 §4.1 那句「命中节点保留其祖先链」的落点 ——
     * 少了它，搜"重置密码"会得到一个空树（那个按钮的父节点没命中，
     * 于是按 parent 拼树时它找不到入口，<b>接口 200、数据齐全、界面空白</b>）。
     */
    private Set<Long> resolveKeptIds(List<SysMenu> menus, String menuName, Integer visible) {
        boolean filterByName = menuName != null && !menuName.isBlank();
        if (!filterByName && visible == null) {
            Set<Long> allIds = new HashSet<>(menus.size());
            menus.forEach(m -> allIds.add(m.getId()));
            return allIds;
        }

        Map<Long, SysMenu> byId = new LinkedHashMap<>();
        menus.forEach(m -> byId.put(m.getId(), m));

        Set<Long> kept = new HashSet<>();
        for (SysMenu menu : menus) {
            boolean nameHit = !filterByName
                    || (menu.getMenuName() != null && menu.getMenuName().contains(menuName));
            boolean visibleHit = visible == null || visible.equals(menu.getVisible());
            if (!nameHit || !visibleHit) {
                continue;
            }
            // 命中：自身 + 沿 parent_id 上溯的整条祖先链
            Long cursor = menu.getId();
            while (cursor != null && kept.add(cursor)) {
                SysMenu current = byId.get(cursor);
                cursor = current == null ? null : current.getParentId();
            }
        }
        return kept;
    }

    /**
     * 按 {@code parent_id} 拼树。
     *
     * <p><b>孤儿节点（父不在保留集合里）一律丢弃，不提到根上</b>：把它提到根，
     * 界面上会出现一个孤零零的"重置密码"按钮，看起来像顶级菜单。
     */
    private List<MenuTreeVO> buildTree(List<SysMenu> menus, Set<Long> kept) {
        Map<Long, MenuTreeVO> nodes = new LinkedHashMap<>();
        for (SysMenu menu : menus) {
            if (kept.contains(menu.getId())) {
                nodes.put(menu.getId(), toTreeVO(menu));
            }
        }
        List<MenuTreeVO> roots = new ArrayList<>();
        for (SysMenu menu : menus) {
            MenuTreeVO node = nodes.get(menu.getId());
            if (node == null) {
                continue;
            }
            MenuTreeVO parent = nodes.get(menu.getParentId());
            if (parent != null) {
                parent.getChildren().add(node);
            } else if (menu.getParentId() != null
                    && menu.getParentId() == SysMenu.ROOT_PARENT_ID) {
                roots.add(node);
            }
            // else：父节点存在但被裁掉了 —— 丢弃，见方法注释
        }
        return roots;
    }

    private MenuTreeVO toTreeVO(SysMenu menu) {
        MenuTreeVO vo = new MenuTreeVO();
        vo.setId(menu.getId());
        vo.setParentId(menu.getParentId());
        vo.setMenuName(menu.getMenuName());
        vo.setMenuType(menu.getMenuType());
        vo.setPerms(menu.getPerms());
        vo.setPath(menu.getPath());
        vo.setVisible(menu.getVisible());
        vo.setSort(menu.getSort());
        return vo;
    }

    // =====================================================================
    // §4.2 创建菜单
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public MenuCreatedVO create(MenuCreateReq req) {
        assertTypeConsistent(req.getMenuType(), req.getPerms(), req.getPath());
        assertParentAcceptsChild(req.getParentId());
        assertPermsUnique(req.getPerms(), null);

        SysMenu menu = new SysMenu();
        menu.setParentId(req.getParentId());
        menu.setMenuName(req.getMenuName());
        menu.setMenuType(req.getMenuType());
        menu.setPerms(blankToNull(req.getPerms()));
        menu.setPath(blankToNull(req.getPath()));
        menu.setIcon(blankToNull(req.getIcon()));
        // 对齐 DDL 默认值：visible DEFAULT 1、sort DEFAULT 0、status DEFAULT 0
        menu.setVisible(req.getVisible() == null ? 1 : req.getVisible());
        menu.setSort(req.getSort() == null ? 0 : req.getSort());
        menu.setStatus(0);
        sysMenuMapper.insert(menu);

        SysMenu saved = sysMenuMapper.selectById(menu.getId());
        return new MenuCreatedVO(saved.getId(), saved.getParentId(), saved.getMenuName(),
                saved.getMenuType(), saved.getPerms(), saved.getCreateTime());
    }

    // =====================================================================
    // §4.3 修改菜单
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public void update(Long menuId, MenuUpdateReq req) {
        SysMenu existing = requireMenu(menuId);
        // menuType 创建后不可修改（§4.3），所以按既有类型校验 perms/path 的搭配
        assertTypeConsistent(existing.getMenuType(), req.getPerms(), req.getPath());
        assertPermsUnique(req.getPerms(), menuId);

        if (!existing.getParentId().equals(req.getParentId())) {
            assertParentAcceptsChild(req.getParentId());
            assertNotMovingIntoOwnSubtree(menuId, req.getParentId());
        }

        // 走 LambdaUpdateWrapper 而不是 updateById(entity)：后者<b>只更新非 null 字段</b>，
        // 于是「把一个按钮的 perms 清空」这种改动会被静默忽略 —— 接口 200，值没变。
        // menuType / status 不在 set 列表里：前者 §4.3 明写创建后不可修改，后者不在参数表里
        sysMenuMapper.update(null, new LambdaUpdateWrapper<SysMenu>()
                .eq(SysMenu::getId, menuId)
                .set(SysMenu::getParentId, req.getParentId())
                .set(SysMenu::getMenuName, req.getMenuName())
                .set(SysMenu::getPerms, blankToNull(req.getPerms()))
                .set(SysMenu::getPath, blankToNull(req.getPath()))
                .set(SysMenu::getIcon, blankToNull(req.getIcon()))
                .set(SysMenu::getVisible, req.getVisible())
                .set(SysMenu::getSort, req.getSort()));
    }

    // =====================================================================
    // §4.4 删除菜单（逻辑删除）
    // =====================================================================

    /**
     * 逻辑删除。存在子节点或已被角色引用 → {@code 10009}。
     *
     * <p>两个判据缺一不可（§9.2 该码的场景是「删除菜单时存在子菜单，<b>或</b>
     * {@code sys_role_menu} 有关联」）：只判子节点的话，删掉一个仍被角色绑定的按钮，
     * {@code sys_role_menu} 里会留下指向已删菜单的悬挂绑定 —— 它不报错，
     * 只是 {@code perms} 装配时那一行 JOIN 不上，表现为某个角色悄悄少了一个按钮。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long menuId) {
        requireMenu(menuId);
        if (sysMenuMapper.countChildren(menuId) > 0 || sysMenuMapper.countRoleBindings(menuId) > 0) {
            throw new BizException(ErrorCode.MENU_IN_USE);
        }
        sysMenuMapper.deleteById(menuId);
    }

    // =====================================================================
    // 校验
    // =====================================================================

    private SysMenu requireMenu(Long menuId) {
        SysMenu menu = menuId == null ? null : sysMenuMapper.selectById(menuId);
        if (menu == null) {
            throw BizException.notFound(menuId);
        }
        return menu;
    }

    /**
     * 类型与 {@code perms} / {@code path} 的搭配（§4.2 参数表）。
     *
     * <p>F 按钮：{@code perms} 必填、{@code path} 为空；M/C：{@code path} 必填。
     * 两条都返回 400（§4.2 原文「perms 重复、层级不合法……返回 400」）。
     */
    private void assertTypeConsistent(String menuType, String perms, String path) {
        if (SysMenu.TYPE_BUTTON.equals(menuType)) {
            if (blankToNull(perms) == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "menuType=F 时 perms 必填");
            }
            return;
        }
        if (blankToNull(path) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "menuType=" + menuType + " 时 path 必填");
        }
    }

    /**
     * 父节点必须能承载子节点 —— <b>按钮下不可挂任何东西</b>（§4.2「层级不合法——如按钮下挂子节点」）。
     */
    private void assertParentAcceptsChild(Long parentId) {
        if (parentId == null || parentId == SysMenu.ROOT_PARENT_ID) {
            return;
        }
        SysMenu parent = sysMenuMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "parentId 对应的菜单不存在");
        }
        if (parent.isButton()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "按钮（menuType=F）下不可挂子节点");
        }
    }

    /**
     * {@code perms} 全局唯一（{@code uk_perms(perms, deleted_at)}，F-12 定案）。
     *
     * <p><b>先查一次是为了给出可读的 400，不是为了替代唯一索引</b>：并发下仍可能双双通过
     * 本判定，最终由唯一索引兜住（那时抛 {@code DuplicateKeyException} → 500）。
     * 索引是真相，本方法是提示。
     */
    private void assertPermsUnique(String perms, Long excludeMenuId) {
        String value = blankToNull(perms);
        if (value == null) {
            return;
        }
        Long duplicated = sysMenuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getPerms, value)
                .ne(excludeMenuId != null, SysMenu::getId, excludeMenuId));
        if (duplicated != null && duplicated > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "perms 已存在：" + value);
        }
    }

    /**
     * 不可移动到自身或自身后代之下（§4.3 参数表）。
     *
     * <p>不做则形成环：按 {@code idx_parent_id} 拼树时那一支要么无限展开、
     * 要么整棵子树从树上消失（找不到 {@code parent_id = 0} 的入口），
     * 而 {@code perms} 又确实返回了 —— 菜单初始化脚本的头注释里记过同一类故障。
     */
    private void assertNotMovingIntoOwnSubtree(Long menuId, Long newParentId) {
        Long cursor = newParentId;
        int depth = 0;
        while (cursor != null && cursor != SysMenu.ROOT_PARENT_ID) {
            if (cursor.equals(menuId)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不可移动到自身或自身后代之下");
            }
            if (++depth > 64) {
                // 库里已经有环了。抛而不是死循环 —— 让它可见
                throw new BizException(ErrorCode.BAD_REQUEST, "菜单层级异常（疑似成环），请联系管理员");
            }
            SysMenu parent = sysMenuMapper.selectById(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
