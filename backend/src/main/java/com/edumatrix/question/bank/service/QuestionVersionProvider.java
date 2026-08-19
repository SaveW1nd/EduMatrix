package com.edumatrix.question.bank.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.question.QuestionType;
import com.edumatrix.common.question.QuestionVersionService;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.question.bank.entity.QbQuestion;
import com.edumatrix.question.bank.entity.QbQuestionVersion;
import com.edumatrix.question.bank.mapper.QbQuestionMapper;
import com.edumatrix.question.bank.mapper.QbQuestionVersionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link QuestionVersionService} 的实现，兼<b>版本表的唯一写入点</b>。
 *
 * <h2>「历史版本不可修改、不可删除」在这里落地成两句话</h2>
 * <ol>
 *   <li>本类<b>只调 {@code append}</b>。{@code QbQuestionVersionMapper} 不继承
 *       {@code BaseMapper}，所以「改一行历史版本」这件事在这里
 *       <b>写不出来</b>（方法不存在，编译不过）；
 *   <li>版本号推进必须在<b>主表行锁之内</b>（{@code QbQuestionMapper#lockForUpdate}）。
 *       没有锁时两个并发编辑都读到 {@code current_version = 2}、都算出 3，
 *       结果要么撞 {@code UK(question_id, version)}，要么更糟 ——
 *       两条都进去而 {@code current_version} 只反映其中一条。
 * </ol>
 *
 * <p>{@code id} / {@code create_by} / {@code update_by} 在这里显式赋值：
 * 窄 Mapper 不走 MyBatis-Plus 自己的插入方法，
 * {@code common/entity/AuditFieldHandler} 不会被触发。
 * {@code tenant_id} 仍由租户插件注入（契约 §2.9），本类一个字不写。
 */
@Service
public class QuestionVersionProvider implements QuestionVersionService {

    private final QbQuestionMapper questionMapper;
    private final QbQuestionVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public QuestionVersionProvider(QbQuestionMapper questionMapper,
                                   QbQuestionVersionMapper versionMapper,
                                   ObjectMapper objectMapper) {
        this.questionMapper = questionMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    // ============================================================ 对外（模块 15）

    @Override
    public Integer snapshot(Long questionId) {
        QbQuestion question = questionId == null ? null : questionMapper.selectById(questionId);
        return question == null ? null : question.getCurrentVersion();
    }

    @Override
    public Map<Long, Integer> snapshot(Collection<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Map.of();
        }
        List<QbQuestion> rows = questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                .in(QbQuestion::getId, questionIds));
        Map<Long, Integer> versions = new HashMap<>();
        for (QbQuestion row : rows) {
            versions.put(row.getId(), row.getCurrentVersion());
        }
        return Map.copyOf(versions);
    }

    @Override
    public QuestionSnapshot read(Long questionId, int version) {
        QbQuestionVersion row = versionMapper.selectSnapshot(questionId, version);
        if (row == null) {
            throw new BizException(ErrorCode.QUESTION_VERSION_NOT_FOUND);
        }
        QbQuestion question = questionMapper.selectById(questionId);
        QuestionType type = question == null ? null
                : QuestionType.of(question.getQuestionType()).orElse(null);
        return new QuestionSnapshot(questionId, row.getVersion(), type,
                parse(row.getContent()), parse(row.getCorrectAnswer()),
                row.getAnalysis(), row.getScoreDefault());
    }

    // ============================================================ 本模块内部

    /** 该题目的全部版本，按 {@code version} 倒序（03-04 §2.5）。 */
    public List<QbQuestionVersion> history(Long questionId) {
        return versionMapper.selectAllByQuestion(questionId);
    }

    /** 该题目当前版本行；不存在抛 30007。 */
    public QbQuestionVersion currentRow(QbQuestion question) {
        QbQuestionVersion row = versionMapper.selectSnapshot(question.getId(),
                question.getCurrentVersion());
        if (row == null) {
            throw new BizException(ErrorCode.QUESTION_VERSION_NOT_FOUND);
        }
        return row;
    }

    /** 原样返回，不存在时 {@code null}（调用方自行决定是 30007 还是别的）。 */
    public QbQuestionVersion rowOrNull(Long questionId, Integer version) {
        return version == null ? null : versionMapper.selectSnapshot(questionId, version);
    }

    /**
     * 追加一条版本快照并返回新版本号。
     *
     * <p><b>调用前必须已持有主表行锁</b>（见类注释）。版本号取
     * {@code MAX(version) + 1} 而不是 {@code current_version + 1}：
     * 两者在正常情况下相等，但前者对「{@code current_version} 被写歪」这种情形是自愈的，
     * 而 {@code UK(question_id, version)} 只认前者。
     */
    public int append(Long questionId, JsonNode content, JsonNode correctAnswer,
                      String analysis, java.math.BigDecimal scoreDefault) {
        Integer max = versionMapper.selectMaxVersion(questionId);
        int next = max == null ? QbQuestionVersion.FIRST_VERSION : max + 1;

        QbQuestionVersion row = new QbQuestionVersion();
        row.setId(IdWorker.nextId());
        row.setQuestionId(questionId);
        row.setVersion(next);
        row.setContent(write(content));
        row.setCorrectAnswer(write(correctAnswer));
        row.setAnalysis(analysis);
        row.setScoreDefault(scoreDefault);
        // 窄 Mapper 不走 MP 的插入方法，AuditFieldHandler 不会触发 —— 这里显式署名
        Long userId = TenantHelper.getUserId();
        row.setCreateBy(userId);
        row.setUpdateBy(userId);
        versionMapper.append(row);
        return next;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // 版本快照写入后不可变，所以这里读到坏 JSON 只可能是库被手工改过
            throw new IllegalStateException("qb_question_version 里的 JSON 无法解析（版本快照本应不可变）", e);
        }
    }

    private String write(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }
}
