package com.edumatrix.question.bank.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.question.AnswerJson;
import com.edumatrix.common.question.CorrectAnswer;
import com.edumatrix.common.question.QuestionType;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.question.bank.dto.ChildQuestionReq;
import com.edumatrix.question.bank.dto.QuestionCreateReq;
import com.edumatrix.question.bank.dto.QuestionStatusReq;
import com.edumatrix.question.bank.dto.QuestionUpdateReq;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.entity.QbQuestionVersion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;
import com.edumatrix.question.bank.mapper.QuestionReferenceMapper;
import com.edumatrix.question.bank.vo.QuestionCreatedVO;
import com.edumatrix.question.bank.vo.QuestionStatusVO;
import com.edumatrix.question.bank.vo.QuestionUpdatedVO;
import com.edumatrix.question.bank.vo.ReferencingHomeworkVO;
import com.edumatrix.question.category.entity.QbCategory;
import com.edumatrix.question.category.mapper.QbCategoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 题目写侧（03-04 §2.2 / §2.3 / §2.7 / §2.8，接口 6 / 7 / 11 / 12）。
 *
 * <h2>编辑 = 写新版本，物理 ID 不变（PRD F3-2）</h2>
 * <p>{@code content} / {@code correctAnswer} / {@code analysis} / {@code scoreDefault}
 * 任一变更 → 追加一条 {@code qb_question_version}（{@code version+1}）并回写
 * {@code current_version}；只改 {@code categoryId} / {@code difficulty} / {@code remark}
 * 时<b>不产生新版本</b>（{@code versionCreated=false}）。
 * <b>历史版本行一个字都不动</b> —— 机制见 {@code QbQuestionVersionMapper}。
 *
 * <h2>题型不可改</h2>
 * <p>PRD F3-2 规则 2 逐字。所以 {@code QuestionUpdateReq} 里没有 {@code questionType}
 * 这个字段 —— 不是「校验它没变」，是<b>压根收不到</b>。
 * 改题型会让已固化该题的历史作业按新题型渲染旧答案：接口 200、字段齐全、结果错。
 *
 * <h2>材料题的父子是一个事务</h2>
 * <p>父题与全部子题在同一个事务里写；父题版本的 {@code content.childOrder} 记子题顺序，
 * 子题<b>没有自己的 sort 列</b>（{@code qb_question} 表就没有这一列）——
 * 顺序只有 {@code childOrder} 这一个真相源。
 */
@Service
public class QuestionService {

    /** PRD F3-1 规则 5：题干纯文本摘要 ≤200 字符（DDL VARCHAR(500) 留余量）。 */
    private static final int STEM_PREVIEW_MAX = 200;

    private final QbQuestionMapper questionMapper;
    private final QbCategoryMapper categoryMapper;
    private final QuestionReferenceMapper referenceMapper;
    private final QuestionVersionProvider versionProvider;
    private final QuestionAccessGuard guard;
    private final CurrentNodeProvider currentNodeProvider;

    public QuestionService(QbQuestionMapper questionMapper,
                           QbCategoryMapper categoryMapper,
                           QuestionReferenceMapper referenceMapper,
                           QuestionVersionProvider versionProvider,
                           QuestionAccessGuard guard,
                           CurrentNodeProvider currentNodeProvider) {
        this.questionMapper = questionMapper;
        this.categoryMapper = categoryMapper;
        this.referenceMapper = referenceMapper;
        this.versionProvider = versionProvider;
        this.guard = guard;
        this.currentNodeProvider = currentNodeProvider;
    }

    // =====================================================================
    // 接口 6 §2.2 创建题目
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public QuestionCreatedVO create(QuestionCreateReq req) {
        QuestionType type = requireType(req.getQuestionType());
        requireCategory(req.getCategoryId());
        Long ownerNodeId = currentNodeProvider.requireCurrentNodeId();

        AnswerJson.validateContent(type, req.getContent());
        CorrectAnswer answer = AnswerJson.readCorrect(type, req.getCorrectAnswer());
        AnswerJson.validateAgainstContent(type, req.getContent(), answer);

        int status = req.getStatus() == null ? QbQuestion.STATUS_DRAFT : req.getStatus();

        if (type != QuestionType.MATERIAL) {
            Long id = insertQuestion(type, req.getCategoryId(), ownerNodeId, QbQuestion.NO_PARENT,
                    req.getDifficulty(), status, stemPreview(req.getContent()), req.getRemark());
            versionProvider.append(id, req.getContent(), AnswerJson.writeCorrect(answer),
                    req.getAnalysis(), req.getScoreDefault());
            return new QuestionCreatedVO(id, QbQuestionVersion.FIRST_VERSION, List.of());
        }
        return createMaterial(req, ownerNodeId, status);
    }

