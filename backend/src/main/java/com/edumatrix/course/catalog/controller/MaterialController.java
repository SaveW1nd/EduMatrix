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
import com.edumatrix.course.catalog.dto.MaterialCreateReq;
import com.edumatrix.course.catalog.dto.MaterialPageQuery;
import com.edumatrix.course.catalog.dto.MaterialUpdateReq;
import com.edumatrix.course.catalog.service.MaterialService;
import com.edumatrix.course.catalog.vo.CreatedIdVO;
import com.edumatrix.course.catalog.vo.MaterialDetailVO;
import com.edumatrix.course.catalog.vo.MaterialListVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 图文资料管理（03-03 §4，接口 17~21）。
 *
 * <p>富文本 {@code content} 在 {@code MaterialService} 里<b>落库前</b>经
 * {@code common/xss/HtmlSanitizer} 白名单过滤（PRD F2-2 规则 1）；
 * 出参时由 {@code MaterialContentRewriter} 把内嵌图片的 {@code fileId} 占位
 * 重写为 ≤30 分钟签名地址（D-3）。
 */
@RestController
@RequestMapping("/api/v1/course/materials")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    /** 接口 17 §4.1 图文资料分页列表。 */
    @GetMapping
    @SaCheckPermission("course:material:list")
    public R<PageResult<MaterialListVO>> page(MaterialPageQuery query) {
        return R.ok(materialService.page(query));
    }

    /** 接口 18 §4.2 图文资料详情。 */
    @GetMapping("/{id}")
    @SaCheckPermission("course:material:list")
    public R<MaterialDetailVO> detail(@PathVariable Long id) {
        return R.ok(materialService.detail(id));
    }

    /** 接口 19 §4.3 创建图文资料。 */
    @PostMapping
    @SaCheckPermission("course:material:add")
    @OperLog(module = "课程编排", action = "创建图文资料")
    public R<CreatedIdVO> create(@RequestBody @Valid MaterialCreateReq req) {
        return R.ok(materialService.create(req));
    }

    /** 接口 20 §4.4 修改图文资料。附件全量覆盖。 */
    @PutMapping("/{id}")
    @SaCheckPermission("course:material:edit")
    @OperLog(module = "课程编排", action = "修改图文资料")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid MaterialUpdateReq req) {
        materialService.update(id, req);
        return R.ok();
    }

    /** 接口 21 §4.5 删除图文资料。被未删除课时引用时拒绝 → {@code 20010}。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("course:material:remove")
    @OperLog(module = "课程编排", action = "删除图文资料")
    public R<Void> delete(@PathVariable Long id) {
        materialService.delete(id);
        return R.ok();
    }
}
