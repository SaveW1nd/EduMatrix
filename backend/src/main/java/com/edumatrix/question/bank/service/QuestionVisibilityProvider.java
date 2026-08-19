package com.edumatrix.question.bank.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.question.QuestionVisibilityChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;

/**
 * {@link QuestionVisibilityChecker} 的实现（模块 10「对外产出」第一条）。
 *
 * <h2>材料题子题的折算 —— 这个类真正的难点</h2>
 * <p>03-04 §0.1：「材料题以<b>父题</b>为授权粒度：授权父题即连带其全部子题
 * （子题不单独授权，随父题判定）」。而 {@code org_resource_grant} 里
 * <b>只有父题的行</b>，子题没有自己的授权行。
 *
 * <p>所以 {@link #assertVisible} 拿到子题时，必须<b>拿父题去判</b>：
 * <ul>
 *   <li>只判 {@code owner_node_id}：子题与父题同 owner（03-04 §0.1），
 *       <b>自有</b>那一支恰好也对 —— 于是漏了折算<b>在自有场景下看不出来</b>；
 *   <li>被授权场景才暴露：授权挂在父题上，拿子题 ID 去问
 *       {@code ResourceGrantReader} 必然不命中 → 404。
 *       表现是「被授权方能看见父题、拿子题 ID 查详情却 404」——
 *       <b>接口不报错，只是少了一半数据</b>。
 * </ul>
 * <p>{@code QuestionVisibilityIT#grantedParentMakesChildVisible} 钉住这一条。
 */
@Component
public class QuestionVisibilityProvider implements QuestionVisibilityChecker {

    private final QbQuestionMapper questionMapper;
    private final ResourceGrantReader grantReader;
    private final CurrentNodeProvider currentNodeProvider;

    public QuestionVisibilityProvider(QbQuestionMapper questionMapper,
                                      ResourceGrantReader grantReader,
                                      CurrentNodeProvider currentNodeProvider) {
        this.questionMapper = questionMapper;
        this.grantReader = grantReader;
        this.currentNodeProvider = currentNodeProvider;
    }

    @Override
    public List<Long> visibleIds(Long myNodeId) {
        if (myNodeId == null) {
            return List.of();
        }
        // 保序去重：自有在前、被授权在后，便于人读日志时一眼看出来源
        Set<Long> ids = new LinkedHashSet<>(questionMapper.selectOwnedRootIds(myNodeId));
        ids.addAll(grantReader.grantedResourceIds(ResourceType.QUESTION, myNodeId));
        return List.copyOf(ids);
    }

    @Override
    public VisibleQuestion assertVisible(Long questionId) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();
        QbQuestion question = load(questionId);
        // 授权粒度是父题：子题一律拿父题去判（见类注释）
        QbQuestion authorizedOn = question.isChild() ? load(question.getParentId()) : question;

        boolean owned = authorizedOn.getOwnerNodeId() != null
                && authorizedOn.getOwnerNodeId().equals(myNodeId);
        if (owned) {
            return view(question, VisibleQuestion.GRANT_TYPE_OWNED);
        }
        if (grantReader.hasGrant(ResourceType.QUESTION, authorizedOn.getId(), myNodeId)) {
            return view(question, VisibleQuestion.GRANT_TYPE_GRANTED);
        }
        // 存在但不可见 —— 与「不存在」给出同一个结果（F-42 口径），不暴露存在性
        throw BizException.notFound(questionId);
    }

    /**
     * 按 ID 取行；查不到（不存在 / 已逻辑删除 / 跨租户）一律 404。
     *
     * <p>三种情形在这里<b>合并成同一个结果</b>：{@code selectById} 带 {@code @TableLogic}
     * 与租户插件，三者都返回 {@code null}。这正是不暴露存在性想要的。
     */
    private QbQuestion load(Long questionId) {
        QbQuestion question = questionId == null ? null : questionMapper.selectById(questionId);
        if (question == null) {
            throw BizException.notFound(questionId);
        }
        return question;
    }

    private static VisibleQuestion view(QbQuestion question, int grantType) {
        return new VisibleQuestion(question.getId(), question.getParentId(),
                question.getOwnerNodeId(), question.getQuestionType(),
                question.getCurrentVersion(), question.getStatus(), grantType);
    }
}
