package com.edumatrix.org.node.service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;
import com.edumatrix.common.subtree.OrgTreeShape;

/**
 * 承载规则的<b>唯一判定</b>（契约 §2.3 结构约束 1、02-数据库设计 §3.1.5）。
 *
 * <pre>
 * node_type=0 平台超管 → 只挂 1                    违规 → 10104
 * node_type=1 管理员   → 可挂 1 / 2 / 3            违规 → 10104
 * node_type=2 教师     → 【仅】3                   违规 → 10105
 * node_type=3 学生     → 【叶子】不可有任何子节点   违规 → 10106
 * </pre>
 *
 * <h2>为什么收敛成一个静态方法</h2>
 * <p>02-数据库设计 §3.1.5 把<b>全部会改变父子关系的入口</b>逐一列了出来
 * （新建人员 / 移动节点 / 删除节点 / 建账号 / 导入学生），并逐字建议：
 * 「把上述判定收敛为一个 {@code NodeTypeRule.assertCanBeChildOf(parentType, childType)}
 * 静态方法，<b>所有入口共用，杜绝『某个接口忘了校验』</b>」。
 * 04-实施计划.md 模块 06 的「对外产出」也把它列为交付物。
 *
 * <h2>⚠ 全库还有<b>第二份同源实现</b>，改这里必须同时改那里</h2>
 * <p>{@code system/user/service/PlatformNodeWriter#assertParentAcceptsChild} 是同一套规则的
 * 另一份实现。<b>不是复制粘贴的疏忽</b>：{@code check_backend_conventions.sh} 的检查③
 * 禁止 {@code system} 领域 import {@code org} 领域，而 03-01 §2.2 的建号路径必须校验它。
 *
 * <p><b>⚠ 合并时机已由模块 07 重新定过：不是模块 07，是「模块 06 整改合入之后的单独一轮」。</b>
 * 原写的是模块 07 —— 那时 {@code org} 建成建人/删人接口、{@code PlatformNodeWriter} 整体退休
 * （交接清单见 {@code system/user/entity/SystemOrgNode} 的类注释），第二份随之消失。
 * 模块 07 确实建成了那些接口，但<b>没有做退休</b>，两条理由：
 * <ol>
 *   <li>交接清单里「{@code system/user} 改调对方 Service」这句<b>照字面做会触发检查③</b> ——
 *       那条 grep 拦的是 import 语句本身，不区分 import 的是实体还是 Service。
 *       正解是照 {@code common/account/PasswordHasher} 的先例走 {@code common/} SPI，
 *       方案已记在 {@code com.edumatrix.org.node} 的 {@code package-info}；
 *   <li>该重构触及模块 03 的 {@code SysUserService} 与模块 04 的两个 Service，
 *       <b>改动面与同期的模块 06 整改高度重叠</b>，同时动会在合并时把注释合掉一半。
 * </ol>
 * <p><b>在那一轮到来之前，两份仍必须并存，而改了一份不改另一份不会有任何东西报错</b> ——
 * 两份各自的测试都会继续通过，只是同一个非法结构在两条路径上一条被拒、一条被放行。
 * 这句话写在这里就是为了让下一个人看见。
 * 模块 07 已<b>逐组合比对过两份</b>（含 {@code null} 与兜底分支），当前对任意输入返回的
 * 错误码完全相同 —— <b>那不是可以放着不管的理由，那是它至今没出事的原因。</b>
 *
 * <h2>{@code node_type} 不可变更，所以本规则只在「建」与「移」两个时刻生效</h2>
 * <p>§3.1.5：「修改 {@code node_type} —— <b>直接禁止</b>。人员类型变更应走
 * 『改角色 + 移动节点』，不得原地改 {@code node_type}，否则其既有子树可能瞬间违规」。
 * §3.3 修改节点因此不提供该能力。
 */
public final class NodeTypeRule {

    private NodeTypeRule() {
    }

