package com.edumatrix.question.bank.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.question.QuestionVisibilityChecker.VisibleQuestion;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.question.bank.dto.QuestionPageQuery;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.entity.QbQuestionVersion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;
import com.edumatrix.question.bank.mapper.QuestionPageMapper;
import com.edumatrix.question.bank.vo.QuestionDetailVO;
import com.edumatrix.question.bank.vo.QuestionListVO;
import com.edumatrix.question.bank.vo.QuestionSnapshotVO;
import com.edumatrix.question.bank.vo.QuestionVersionMetaVO;
import com.edumatrix.question.bank.vo.QuestionVersionVO;
import com.edumatrix.question.category.entity.QbCategory;
import com.edumatrix.question.category.mapper.QbCategoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 题目读侧（03-04 §2.1 / §2.4 / §2.5 / §2.6，接口 5 / 8 / 9 / 10）。
 *
 * <h2>三个接口的可见性都走同一个入口</h2>
 * <p>详情 / 版本列表 / 版本快照一律先 {@code guard.loadVisible(id)}，
 * 不可见 → 404。<b>接口 10 的顺序是死规定</b>：可见性 404 必须在 30007 之前判 ——
 * 反过来 30007 就成了存在性探针（「这个 id 有没有第 3 版」能回答「这道题存不存在」）。
 *
 * <h2>教师侧接口，含答案与解析</h2>
 * <p>03-04 §2.4 说明逐字。学生端的题目下发接口（模块 15 的接口 20/21）
 * <b>一律不含</b> {@code correctAnswer} 与 {@code analysis} —— 那是另一套 VO，
 * 不要为了复用把答案字段做成可空开关。
 */
@Service
public class QuestionQueryService {

    private final QbQuestionMapper questionMapper;
    private final QbCategoryMapper categoryMapper;
    private final QuestionCategoryScope categoryScope;
    private final QuestionPageMapper pageMapper;
    private final QuestionVersionProvider versionProvider;
    private final QuestionAccessGuard guard;
    private final ResourceGrantReader grantReader;
    private final NodeNameReader nodeNameReader;
    private final UserNameReader userNameReader;
    private final ObjectMapper objectMapper;

    public QuestionQueryService(QbQuestionMapper questionMapper,
                                QbCategoryMapper categoryMapper,
                                QuestionCategoryScope categoryScope,
                                QuestionPageMapper pageMapper,
                                QuestionVersionProvider versionProvider,
                                QuestionAccessGuard guard,
                                ResourceGrantReader grantReader,
                                NodeNameReader nodeNameReader,
                                UserNameReader userNameReader,
                                ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.categoryMapper = categoryMapper;
        this.categoryScope = categoryScope;
        this.pageMapper = pageMapper;
        this.versionProvider = versionProvider;
        this.guard = guard;
        this.grantReader = grantReader;
        this.nodeNameReader = nodeNameReader;
        this.userNameReader = userNameReader;
        this.objectMapper = objectMapper;
    }

    // =====================================================================
    // 接口 5 §2.1 分页查询题目
    // =====================================================================

