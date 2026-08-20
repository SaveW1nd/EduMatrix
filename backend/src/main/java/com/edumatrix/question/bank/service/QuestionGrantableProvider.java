package com.edumatrix.question.bank.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.resource.GrantableResourceItem;
import com.edumatrix.common.resource.GrantableResourceProvider;
import com.edumatrix.common.resource.GrantableResourceQuery;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;
import com.edumatrix.question.bank.mapper.QuestionPageMapper;
import com.edumatrix.question.bank.vo.QuestionListVO;

/**
 * {@link ResourceType#QUESTION} 的可授权清单提供方（{@code resource_type = 2}，03-02 §9.1）。
 *
 * <h2>直接复用 {@link QuestionPageMapper#selectVisiblePage}，<b>不新写一条 SQL</b></h2>
 * <p>那条 SQL 的可见性谓词就是 {@code owner_node_id = 我 OR id IN (给定清单)}，
 * 与接口 37 要的一模一样，差别只在传进去的清单口径（{@code canUse} → {@code canRegrant}）。
 * 它顺带把 {@code categoryName} JOIN 出来了 —— 而 §9.1 的 {@code extra} 恰好要这个字段。
 *
 * <h2>⚠ 材料题子题不在可授权清单里，这是对的</h2>
 * <p>那条 SQL 带 {@code q.parent_id = 0}。子题<b>随父题走</b>：状态联动、不能脱离父题删除
 *（03-04 §2.7 / §2.8）。于是「把一道子题单独授给某个节点」本身就说不通 ——
 * 它不在接口 37 的清单里，传给接口 38 就会按契约 §2.5 规则 1 返回 {@code 10301}，
 * 与「资源不存在」逐字相同。<b>这条闭合是靠那个 {@code parent_id = 0} 达成的</b>，
 * 删掉它不会报错，只会让子题悄悄可授。
 *
 * <p><b>本类不做任何权限判定</b>，同 {@code CourseGrantableProvider}。
 */
@Component
public class QuestionGrantableProvider implements GrantableResourceProvider {

    private final QuestionPageMapper pageMapper;
    private final QbQuestionMapper questionMapper;
    private final QuestionCategoryScope categoryScope;

    public QuestionGrantableProvider(QuestionPageMapper pageMapper,
                                     QbQuestionMapper questionMapper,
                                     QuestionCategoryScope categoryScope) {
        this.pageMapper = pageMapper;
        this.questionMapper = questionMapper;
        this.categoryScope = categoryScope;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.QUESTION;
    }

    @Override
    public PageResult<GrantableResourceItem> page(GrantableResourceQuery query) {
        Page<QuestionListVO> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<QuestionListVO> result = pageMapper.selectVisiblePage(
                page, query.getMyNodeId(), query.getRegrantableIds(), query.getSource(),
                null, null, null, null, query.getKeyword(),
                categoryScope.withDescendants(query.getCategoryId()));

        List<GrantableResourceItem> list = new ArrayList<>(result.getRecords().size());
        for (QuestionListVO row : result.getRecords()) {
            GrantableResourceItem item = new GrantableResourceItem();
            item.setResourceId(row.getId());
            item.setResourceName(row.getStemPreview());
            item.setOwnerNodeId(row.getOwnerNodeId());
            // grantType 由那条 SQL 的 CASE WHEN owner_node_id = 我 算出，取值与 source 同源
            item.setSource(row.getGrantType() == null
                    ? GrantableResourceItem.SOURCE_GRANTED : row.getGrantType());
            item.put("questionType", row.getQuestionType())
                    .put("difficulty", row.getDifficulty())
                    .put("categoryName", row.getCategoryName())
                    .put("currentVersion", row.getCurrentVersion());
            list.add(item);
        }
        return PageResult.of(result.getTotal(), list);
    }

    @Override
    public Map<Long, String> namesOf(Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        // 租户条件由插件注入；deleted_at = 0 由 @TableLogic 自动追加
        Map<Long, String> names = new HashMap<>();
        for (QbQuestion question : questionMapper.selectBatchIds(resourceIds)) {
            names.put(question.getId(), question.getStemPreview());
        }
        return names;
    }
}
