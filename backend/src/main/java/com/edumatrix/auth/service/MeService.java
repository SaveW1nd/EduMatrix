package com.edumatrix.auth.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.edumatrix.auth.entity.AuthUser;
import com.edumatrix.auth.mapper.AuthOrgMapper;
import com.edumatrix.auth.mapper.AuthPermMapper;
import com.edumatrix.auth.mapper.AuthUserMapper;
import com.edumatrix.auth.session.LoginHelper;
import com.edumatrix.auth.vo.MeVO;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodeAncestorCache;
import com.edumatrix.common.subtree.NodePath;

/**
 * {@code GET /auth/me}（03-01 §1.5）。
 *
 * <h2>这个接口有一个「不报错的故障」形态，必须知道</h2>
 * <p>{@code roles} / {@code perms} 走 {@code sys_user_role → sys_role → sys_role_menu → sys_menu}，
 * 而四个内置角色及其菜单绑定的 {@code tenant_id = 0}。若那两张表的租户插件注入条件
 * 不是 {@code (tenant_id = ? OR tenant_id = 0)}，<b>非超管用户一律返回空数组</b> ——
 * 接口 200、字段齐全，只是数组是空的，前端按钮全隐、后端权限校验全 403，
 * 系统开箱即不可用（契约 §2.9 / 03-01 §1.5）。
 *
 * <p><b>查出来是空的时候该去哪</b>：{@code common/tenant/PlatformRowTenantLineInnerInterceptor}。
 * 放行逻辑只写在那一处，<b>不要在本类里手写 {@code OR tenant_id = 0} 绕过</b>
 * （{@code scripts/check_backend_conventions.sh} 的检查①会 grep 出来）。
 *
 * <h2>学生的 perms 为空是设计意图</h2>
 * <p>F-1 定案②：{@code student} 不绑任何菜单行，学生端接口一律不加
 * {@code @SaCheckPermission}，只按角色与数据权限判定。
 * 与上面那个故障的区分方法：<b>看 {@code roles}</b> —— 里面有 {@code student} 这一行，
 * 就说明平台级放行是生效的，空的只是 {@code perms}。
 */
@Service
public class MeService {

    private final AuthUserMapper authUserMapper;
    private final AuthOrgMapper authOrgMapper;
    private final AuthPermMapper authPermMapper;
    private final NodeAncestorCache nodeAncestorCache;

    public MeService(AuthUserMapper authUserMapper,
                     AuthOrgMapper authOrgMapper,
                     AuthPermMapper authPermMapper,
                     NodeAncestorCache nodeAncestorCache) {
        this.authUserMapper = authUserMapper;
        this.authOrgMapper = authOrgMapper;
        this.authPermMapper = authPermMapper;
        this.nodeAncestorCache = nodeAncestorCache;
    }

    public MeVO currentUser() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 本接口必然有会话，租户由插件按会话注入 —— 不需要 ignore()。
        // 超管则走「租户插件对超管整体放行」那条通道（契约 §2.9），同样不需要 ignore()
        AuthUser user = authUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        AuthOrgMapper.NodeNameRow node = authOrgMapper.selectNodeName(user.getNodeId());
        NodePathView pathView = resolveNodePath(user);
        MeVO.TenantVO tenant = resolveTenant(user);

        List<MeVO.RoleVO> roles = authPermMapper.selectRoles(userId).stream()
                .map(r -> new MeVO.RoleVO(r.getRoleId(), r.getRoleName(), r.getRoleKey()))
                .collect(Collectors.toList());
        List<String> perms = authPermMapper.selectPerms(userId);

        return new MeVO(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getUserType(),
                user.getPhone(),
                user.getAvatar(),
                user.getStatus(),
                user.needChangePassword(),
                user.getLastLoginTime(),
                user.getNodeId(),
                node == null ? null : node.getNodeName(),
                // §1.5 字段说明：平台超管的 nodeType 为 null
                user.isSuperAdmin() ? null : (node == null ? null : node.getNodeType()),
                pathView.path(),
                pathView.ids(),
                tenant,
                roles,
                perms);
    }

    /**
     * 面包屑：<b>自机构根节点起</b>、以 {@code /} 拼接至本节点（§1.5）。
     *
     * <p>{@code nodePathIds} 与 {@code nodePath} <b>同序且不含虚拟根 {@code 0}</b> ——
     * {@link NodePath#parseAncestorIds} 已经跳过了那个哨兵。平台根不属于任何租户，
     * 出现在租户的面包屑里反而是越界（契约 §2.9）。
     *
     * <p>平台超管取空串与空数组：他不属于任何机构，「自机构根节点起」对他不成立。
     */
    private NodePathView resolveNodePath(AuthUser user) {
        if (user.isSuperAdmin() || user.getNodeId() == null) {
            return new NodePathView("", List.of());
        }
        List<Long> ids = new ArrayList<>(NodePath.parseAncestorIds(nodeAncestorCache.get(user.getNodeId())));
        ids.add(user.getNodeId());

        List<AuthOrgMapper.NodeNameRow> rows = authOrgMapper.selectNodeNames(ids);
        Map<Long, String> nameById = new LinkedHashMap<>();
        for (AuthOrgMapper.NodeNameRow row : rows) {
            nameById.put(row.getId(), row.getNodeName());
        }
        // 按 ids 的顺序拼，而不是按查询返回的顺序 —— IN 查询不保证顺序，
        // 而面包屑的顺序就是它的全部意义
        String path = ids.stream()
                .map(nameById::get)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining("/"));
        return new NodePathView(path, ids);
    }

    /** 平台超管（{@code userType = 0}）的 {@code tenant} 为 {@code null}（§1.5 字段说明）。 */
    private MeVO.TenantVO resolveTenant(AuthUser user) {
        if (user.isSuperAdmin()) {
            return null;
        }
        AuthOrgMapper.TenantRow row = authOrgMapper.selectTenant(user.getTenantId());
        if (row == null) {
            return null;
        }
        return new MeVO.TenantVO(row.getId(), row.getName(), row.getRootNodeId(), row.getExpireTime());
    }

    private record NodePathView(String path, List<Long> ids) {
    }
}
