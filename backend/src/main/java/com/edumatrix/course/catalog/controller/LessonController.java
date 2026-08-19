package com.edumatrix.course.catalog.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.course.catalog.dto.LessonCreateReq;
import com.edumatrix.course.catalog.dto.LessonPageQuery;
import com.edumatrix.course.catalog.dto.LessonUpdateReq;
import com.edumatrix.course.catalog.service.LessonService;
import com.edumatrix.course.catalog.vo.CreatedIdVO;
import com.edumatrix.course.catalog.vo.LessonDetailVO;
import com.edumatrix.course.catalog.vo.LessonListVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 课时管理（03-03 §3，接口 12~16）。
 *
 * <p>列表与详情随课程编排页的 {@code course:lesson:list} 放行（契约 §3.1 边界 2）。
 */
@RestController
@RequestMapping("/api/v1/course/lessons")
public class LessonController {

    private final LessonService lessonService;

    public LessonController(LessonService lessonService) {
        this.lessonService = lessonService;
    }

    /** 接口 12 §3.1 课时分页列表。 */
    @GetMapping
    @SaCheckPermission("course:lesson:list")
    public R<PageResult<LessonListVO>> page(@Valid LessonPageQuery query) {
        return R.ok(lessonService.page(query));
    }

    /** 接口 13 §3.2 课时详情。 */
    @GetMapping("/{id}")
    @SaCheckPermission("course:lesson:list")
    public R<LessonDetailVO> detail(@PathVariable Long id) {
        return R.ok(lessonService.detail(id));
    }

    /** 接口 14 §3.3 创建课时。判定顺序见 {@code LessonService} 类注释的表。 */
    @PostMapping
    @SaCheckPermission("course:lesson:add")
    @OperLog(module = "课程编排", action = "创建课时")
    public R<CreatedIdVO> create(@RequestBody @Valid LessonCreateReq req) {
        return R.ok(lessonService.create(req));
    }

    /** 接口 15 §3.4 修改课时。 */
    @PutMapping("/{id}")
    @SaCheckPermission("course:lesson:edit")
    @OperLog(module = "课程编排", action = "修改课时")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid LessonUpdateReq req) {
        lessonService.update(id, req);
        return R.ok();
    }

    /** 接口 16 §3.5 删除课时。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("course:lesson:remove")
    @OperLog(module = "课程编排", action = "删除课时")
    public R<Void> delete(@PathVariable Long id) {
        lessonService.delete(id);
        return R.ok();
    }
}
