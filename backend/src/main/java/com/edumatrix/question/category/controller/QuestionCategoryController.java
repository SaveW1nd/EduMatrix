package com.edumatrix.question.category.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.question.category.dto.CategoryCreateReq;
import com.edumatrix.question.category.dto.CategoryUpdateReq;
import com.edumatrix.question.category.service.QuestionCategoryService;
import com.edumatrix.question.category.vo.CategoryCreatedVO;
import com.edumatrix.question.category.vo.CategoryNodeVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 题库分类（03-04 §1，接口 1~4）。
 *
 * <p>分类树查询<b>不单独发按钮标识</b>，随题库管理页的
 * {@code question:question:list} 一并放行 —— 契约 §3.1 边界 2：
 * 「同一页面内的辅助读接口（详情、树、版本列表等）随该页 {@code :list} 一并放行」。
 *
 * <p>三个写操作用 {@code question:category:add / edit / remove}。
 * 按 03-04 §1.2 与 PRD F3-1 规则 8，它们<b>仅 {@code org_admin}</b> ——
 * 这一点靠 {@code sys_role_menu} 的绑定实现，不在这里加角色门（F-72 定案，
 * 理由见 {@code QuestionCategoryService} 类注释）。
 */
@RestController
public class QuestionCategoryController {

    private final QuestionCategoryService categoryService;

    public QuestionCategoryController(QuestionCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 接口 1 §1.1 获取题库分类树。 */
    @GetMapping("/api/v1/question/categories")
    @SaCheckPermission("question:question:list")
    public R<List<CategoryNodeVO>> tree(@RequestParam(required = false) String keyword) {
        return R.ok(categoryService.tree(keyword));
    }

    /** 接口 2 §1.2 新增题库分类。同级重名 / 名称超长 → 400。 */
    @PostMapping("/api/v1/question/categories")
    @SaCheckPermission("question:category:add")
    @OperLog(module = "题库分类", action = "新增分类")
    public R<CategoryCreatedVO> create(@RequestBody @Valid CategoryCreateReq req) {
        return R.ok(categoryService.create(req));
    }

    /** 接口 3 §1.3 修改题库分类。非法移动（自身或子孙）/ 重名 → 400。 */
    @PutMapping("/api/v1/question/categories/{id}")
    @SaCheckPermission("question:category:edit")
    @OperLog(module = "题库分类", action = "修改分类")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid CategoryUpdateReq req) {
        categoryService.update(id, req);
        return R.ok();
    }

    /** 接口 4 §1.4 删除题库分类（逻辑删除）。分类非空 → {@code 30004}。 */
    @DeleteMapping("/api/v1/question/categories/{id}")
    @SaCheckPermission("question:category:remove")
    @OperLog(module = "题库分类", action = "删除分类")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok();
    }
}