    public PageResult<QuestionListVO> page(QuestionPageQuery query) {
        Long myNodeId = guard.myNodeId();
        List<Long> grantedIds = grantReader.grantedResourceIds(ResourceType.QUESTION, myNodeId);

        Page<QuestionListVO> page = new Page<>(
                query.getPageNum() == null ? PageResult.DEFAULT_PAGE_NUM : query.getPageNum(),
                normalizePageSize(query.getPageSize()));

        var result = pageMapper.selectVisiblePage(page, myNodeId, grantedIds, query.getGrantType(),
                query.getQuestionType(), query.getDifficulty(), query.getStatus(),
                query.getCreatorId(), query.getKeyword(),
                categoryWithDescendants(query.getCategoryId()));

        fillNames(result.getRecords());
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return PageResult.DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, PageResult.MAX_PAGE_SIZE);
    }

    /**
     * 分类筛选<b>含其全部子孙分类</b>（03-04 §2.1 参数表逐字）。
     *
     * <p><b>实现已抽到 {@link QuestionCategoryScope}</b>：模块 11 的接口 37
     *（我可授权的资源列表，03-02 §9.1）也按分类筛，两处必须是同一条口径 ——
     * 否则同一个分类树在两个页面上出不同的结果，而两边都返回 200。
     *
     * @return {@code null} 表示不按分类筛（不是空集 —— 空集会把结果筛成 0 行）
     */
    private List<Long> categoryWithDescendants(Long categoryId) {
        return categoryScope.withDescendants(categoryId);
    }

    /** 归属节点名与创建人姓名 —— 一页最多 100 行，两次批量查询，不逐行点查。 */
    private void fillNames(List<QuestionListVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<Long> nodeIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(row -> {
            if (row.getOwnerNodeId() != null) {
                nodeIds.add(row.getOwnerNodeId());
            }
            if (row.getCreatorId() != null) {
                userIds.add(row.getCreatorId());
            }
        });
        Map<Long, String> nodeNames = nodeNameReader.nodeNames(nodeIds);
        Map<Long, String> userNames = userNameReader.realNames(userIds);
        rows.forEach(row -> {
            row.setOwnerNodeName(nodeNames.get(row.getOwnerNodeId()));
            row.setCreatorName(userNames.get(row.getCreatorId()));
        });
    }

    // =====================================================================
    // 接口 8 §2.4 题目详情（默认当前版本）
    // =====================================================================

    /**
     * 材料题传父题 ID 时附全部子题当前版本；传子题 ID 时返回子题并附 {@code parentId}。
     *
     * <p>子题顺序取自父题版本的 {@code content.childOrder} —— <b>不是</b>按 ID 排序，
     * 也<b>不是</b>按插入顺序：{@code qb_question} 没有 sort 列，childOrder 是唯一真相源。
     */
    public QuestionDetailVO detail(Long id) {
        VisibleQuestion visible = guard.loadVisible(id);
        QbQuestion question = questionMapper.selectById(id);
        QuestionDetailVO vo = toDetail(question, visible.grantType());

        if (question.getQuestionType() != null
                && question.getQuestionType() == com.edumatrix.common.question.QuestionType.MATERIAL.code()) {
            vo.setChildQuestions(childrenInOrder(question, visible.grantType()));
        }
        return vo;
    }

    private List<QuestionDetailVO> childrenInOrder(QbQuestion parent, int grantType) {
        List<QbQuestion> children = questionMapper.selectList(
                new LambdaQueryWrapper<QbQuestion>().eq(QbQuestion::getParentId, parent.getId()));
        Map<Long, QbQuestion> byId = new LinkedHashMap<>();
        children.forEach(child -> byId.put(child.getId(), child));

        List<QuestionDetailVO> ordered = new ArrayList<>();
        JsonNode childOrder = versionProvider.read(parent.getId(), parent.getCurrentVersion())
                .content().get("childOrder");
        if (childOrder != null && childOrder.isArray()) {
            for (JsonNode idNode : childOrder) {
                QbQuestion child = byId.remove(parseId(idNode));
                if (child != null) {
                    QuestionDetailVO childVo = toDetail(child, grantType);
                    childVo.setSort(ordered.size() + 1);
                    ordered.add(childVo);
                }
            }
        }
        // childOrder 里没提到的子题仍要返回 —— 少一道题不会报错，只是试卷短了一截
        for (QbQuestion leftover : byId.values()) {
            QuestionDetailVO childVo = toDetail(leftover, grantType);
            childVo.setSort(ordered.size() + 1);
            ordered.add(childVo);
        }
        return ordered;
    }

    private static Long parseId(JsonNode node) {
        try {
            return Long.valueOf(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private QuestionDetailVO toDetail(QbQuestion question, int grantType) {
        QuestionDetailVO vo = new QuestionDetailVO();
        vo.setId(question.getId());
        vo.setCategoryId(question.getCategoryId());
        QbCategory category = question.getCategoryId() == null ? null
                : categoryMapper.selectById(question.getCategoryId());
        vo.setCategoryName(category == null ? null : category.getCategoryName());
        vo.setQuestionType(question.getQuestionType());
        vo.setParentId(question.getParentId());
        vo.setDifficulty(question.getDifficulty());
        vo.setCurrentVersion(question.getCurrentVersion());
        vo.setStatus(question.getStatus());
        vo.setOwnerNodeId(question.getOwnerNodeId());
        vo.setOwnerNodeName(nodeNameReader.nodeNames(List.of(question.getOwnerNodeId()))
                .get(question.getOwnerNodeId()));
        vo.setGrantType(grantType);
        vo.setCreatorId(question.getCreateBy());
        vo.setCreatorName(question.getCreateBy() == null ? null
                : userNameReader.realNames(List.of(question.getCreateBy())).get(question.getCreateBy()));
        vo.setCreateTime(question.getCreateTime());
        vo.setUpdateTime(question.getUpdateTime());

        QbQuestionVersion row = versionProvider.currentRow(question);
        vo.setVersion(toVersion(row));
        return vo;
    }

    // =====================================================================
    // 接口 9 §2.5 题目版本列表
    // =====================================================================

    /** 按 {@code version} 倒序返回全部历史版本元信息；<b>不分页</b>（版本数量有限）。 */
    public List<QuestionVersionMetaVO> versions(Long id) {
        VisibleQuestion visible = guard.loadVisible(id);
        List<QbQuestionVersion> rows = versionProvider.history(id);
        Set<Long> creators = new LinkedHashSet<>();
        rows.forEach(row -> {
            if (row.getCreateBy() != null) {
                creators.add(row.getCreateBy());
            }
        });
        Map<Long, String> names = userNameReader.realNames(creators);

        List<QuestionVersionMetaVO> list = new ArrayList<>();
        for (QbQuestionVersion row : rows) {
            QuestionVersionMetaVO meta = new QuestionVersionMetaVO();
            meta.setVersion(row.getVersion());
            meta.setIsCurrent(row.getVersion().equals(visible.currentVersion()));
            meta.setStemPreview(stemOf(row.getContent()));
            meta.setScoreDefault(row.getScoreDefault());
            meta.setCreatedBy(row.getCreateBy());
            meta.setCreatedByName(names.get(row.getCreateBy()));
            meta.setCreatedTime(row.getCreateTime());
            list.add(meta);
        }
        return list;
    }

    // =====================================================================
    // 接口 10 §2.6 题目指定版本快照
    // =====================================================================

    /**
     * <b>顺序是死规定</b>：先 {@code loadVisible}（不可见 → 404），
     * 再查版本（不存在 → 30007）。反过来 30007 就成了存在性探针。
     */
    public QuestionSnapshotVO snapshot(Long id, Integer version) {
        guard.loadVisible(id);
        QbQuestionVersion row = versionProvider.rowOrNull(id, version);
        if (row == null) {
            throw new BizException(ErrorCode.QUESTION_VERSION_NOT_FOUND);
        }
        QbQuestion question = questionMapper.selectById(id);

        QuestionSnapshotVO vo = new QuestionSnapshotVO();
        vo.setQuestionId(id);
        vo.setVersion(row.getVersion());
        vo.setQuestionType(question.getQuestionType());
        vo.setContent(parse(row.getContent()));
        vo.setCorrectAnswer(parse(row.getCorrectAnswer()));
        vo.setAnalysis(row.getAnalysis());
        vo.setScoreDefault(row.getScoreDefault());
        vo.setCreatedBy(row.getCreateBy());
        vo.setCreatedByName(row.getCreateBy() == null ? null
                : userNameReader.realNames(List.of(row.getCreateBy())).get(row.getCreateBy()));
        vo.setCreatedTime(row.getCreateTime());
        return vo;
    }

    // =====================================================================
    // 私有
    // =====================================================================

    private QuestionVersionVO toVersion(QbQuestionVersion row) {
        QuestionVersionVO vo = new QuestionVersionVO();
        vo.setVersion(row.getVersion());
        vo.setContent(parse(row.getContent()));
        vo.setCorrectAnswer(parse(row.getCorrectAnswer()));
        vo.setAnalysis(row.getAnalysis());
        vo.setScoreDefault(row.getScoreDefault());
        vo.setCreatedBy(row.getCreateBy());
        vo.setCreatedByName(row.getCreateBy() == null ? null
                : userNameReader.realNames(List.of(row.getCreateBy())).get(row.getCreateBy()));
        vo.setCreatedTime(row.getCreateTime());
        return vo;
    }

    private String stemOf(String contentJson) {
        JsonNode content = parse(contentJson);
        JsonNode stem = content == null ? null : content.get("stem");
        return stem == null || !stem.isTextual() ? null : stem.asText();
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("qb_question_version 里的 JSON 无法解析（版本快照本应不可变）", e);
        }
    }
}