    /**
     * 材料题：父题 + 全部子题，一个事务。
     *
     * <p>父题的 {@code scoreDefault} 必须等于子题之和（契约 §5「父题分数 = 子题之和」），
     * 不相等 → {@code 30006}。这一条<b>文档没有明写返回什么码</b>（登记 F-79），
     * 按 30006 处置是因为它就是「内容与题型不匹配」的一种。
     */
    private QuestionCreatedVO createMaterial(QuestionCreateReq req, Long ownerNodeId, int status) {
        List<ChildQuestionReq> children = req.getChildQuestions();
        if (children == null || children.isEmpty()) {
            throw mismatch("材料题必须至少有 1 个子题（03-04 §2.2）");
        }
        BigDecimal childSum = BigDecimal.ZERO;
        for (ChildQuestionReq child : children) {
            QuestionType childType = requireType(child.getQuestionType());
            if (!childType.canBeChild()) {
                throw mismatch("子题题型只能是 1~5，不能再是材料题");
            }
            AnswerJson.validateContent(childType, child.getContent());
            CorrectAnswer childAnswer = AnswerJson.readCorrect(childType, child.getCorrectAnswer());
            AnswerJson.validateAgainstContent(childType, child.getContent(), childAnswer);
            childSum = childSum.add(child.getScoreDefault());
        }
        if (req.getScoreDefault().compareTo(childSum) != 0) {
            throw mismatch("材料题父题的 scoreDefault=" + req.getScoreDefault()
                    + " 必须等于子题之和 " + childSum + "（契约 §5「父题分数 = 子题之和」）");
        }

        Long parentId = insertQuestion(QuestionType.MATERIAL, req.getCategoryId(), ownerNodeId,
                QbQuestion.NO_PARENT, req.getDifficulty(), status,
                stemPreview(req.getContent()), req.getRemark());

        List<ChildQuestionReq> ordered = new ArrayList<>(children);
        ordered.sort((a, b) -> Integer.compare(sortOf(a, children), sortOf(b, children)));

        List<Long> childIds = new ArrayList<>();
        for (ChildQuestionReq child : ordered) {
            QuestionType childType = requireType(child.getQuestionType());
            Long childId = insertQuestion(childType, req.getCategoryId(), ownerNodeId, parentId,
                    req.getDifficulty(), status, stemPreview(child.getContent()), null);
            CorrectAnswer childAnswer = AnswerJson.readCorrect(childType, child.getCorrectAnswer());
            versionProvider.append(childId, child.getContent(),
                    AnswerJson.writeCorrect(childAnswer), child.getAnalysis(),
                    child.getScoreDefault());
            childIds.add(childId);
        }

        // 父题版本的 content 里带上 childOrder —— 子题顺序的唯一真相源
        versionProvider.append(parentId, withChildOrder(req.getContent(), childIds),
                null, req.getAnalysis(), req.getScoreDefault());
        return new QuestionCreatedVO(parentId, QbQuestionVersion.FIRST_VERSION, childIds);
    }

    // =====================================================================
    // 接口 7 §2.3 修改题目（自动生成新版本）
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public QuestionUpdatedVO update(Long id, QuestionUpdateReq req) {
        QbQuestion question = guard.loadOwnedForUpdate(id);
        QuestionType type = requireType(question.getQuestionType());
        QbQuestionVersion current = versionProvider.currentRow(question);

        boolean contentChanged = req.getContent() != null;
        boolean answerChanged = req.getCorrectAnswer() != null;
        boolean analysisChanged = req.getAnalysis() != null
                && !Objects.equals(req.getAnalysis(), current.getAnalysis());
        boolean scoreChanged = req.getScoreDefault() != null
                && current.getScoreDefault().compareTo(req.getScoreDefault()) != 0;
        boolean newVersion = contentChanged || answerChanged || analysisChanged || scoreChanged;

        QbQuestion patch = new QbQuestion();
        patch.setId(id);
        if (req.getCategoryId() != null) {
            requireCategory(req.getCategoryId());
            patch.setCategoryId(req.getCategoryId());
        }
        if (req.getDifficulty() != null) {
            patch.setDifficulty(req.getDifficulty());
        }
        if (req.getRemark() != null) {
            patch.setRemark(req.getRemark());
        }

        if (!newVersion) {
            questionMapper.updateById(patch);
            return new QuestionUpdatedVO(id, question.getCurrentVersion(), false);
        }

        // 当前快照只读一次：下面最多有三处要用它（沿用旧 content / 旧答案 / 旧 childOrder）
        var snapshot = versionProvider.read(id, current.getVersion());
        JsonNode content = contentChanged ? req.getContent() : snapshot.content();
        JsonNode rawAnswer = answerChanged ? req.getCorrectAnswer() : snapshot.correctAnswer();

        AnswerJson.validateContent(type, content);
        CorrectAnswer answer = AnswerJson.readCorrect(type, rawAnswer);
        AnswerJson.validateAgainstContent(type, content, answer);
        if (type == QuestionType.MATERIAL) {
            content = preserveChildOrder(content, snapshot.content(), id);
        }

        int nextVersion = versionProvider.append(id, content, AnswerJson.writeCorrect(answer),
                analysisChanged ? req.getAnalysis() : current.getAnalysis(),
                scoreChanged ? req.getScoreDefault() : current.getScoreDefault());

        patch.setCurrentVersion(nextVersion);
        patch.setStemPreview(stemPreview(content));
        questionMapper.updateById(patch);
        return new QuestionUpdatedVO(id, nextVersion, true);
    }

