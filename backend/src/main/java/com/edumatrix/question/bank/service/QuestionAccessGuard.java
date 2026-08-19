package com.edumatrix.question.bank.service;

import org.springframework.stereotype.Service;

import com.edumatrix.common.question.QuestionVisibilityChecker;
import com.edumatrix.common.question.QuestionVisibilityChecker.VisibleQuestion;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;

/**
 * 题目的<b>可见性</b>与<b>写权限</b>判定 —— 本模块题目接口的唯一入口。
 *
 * <p>与 {@code course/catalog/service/CourseAccessGuard} <b>逐条同构</b>，
 * 不另立一套：两类受管资源的口径必须一致，而口径分叉不会报错。
 *
 * <table border="1">
 *   <caption>判定顺序（任一不过即终止）</caption>
 *   <tr><th>#</th><th>判定</th><th>不通过</th><th>依据</th></tr>
 *   <tr><td>1</td><td>{@code @SaCheckPermission}</td><td>403</td>
 *       <td>契约 §10 附表 A；不经过本类</td></tr>
 *   <tr><td>2</td><td>按 id 查到行（租户条件由插件注入、{@code deleted_at=0} 由 @TableLogic）</td>
 *       <td><b>404</b></td><td>见下方「为什么本模块没有 CourseRef 那样的三分」</td></tr>
 *   <tr><td>3</td><td><b>可见性</b>：是 owner ∪ 被显式授权且在有效期内（子题折算到父题）</td>
 *       <td><b>404</b></td>
 *       <td>03-04 §0.1「不可见时返回 404」+ 契约 §2.4 三分法第 1 行</td></tr>
 *   <tr><td>4</td><td><b>写权限</b>：{@code owner_node_id} <b>严格等于</b>我的节点</td>
 *       <td><b>403</b></td>
 *       <td>03-04 §0.1「可见 ≠ 可写……仅被授权者为只读可用，写操作返回 403」、契约 §2.5 规则 8</td></tr>
 * </table>
 *
 * <p><b>3 在 4 之前是必要的</b>：先 404 再 403，被授权者才拿到 403（可见但不可改），
 * 完全不相干的人拿到 404。反过来会把「存在一道你看不见的题」泄露出去。
 *
 * <h2>为什么本模块没有 {@code CourseRef} 那样的三分</h2>
 * <p>模块 08 要区分 {@code PATH} / {@code PARAM} / {@code DERIVED} 三类，是因为
 * {@code courseId} 会出现在请求体与查询参数里（创建章节、课时列表）。
 * 而模块 10 的十二个接口里，<b>{@code questionId} 只出现在路径上</b>
 * （接口 7/8/9/10/11/12 的 {@code {id}}），没有第二类来源。
 * 所以这里只有一种，返回码恒为 404（F-42 口径：「不存在」与「不可见」同一个结果）。
 *
 * <p><b>模块 15 会有第二类</b>：选题时 {@code questionIds} 出现在请求体里。
 * 那时不要在本类上加重载 —— 按 {@code CourseAccessGuard.CourseRef} 的形态显式分类，
 * 并先把「请求体里的不可用题目返回什么码」定下来（已登记 F-75）。
 */
@Service
public class QuestionAccessGuard {

    private final QbQuestionMapper questionMapper;
    private final QuestionVisibilityChecker visibilityChecker;
    private final CurrentNodeProvider currentNodeProvider;

    public QuestionAccessGuard(QbQuestionMapper questionMapper,
                               QuestionVisibilityChecker visibilityChecker,
                               CurrentNodeProvider currentNodeProvider) {
        this.questionMapper = questionMapper;
        this.visibilityChecker = visibilityChecker;
        this.currentNodeProvider = currentNodeProvider;
    }

    /** 当前登录人所在节点；取不到抛 400（绝不退化为「不加过滤」，契约 §7.1）。 */
    public Long myNodeId() {
        return currentNodeProvider.requireCurrentNodeId();
    }

    /** 判定 2 + 3：读操作用。不存在 / 不可见 —— 同一个 404。 */
    public VisibleQuestion loadVisible(Long questionId) {
        return visibilityChecker.assertVisible(questionId);
    }

    /**
     * 判定 2 + 3 + 4：写操作用。不可见 → 404；可见但非 owner → 403。
     *
     * <p>材料题：<b>owner 判定落在题目自身</b>（父子同 {@code owner_node_id}，
     * 03-04 §0.1），与可见性的「折算到父题」不同 —— 两者用的不是同一个谓词，
     * 这一点不要合并。
     */
    public QbQuestion loadOwned(Long questionId) {
        Long myNodeId = myNodeId();
        VisibleQuestion visible = visibilityChecker.assertVisible(questionId);
        if (!visible.owned()) {
            // 可见但不是 owner = 被授权者的「只读可用」（03-04 §0.1），403 而不是 404
            throw BizException.forbidden();
        }
        QbQuestion question = questionMapper.selectById(questionId);
        if (question == null || !myNodeId.equals(question.getOwnerNodeId())) {
            // assertVisible 与本行之间发生了并发删除 / 转归属 —— 按不可见处理
            throw BizException.notFound(questionId);
        }
        return question;
    }

    /**
     * 同 {@link #loadOwned}，外加<b>取主表行锁</b>。
     *
     * <p>会写版本表或改 {@code current_version} / {@code status} 的操作一律用它。
     * 材料题一律锁<b>父题</b>行，于是同一道材料题的父子编辑串行 ——
     * 加锁对象只有一个，结构上不可能死锁（无跨表加锁顺序）。
     */
    public QbQuestion loadOwnedForUpdate(Long questionId) {
        QbQuestion question = loadOwned(questionId);
        Long lockTarget = question.isChild() ? question.getParentId() : question.getId();
        questionMapper.lockForUpdate(lockTarget);
        // 取锁后重读：等锁期间它可能已被删除 / 改归属
        QbQuestion fresh = questionMapper.selectById(questionId);
        if (fresh == null) {
            throw BizException.notFound(questionId);
        }
        if (!myNodeId().equals(fresh.getOwnerNodeId())) {
            throw BizException.forbidden();
        }
        return fresh;
    }
}
