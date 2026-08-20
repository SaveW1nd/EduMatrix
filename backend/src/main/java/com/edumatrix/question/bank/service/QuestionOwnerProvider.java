package com.edumatrix.question.bank.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.edumatrix.common.resource.ResourceOwnerProvider;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;

/**
 * {@link ResourceType#QUESTION} 的归属提供方（{@code resource_type = 2}）——
 * 模块 08 留下的强制检查点。
 *
 * <p>在它存在之前，{@code ResourceOwnerChecker} 对 {@code QUESTION} <b>抛
 * {@code IllegalStateException}</b>。那是刻意的：若改成返回 {@code false}，
 * 模块 11 的授权引擎会静默判定「你不是 owner」——<b>接口 200、字段齐全、结果错</b>，
 * 正是本项目 1 号失败模式。
 *
 * <p>三类受管资源还差视频（{@code =3}），由模块 09 补。
 *
 * <h2>材料题子题：这里【照实回答】，不折算到父题</h2>
 * <p>03-04 §0.1 写「材料题的父题与子题写入同一 {@code owner_node_id}」，
 * 所以子题问归属时答案本来就与父题相同，不需要折算。
 * 需要折算的是<b>授权</b>（{@code org_resource_grant} 只有父题的行），
 * 那件事在 {@code QuestionVisibilityProvider} 里做 —— 本类
 * <b>只回答归属是谁，不做任何权限判定</b>（与 {@code CourseOwnerProvider} 同型）。
 */
@Component
public class QuestionOwnerProvider implements ResourceOwnerProvider {

    private final QbQuestionMapper questionMapper;

    public QuestionOwnerProvider(QbQuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.QUESTION;
    }

    @Override
    public Long ownerNodeIdOf(Long resourceId) {
        if (resourceId == null) {
            return null;
        }
        // 租户条件由插件注入；deleted_at = 0 由 @TableLogic 自动追加
        QbQuestion question = questionMapper.selectById(resourceId);
        return question == null ? null : question.getOwnerNodeId();
    }

    /**
     * 批量版：一条 {@code selectBatchIds} 代替 N 次点查。
     *
     * <p>模块 11 的接口 38 单次最多 500 个 {@code resourceIds}，走默认实现就是 500 次往返 ——
     * 慢，但<b>不报错</b>。覆写的理由只有性能，语义与逐个查逐字相同。
     */
    @Override
    public Map<Long, Long> ownerNodeIdsOf(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> owners = new HashMap<>();
        for (QbQuestion question : questionMapper.selectBatchIds(resourceIds)) {
            if (question.getOwnerNodeId() != null) {
                owners.put(question.getId(), question.getOwnerNodeId());
            }
        }
        return owners;
    }
}
