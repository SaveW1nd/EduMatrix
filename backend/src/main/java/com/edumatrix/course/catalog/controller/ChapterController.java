package com.edumatrix.course.catalog.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.operlog.OperLog;
import com.edumatrix.common.response.R;
import com.edumatrix.course.catalog.dto.ChapterCreateReq;
import com.edumatrix.course.catalog.dto.ChapterSortReq;
import com.edumatrix.course.catalog.dto.ChapterUpdateReq;
import com.edumatrix.course.catalog.service.ChapterService;
import com.edumatrix.course.catalog.vo.ChapterDeleteVO;
import com.edumatrix.course.catalog.vo.ChapterNodeVO;
import com.edumatrix.course.catalog.vo.CreatedIdVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 章节管理（03-03 §2，接口 7~11）。
 *
 * <p><b>两个路由前缀</b>：章节树与排序挂在 {@code /courses/{id}/...} 下（课程维度），
 * 增删改挂在 {@code /chapters/...} 下（章节维度）—— 03-03 目录表就是这么定的，
 * 与 {@code check_consistency.py} 的 C18（端点路径存在性）对齐。
 *
 * <p>章节树查询随课程编排页的 {@code course:lesson:list} 放行（契约 §3.1 边界 2）。
 */
@RestController
public class ChapterController {

    private final ChapterService chapterService;

    public ChapterController(ChapterService chapterService) {
        this.chapterService = chapterService;
    }

    /** 接口 7 §2.1 章节树查询。<b>读操作按 §0.2 可见性判定，不要求 owner</b>。 */
    @GetMapping("/api/v1/course/courses/{id}/chapters")
    @SaCheckPermission("course:lesson:list")
    public R<List<ChapterNodeVO>> tree(@PathVariable Long id) {
        return R.ok(chapterService.tree(id));
    }

    /** 接口 8 §2.2 创建章节。节下再建节 → {@code 20006}。 */
    @PostMapping("/api/v1/course/chapters")
    @SaCheckPermission("course:chapter:add")
    @OperLog(module = "课程编排", action = "创建章节")
    public R<CreatedIdVO> create(@RequestBody @Valid ChapterCreateReq req) {
        return R.ok(chapterService.create(req));
    }

    /** 接口 9 §2.3 修改章节。<b>只改名称</b>，层级与排序走接口 11。 */
    @PutMapping("/api/v1/course/chapters/{id}")
    @SaCheckPermission("course:chapter:edit")
    @OperLog(module = "课程编排", action = "修改章节")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid ChapterUpdateReq req) {
        chapterService.update(id, req);
        return R.ok();
    }

    /** 接口 10 §2.4 删除章节。级联逻辑删除其下节与课时，响应回显实际删除数。 */
    @DeleteMapping("/api/v1/course/chapters/{id}")
    @SaCheckPermission("course:chapter:remove")
    @OperLog(module = "课程编排", action = "删除章节")
    public R<ChapterDeleteVO> delete(@PathVariable Long id) {
        return R.ok(chapterService.delete(id));
    }

    /** 接口 11 §2.5 章节拖拽排序。全量提交；集合不一致 → {@code 20018}（<b>锁内判定</b>）。 */
    @PutMapping("/api/v1/course/courses/{id}/chapters/sort")
    @SaCheckPermission("course:chapter:sort")
    @OperLog(module = "课程编排", action = "章节排序")
    public R<Void> sort(@PathVariable Long id, @RequestBody @Valid ChapterSortReq req) {
        chapterService.sort(id, req);
        return R.ok();
    }
}