    // =====================================================================
    // 接口 11 §2.7 启用/停用题目
    // =====================================================================

    /**
     * 停用（{@code status=2}）时若该题（材料题含其任一子题）被作业引用 → {@code 30001}。
     *
     * <p>启用无前置校验；材料题父题的状态<b>联动全部子题</b>（03-04 §2.7）。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuestionStatusVO changeStatus(Long id, QuestionStatusReq req) {
        QbQuestion question = guard.loadOwnedForUpdate(id);
        rejectChildId(question, "启用/停用请传材料题的父题 ID（03-04 §2.7）");

        if (req.getStatus() == QbQuestion.STATUS_DISABLED) {
            List<ReferencingHomeworkVO> referencing =
                    referenceMapper.selectReferencingHomeworks(familyIds(question));
            if (!referencing.isEmpty()) {
                throw new BizException(ErrorCode.QUESTION_IN_USE_CANNOT_DISABLE,
                        ErrorCode.QUESTION_IN_USE_CANNOT_DISABLE.getMsg(), id,
                        java.util.Map.of("referencedHomeworks", referencing));
            }
        }
        applyStatus(question, req.getStatus());
        return new QuestionStatusVO(id, req.getStatus());
    }

    // =====================================================================
    // 接口 12 §2.8 删除题目（逻辑删除）
    // =====================================================================

    /**
     * 被<b>任意状态</b>作业引用即不可删除 → {@code 30005}，只能停用。
     *
     * <p>材料题删父题时<b>级联逻辑删除全部子题</b>。
     * {@code qb_question_version} <b>永不删除</b>（03-04 §2.8），
     * {@code org_resource_grant} 里的授权行<b>一律保留、不做级联撤销</b>
     * （契约 §2.5 规则 12）—— 题目若恢复则原授权自动重新生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        QbQuestion question = guard.loadOwnedForUpdate(id);
        rejectChildId(question, "删除请传材料题的父题 ID（03-04 §2.8）");

        List<Long> family = familyIds(question);
        if (!referenceMapper.selectReferencingHomeworks(family).isEmpty()) {
            throw new BizException(ErrorCode.QUESTION_IN_USE_CANNOT_REMOVE);
        }
        family.forEach(questionMapper::deleteById);
    }

    // =====================================================================
    // 私有
    // =====================================================================

    /** 题目自身 + （材料题）其全部子题 —— 引用校验与状态联动的作用范围。 */
    private List<Long> familyIds(QbQuestion question) {
        List<Long> ids = new ArrayList<>();
        ids.add(question.getId());
        if (question.getQuestionType() == QuestionType.MATERIAL.code()) {
            questionMapper.selectList(new LambdaQueryWrapper<QbQuestion>()
                            .eq(QbQuestion::getParentId, question.getId()))
                    .forEach(child -> ids.add(child.getId()));
        }
        return ids;
    }

    private void applyStatus(QbQuestion question, int status) {
        for (Long id : familyIds(question)) {
            QbQuestion patch = new QbQuestion();
            patch.setId(id);
            patch.setStatus(status);
            questionMapper.updateById(patch);
        }
    }

    /**
     * 接口 11 / 12 收到<b>子题 ID</b> → 400。
     *
     * <p>03-04 §2.7 / §2.8 只写「材料题传父题 ID」，<b>没说传子题 ID 会怎样</b>
     * （登记 F-78）。定为 400 的理由：子题状态随父题联动、子题不能脱离父题被删除，
     * 所以「以子题 ID 调这两个接口」本身就是一个说不通的请求。
     * 静默按父题处理更糟 —— 调用方以为只动了一道子题，实际整道材料题都变了。
     */
    private void rejectChildId(QbQuestion question, String hint) {
        if (question.isChild()) {
            throw new BizException(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.getMsg() + "：" + hint);
        }
    }

