package com.edumatrix.question.bank.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.question.bank.dto.QuestionCreateReq;
import com.edumatrix.question.bank.dto.QuestionPageQuery;
import com.edumatrix.question.bank.dto.QuestionStatusReq;
import com.edumatrix.question.bank.dto.QuestionUpdateReq;
import com.edumatrix.question.bank.service.QuestionQueryService;
import com.edumatrix.question.bank.service.QuestionService;
import com.edumatrix.question.bank.vo.QuestionCreatedVO;
import com.edumatrix.question.bank.vo.QuestionDetailVO;
import com.edumatrix.question.bank.vo.QuestionListVO;
import com.edumatrix.question.bank.vo.QuestionSnapshotVO;
import com.edumatrix.question.bank.vo.QuestionStatusVO;
import com.edumatrix.question.bank.vo.QuestionUpdatedVO;
import com.edumatrix.question.bank.vo.QuestionVersionMetaVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 题目（03-04 §2，接口 5~12 共八个）。
 *
 * <p>判定顺序统一走 {@code QuestionAccessGuard} 的四步表：
 * {@code @SaCheckPermission} → 查到行 → 可见性(404) → 写权限(403) → 业务码。
 * <b>被授权者是「只读可用」</b>：可见但改 / 停用 / 删一律 403（03-04 §0.1）。
 *
 * <p>四个读接口（列表 / 详情 / 版本列表 / 版本快照）<b>不单独发按钮标识</b>，
 * 随题库管理页的 {@code question:question:list} 一并放行 —— 契约 §3.1 边界 2。
 */
@RestController
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionQueryService queryService;

    public QuestionController(QuestionService questionService, QuestionQueryService queryService) {
        this.questionService = questionService;
        this.queryService = queryService;
    }

    /**
     * 接口 5 §2.1 分页查询题目。
     *
     * <p>返回<b>自有 ∪ 被授权且在有效期内</b>的题目，逐行以 {@code grantType} 标来源。
     * <b>不回溯祖先链</b>：上级拥有而未显式授权给我的题，本接口不返回。
     * <b>材料题只出父题</b>（固定过滤 {@code parent_id = 0}）。
     */
    @GetMapping("/api/v1/question/questions")
    @SaCheckPermission("question:question:list")
    public R<PageResult<QuestionListVO>> page(QuestionPageQuery query) {
        return R.ok(queryService.page(query));
    }

    /** 接口 8 §2.4 题目详情（当前版本，含答案与解析 —— 教师侧）。不可见 → 404。 */
    @GetMapping("/api/v1/question/questions/{id}")
    @SaCheckPermission("question:question:list")
    public R<QuestionDetailVO> detail(@PathVariable Long id) {
        return R.ok(queryService.detail(id));
    }

    /** 接口 9 §2.5 题目版本列表（按 version 倒序，不分页）。 */
    @GetMapping("/api/v1/question/questions/{id}/versions")
    @SaCheckPermission("question:question:list")
    public R<List<QuestionVersionMetaVO>> versions(@PathVariable Long id) {
        return R.ok(queryService.versions(id));
    }

    /**
     * 接口 10 §2.6 题目指定版本快照。版本不存在 → {@code 30007}。
     *
     * <p><b>可见性 404 在 30007 之前判</b>：反过来 30007 就成了存在性探针。
     */
    @GetMapping("/api/v1/question/questions/{id}/versions/{version}")
    @SaCheckPermission("question:question:list")
    public R<QuestionSnapshotVO> snapshot(@PathVariable Long id, @PathVariable Integer version) {
        return R.ok(queryService.snapshot(id, version));
    }

    /**
     * 接口 6 §2.2 创建题目。
     *
     * <p>{@code owner_node_id} 由服务端强制写入创建者所在节点，<b>请求体不接受</b>。
     * 结构与题型不匹配 → {@code 30006}；判断题答案是字符串 → {@code 400}。
     */
    @PostMapping("/api/v1/question/questions")
    @SaCheckPermission("question:question:add")
    @OperLog(module = "题库", action = "创建题目")
    public R<QuestionCreatedVO> create(@RequestBody @Valid QuestionCreateReq req) {
        return R.ok(questionService.create(req));
    }

    /**
     * 接口 7 §2.3 修改题目（自动生成新版本）。
     *
     * <p>内容四项任一变更 → 写新版本并回写 {@code current_version}；
     * 只改分类/难度/备注 → {@code versionCreated=false}。<b>题型不可改</b>。
     */
    @PutMapping("/api/v1/question/questions/{id}")
    @SaCheckPermission("question:question:edit")
    @OperLog(module = "题库", action = "修改题目")
    public R<QuestionUpdatedVO> update(@PathVariable Long id,
                                       @RequestBody @Valid QuestionUpdateReq req) {
        return R.ok(questionService.update(id, req));
    }

    /** 接口 11 §2.7 启用/停用题目。停用且被作业引用 → {@code 30001}。 */
    @PutMapping("/api/v1/question/questions/{id}/status")
    @SaCheckPermission("question:question:status")
    @OperLog(module = "题库", action = "启用停用题目")
    public R<QuestionStatusVO> changeStatus(@PathVariable Long id,
                                            @RequestBody @Valid QuestionStatusReq req) {
        return R.ok(questionService.changeStatus(id, req));
    }

    /** 接口 12 §2.8 删除题目（逻辑删除）。被任意状态作业引用 → {@code 30005}。 */
    @DeleteMapping("/api/v1/question/questions/{id}")
    @SaCheckPermission("question:question:remove")
    @OperLog(module = "题库", action = "删除题目")
    public R<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return R.ok();
    }
}
