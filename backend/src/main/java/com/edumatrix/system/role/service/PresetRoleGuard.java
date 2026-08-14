package com.edumatrix.system.role.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.role.entity.SysRole;

/**
 * <b>写侧收紧的唯一落点</b> —— 预置角色（{@code tenant_id = 0}）能被谁改、谁都不能删。
 *
 * <h2>这条规则的由来：放宽的只是读</h2>
 * <p>契约 §2.9 把 {@code sys_role} / {@code sys_role_menu} 的租户过滤放宽为
 * {@code (tenant_id = ? OR tenant_id = 0)}，理由是<b>让租户用户读得到自己的权限定义</b>
 * ——否则全员零权限、系统开箱不可用。但那四行是<b>全平台所有租户共用的同一行</b>：
 * 租户 A 的管理员改一个字段，租户 B 立刻跟着变。
 *
 * <p>契约 §2.9 末段与 03-01 §3 导语讲的是同一件事：
 * <b>「放行解决的是『读不到自己的权限定义』，不是『可以改别人的』，两件事必须分别落实。」</b>
 * 读侧那一半由 {@code common/tenant/PlatformRowTenantLineInnerInterceptor} 落实，
 * 写侧这一半就是本类。
 *
 * <h2>判定矩阵（03-01 §3.4 / §3.5 / §3.6 逐条）</h2>
 * <table border="1">
 *   <caption>谁能对什么做什么</caption>
 *   <tr><th>调用者</th><th>目标</th><th>§3.4 改</th><th>§3.5 删</th><th>§3.6 分配菜单</th></tr>
 *   <tr><td>{@code org_admin}</td><td>预置（{@code tenant_id=0}）</td>
 *       <td><b>400</b></td><td><b>400</b></td><td><b>400</b></td></tr>
 *   <tr><td>{@code org_admin}</td><td>本租户自建</td>
 *       <td>允许</td><td>允许（被引用 → {@code 10008}）</td><td>允许 + 防提权</td></tr>
 *   <tr><td>{@code super_admin}</td><td>预置</td>
 *       <td>允许</td><td><b>400（任何人不可删）</b></td><td>允许</td></tr>
 * </table>
 *
 * <h2>为什么是 400 而不是 403</h2>
 * <p>03-01 §3.4/§3.5/§3.6 三处逐字写的都是 {@code 400}，照抄。它与越界三分法
 * （403 = 我没资格做这件事）并不冲突：{@code org_admin} <b>有</b>
 * {@code system:role:edit} / {@code remove} / {@code assignMenu} 这三个功能权限
 * —— 菜单初始化数据里确实绑给了他，因为他要能改自己租户的自建角色。
 * 被拒的是<b>这一个目标对象不可写</b>，属于参数层面的拒绝。
 *
 * <h2>为什么收敛到一个类</h2>
 * <p>三个写接口各写一份判定，加第四个时必然漏一处，而<b>漏了不报错</b>：
 * 表现是某个租户的管理员改了预置角色，全平台跟着变，几周后才被发现。
 * 与 {@code TokenService#forget} 抽成一处是同一条判据 ——
 * <b>会静默出错的动作，出现次数越少越好。</b>
 */
@Service
public class PresetRoleGuard {

    private static final Logger log = LoggerFactory.getLogger(PresetRoleGuard.class);

    /**
     * §3.4 修改角色 / §3.6 为角色分配菜单的前置断言。
     *
     * <p>预置角色对 {@code org_admin} <b>全只读</b>，任何写操作返回 400；
     * {@code super_admin} 放行（「改预置角色属平台级操作，仅 super_admin 可为之」，§3.4 原文）。
     */
    public void assertWritable(SysRole role) {
        if (!role.isPreset()) {
            return;
        }
        if (TenantHelper.isSuperAdminSession()) {
            return;
        }
        log.warn("写侧收紧拦截：非超管尝试修改平台预置角色 userId={} roleId={} roleKey={}（03-01 §3.4/§3.6）",
                TenantHelper.getUserId(), role.getId(), role.getRoleKey());
        throw new BizException(ErrorCode.BAD_REQUEST,
                "平台预置角色对机构管理员只读，不可修改（03-01 §3.4）");
    }

    /**
     * §3.5 删除角色的前置断言 —— <b>预置角色任何人都不可删除，含 {@code super_admin}</b>。
     *
     * <p>§3.5 原文：「四个角色是契约第 3 节的固定集合，删掉即全平台该类用户失权」。
     * 所以这里<b>没有超管分支</b>。这不是漏写：给超管留一条删除路径，
     * 就等于给"一次误操作让全平台学生登录后零权限"留了入口，
     * 而这个动作<b>没有任何撤销接口</b>（本模块不提供恢复已删角色的功能）。
     */
    public void assertDeletable(SysRole role) {
        if (!role.isPreset()) {
            return;
        }
        log.warn("写侧收紧拦截：尝试删除平台预置角色 userId={} roleId={} roleKey={}（03-01 §3.5：任何人不可删）",
                TenantHelper.getUserId(), role.getId(), role.getRoleKey());
        throw new BizException(ErrorCode.BAD_REQUEST,
                "平台预置角色不可删除（03-01 §3.5：任何人不可删，含平台超管）");
    }

    /**
     * §3.3 创建角色：{@code roleKey} 不得使用四个预置值。
     *
     * <p>不做则出现一个 {@code tenant_id = 某租户} 而 {@code role_key = 'teacher'} 的行 ——
     * 它<b>不会撞 {@code uk_tenant_role_key}</b>（那个唯一键带 {@code tenant_id}），
     * 于是该租户里同时存在两个 {@code teacher}：一个平台的、一个自建的。
     * 此后任何按 {@code role_key} 判断身份的代码都会读到不确定的那一个。
     */
    public void assertRoleKeyNotPreset(String roleKey) {
        if (roleKey == null) {
            return;
        }
        String normalized = roleKey.trim();
        for (String preset : PRESET_ROLE_KEYS) {
            if (preset.equals(normalized)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "roleKey 不得使用平台预置值（super_admin / org_admin / teacher / student）");
            }
        }
    }

    /**
     * 契约 §3 的四个角色标识（穷举，不再有第五个）。
     *
     * <p><b>它只用于「新建角色不得占用这四个名字」这一条校验</b>，
     * <b>不是</b>「是不是预置角色」的判据 —— 那个一律按 {@code tenant_id = 0} 判
     * （见 {@link SysRole#isPreset()}）。两者用途不同：这里防的是<b>命名冲突</b>，
     * 那里判的是<b>行的归属</b>。若某天平台新增第五个内置角色，
     * 只有本清单需要跟着改，{@code PresetRoleGuard} 的三条断言一个字不动。
     */
    private static final String[] PRESET_ROLE_KEYS = {"super_admin", "org_admin", "teacher", "student"};
}