    private Long insertQuestion(QuestionType type, Long categoryId, Long ownerNodeId, Long parentId,
                                Integer difficulty, int status, String stemPreview, String remark) {
        QbQuestion row = new QbQuestion();
        row.setOwnerNodeId(ownerNodeId);
        row.setCategoryId(categoryId);
        row.setQuestionType(type.code());
        row.setParentId(parentId);
        row.setDifficulty(difficulty);
        row.setCurrentVersion(QbQuestionVersion.FIRST_VERSION);
        row.setStemPreview(stemPreview);
        row.setStatus(status);
        row.setRemark(remark);
        questionMapper.insert(row);
        return row.getId();
    }

    private QuestionType requireType(Integer code) {
        return QuestionType.of(code).orElseThrow(() -> mismatch("题型取值只能是 1~6，实际 " + code));
    }

    private void requireCategory(Long categoryId) {
        QbCategory category = categoryId == null ? null : categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BizException.notFound(categoryId);
        }
    }

    /** PRD F3-1 规则 5：题干纯文本摘要，≤200 字符。 */
    private String stemPreview(JsonNode content) {
        JsonNode stem = content == null ? null : content.get("stem");
        String text = stem == null || !stem.isTextual() ? "" : stem.asText();
        return text.length() <= STEM_PREVIEW_MAX ? text : text.substring(0, STEM_PREVIEW_MAX);
    }

    private static int sortOf(ChildQuestionReq child, List<ChildQuestionReq> all) {
        return child.getSort() == null ? all.indexOf(child) + 1 : child.getSort();
    }

    private JsonNode withChildOrder(JsonNode content, List<Long> childIds) {
        ObjectNode node = content.deepCopy();
        ArrayNode order = JsonNodeFactory.instance.arrayNode();
        childIds.forEach(id -> order.add(String.valueOf(id)));
        node.set("childOrder", order);
        return node;
    }

    /**
     * 材料题改父题时保住 {@code childOrder}：请求体没带就沿用上一版的。
     *
     * <p>03-04 §2.3 允许父题改 {@code childOrder}（调整子题排序），
     * 但<b>不支持增删子题</b>。所以这里只做两件事：带了就用带的，没带就沿用；
     * 而「带的那份是不是恰好是这道材料题的全部子题」由 {@link #assertSameChildSet} 判。
     */
    private JsonNode preserveChildOrder(JsonNode content, JsonNode previousContent, Long parentId) {
        ObjectNode node = content.deepCopy();
        JsonNode submitted = node.get("childOrder");
        List<Long> actual = questionMapper.selectList(new LambdaQueryWrapper<QbQuestion>()
                        .eq(QbQuestion::getParentId, parentId))
                .stream().map(QbQuestion::getId).toList();
        if (submitted == null || submitted.isNull()) {
            JsonNode previous = previousContent == null ? null : previousContent.get("childOrder");
            if (previous != null) {
                node.set("childOrder", previous.deepCopy());
            }
            return node;
        }
        assertSameChildSet(submitted, actual);
        return node;
    }

    /**
     * 提交的 {@code childOrder} 必须<b>恰好</b>是这道材料题的全部子题。
     *
     * <p>少一个 = 那道子题从此在试卷上消失；多一个 = 把别人的子题拉进来。
     * 两者都<b>不会报错</b>，只是渲染出来的材料题少一道题或多一道题 ——
     * 正是「接口 200、字段齐全、结果错」。
     */
    private void assertSameChildSet(JsonNode submitted, List<Long> actual) {
        if (!submitted.isArray()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    ErrorCode.BAD_REQUEST.getMsg() + "：content.childOrder 必须是子题 ID 数组");
        }
        List<Long> given = new ArrayList<>();
        for (JsonNode item : submitted) {
            try {
                given.add(Long.valueOf(item.asText()));
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        ErrorCode.BAD_REQUEST.getMsg() + "：content.childOrder 里有非法的子题 ID");
            }
        }
        if (given.size() != actual.size() || !given.containsAll(actual)) {
            throw mismatch("content.childOrder 必须恰好是本材料题的全部子题（本题实际有 "
                    + actual.size() + " 个子题，提交了 " + given.size()
                    + " 个）—— 本接口不支持增删子题（03-04 §2.3）");
        }
    }

    private static BizException mismatch(String detail) {
        return new BizException(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH,
                ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getMsg() + "：" + detail);
    }
}