    /**
     * 断言 {@code childType} 可以挂在 {@code parentType} 之下。
     *
     * <h2>三个码的分工照 03-02 §3.4 的校验顺序，不合并</h2>
     * <p>校验 5（目标父是教师 → {@code 10105}）、校验 6（目标父是学生 → {@code 10106}）、
     * 校验 7（其余非法组合 → {@code 10104}），<b>判定顺序与错误码逐条对应</b>。
     * 前端据 {@code 10105} / {@code 10106} 给出的提示语不同
     * （「教师名下只能加学员」vs「学员不能再带人」），{@code 10104} 是兜底。
     *
     * @param parentType 目标父节点的 {@code node_type}
     * @param childType  被挂/被移动节点的 {@code node_type}
     */
    /**
     * @deprecated 用 {@link #assertCanBeChildOf(Integer, Integer, boolean)} —— F-114 之后
     *             「管理员下能挂什么」<b>取决于这个管理员是不是机构根</b>，只看类型判不出来。
     *             保留本重载只为不动那些确实与机构根无关的调用点；新代码不要用。
     */
    @Deprecated
    public static void assertCanBeChildOf(Integer parentType, Integer childType) {
        assertCanBeChildOf(parentType, childType, true);
    }

    /**
     * @param parentIsOrgRoot 父节点是不是<b>机构根</b>（{@code id == tenant_id}）。
     *
     * <p><b>F-114（需方 2026-08-22 定案）：机构下只允许一层管理员，树高最多 5 层。</b>
     * <pre>
     *   L0 平台根 → L1 机构根 → L2 普通管理员 → L3 教师 → L4 学生
     * </pre>
     * 改之前，树里<b>唯一能无限长的就是「管理员挂管理员」</b>（02-数据库设计 §444
     * 「父节点 {@code node_type=1} → 允许 1/2/3」）。现在：
     * <ul>
     *   <li>父节点是<b>机构根</b>的管理员 → 允许 1 / 2 / 3</li>
     *   <li>父节点是<b>非机构根</b>的管理员 → <b>只允许 2 / 3</b></li>
     * </ul>
     *
     * <p><b>需方给的理由比「结构清晰」硬，原样登记</b>：授权的规矩是「不能授你自己没有的东西」，
     * 所以要让某节点不悬挂，<b>上级必须先有</b>。任意深度下，补授会一路往上串，
     * <b>补几层没有上界</b>；限死 5 层之后 ——
     * 挪教师 → 断在 L2→L3，补 L2 一个节点（L2 的上级是机构根，永远有）；
     * 挪学生 → 断在 L3→L4，最多补 L3 与 L2 两个。
     * <b>加：最多两个节点，上界固定；去：永远一个动作（级联撤销）。</b>
     *
     * <p><b>深度限制不能让断链不发生</b>，但让每次修复的成本有了硬上界 —— 这才是它的价值。
     */
    public static void assertCanBeChildOf(Integer parentType, Integer childType,
                                          boolean parentIsOrgRoot) {
        if (parentType == null || childType == null) {
            throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
        }
        // 校验 5：教师节点下【仅】学生
        if (parentType == NodePath.NODE_TYPE_TEACHER) {
            if (childType != NodePath.NODE_TYPE_STUDENT) {
                throw new BizException(ErrorCode.TEACHER_NODE_ONLY_ACCEPTS_STUDENT);
            }
            return;
        }
        // 校验 6：学生是叶子，其下一律拒绝
        if (parentType == NodePath.NODE_TYPE_STUDENT) {
            throw new BizException(ErrorCode.STUDENT_NODE_MUST_BE_LEAF);
        }
        // 校验 7：其余非法组合
        if (parentType == NodePath.NODE_TYPE_PLATFORM) {
            // 平台根下只挂管理员（机构根节点）
            if (childType != NodePath.NODE_TYPE_ADMIN) {
                throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
            }
            return;
        }
        if (parentType == NodePath.NODE_TYPE_ADMIN) {
            // 管理员不可挂 0 —— 平台根全表唯一一行，不可能有第二个
            if (childType == NodePath.NODE_TYPE_PLATFORM) {
                throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
            }
            // 【F-114】机构下只允许一层管理员。判定不在这里 —— PlatformNodeWriter 有
            // 同源的第二份承载规则，两份都要拦这一条，所以这条判定只放在 common/ 那一份
            OrgTreeShape.assertOnlyOneAdminLayer(parentType, childType, parentIsOrgRoot);
            return;
        }
        throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
    }
}
