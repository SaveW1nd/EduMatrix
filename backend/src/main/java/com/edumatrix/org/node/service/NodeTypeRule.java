package com.edumatrix.org.node.service;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.NodePath;

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
 * <p>合并的时机是<b>模块 07</b>：那时 {@code org} 建成建人/删人接口，
 * {@code PlatformNodeWriter} 整体退休（交接清单见
 * {@code system/user/entity/SystemOrgNode} 的类注释），第二份随之消失。
 *
 * <p><b>在那之前，改了一份不改另一份不会有任何东西报错</b> —— 两份各自的测试都会继续通过，
 * 只是同一个非法结构在两条路径上一条被拒、一条被放行。这句话写在这里就是为了让下一个人看见。
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
    public static void assertCanBeChildOf(Integer parentType, Integer childType) {
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
            // 管理员可挂 1/2/3；不可挂 0 —— 平台根全表唯一一行，不可能有第二个
            if (childType == NodePath.NODE_TYPE_PLATFORM) {
                throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
            }
            return;
        }
        throw new BizException(ErrorCode.NODE_PARENT_CHILD_TYPE_INVALID);
    }
}
