package com.edumatrix.common.subtree;

import org.springframework.stereotype.Component;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 「当前账号是不是**机构根节点**」—— 三类受管资源（课程 / 题目 / 视频）的**写操作**统一走这一份。
 *
 * <h2>为什么在 {@code common/} 而不是各域各写一份</h2>
 * {@code course} / {@code question} / {@code vod} 三个域<b>互相不能 import</b>（约定检查③），
 * 而它们要的是<b>同一个判定</b>。各抄一遍的话会各自漂移，而漂移的表现是
 * <b>「有的资源收窄了、有的没有」——不报错</b>。
 * {@code common/} 不进检查③ 的扫描范围，正是做交汇点用的。
 *
 * <h2>为什么这类判定写在代码里而不是 {@code sys_role_menu}</h2>
 * <b>不是对 F-110 纪律的例外，而是它从来不覆盖这一类。</b>
 * F-110 说的是「权限的真相在 {@code sys_role_menu} → {@code sys_menu.perms}，代码里不写第二份」，
 * 而 {@code sys_role_menu} 只有 <b>role 与 menu 两列、没有节点维度</b> ——
 * 「机构根的 {@code org_admin} 能、分校的 {@code org_admin} 不能」<b>在那张表里根本写不出来</b>。
 *
 * <p>本判定属于<b>位置类判定</b>，与 {@link SubtreeScopeHelper}（我能看见谁）、
 * 建树规则（02-数据库设计 §444：父节点类型决定子节点类型）、冻结集判定同类 ——
 * <b>那一类判定本来就在代码里，这是它们的常态而不是破例</b>。
 * 写成「例外」会让下一个人以为 F-110 的纪律可破。
 *
 * <h2>判据</h2>
 * 判据本身<b>不在本类</b>，在 {@link OrgTreeShape#isOrgRoot} —— 建节点、移节点两条路径
 * 拿到的是<b>实体</b>而不是会话，判的却是同一件事，所以那一份是唯一的一份。
 * 本类只负责「把会话里的 {@code nodeId} / {@code tenantId} 取出来交给它」。
 */
@Component
public class OrgRootGuard {

    private final CurrentNodeProvider currentNodeProvider;

    public OrgRootGuard(CurrentNodeProvider currentNodeProvider) {
        this.currentNodeProvider = currentNodeProvider;
    }

    /** @return 当前账号是否位于机构根节点；无会话或无租户上下文时为 {@code false} */
    public boolean isOrgRoot() {
        Long nodeId = currentNodeProvider.currentNodeId();
        Long tenantId = TenantHelper.getTenantIdOrNull();
        return OrgTreeShape.isOrgRoot(nodeId, tenantId);
    }

    /**
     * 三类受管资源的写操作入口一律先调它。
     *
     * @param what 出错信息里点名是哪一类资源，便于排查（例：{@code "课程"}）
     * @throws BizException 非机构根一律 403（{@link ErrorCode#FORBIDDEN}）
     */
    public void assertOrgRoot(String what) {
        if (!isOrgRoot()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    what + "的新增、修改与删除【仅机构根节点可操作】（F-114 需方定案）。"
                            + "下级管理员即使拥有对应权限位也不行 —— "
                            + "这是资源归属层级的约束，不是权限等级。"
                            + "读接口不受影响：分校管理员仍看得见，才能授权给名下学员、才能组卷");
        }
    }
}
