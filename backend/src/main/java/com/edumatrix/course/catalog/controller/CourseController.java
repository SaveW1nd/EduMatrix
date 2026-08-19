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
import com.edumatrix.course.catalog.dto.CourseCreateReq;
import com.edumatrix.course.catalog.dto.CoursePageQuery;
import com.edumatrix.course.catalog.dto.CourseShelfReq;
import com.edumatrix.course.catalog.dto.CourseUpdateReq;
import com.edumatrix.course.catalog.service.CourseService;
import com.edumatrix.course.catalog.vo.CourseDetailVO;
import com.edumatrix.course.catalog.vo.CourseListVO;
import com.edumatrix.course.catalog.vo.CourseShelfVO;
import com.edumatrix.course.catalog.vo.CreatedIdVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 课程管理（03-03 §1，接口 1~6）。<b>接口总数不变</b>：这六条早在 03-03 目录表里。
 *
 * <h2>{@code perms} 已在 Flyway 基线就位，本模块不新增迁移</h2>
 * <p>契约 §10 附表 A 的 {@code course:course:list} / {@code :add} / {@code :edit} /
 * {@code :remove} / {@code :status} 五个标识与它们的 {@code sys_role_menu} 绑定
 * 都在 {@code V202608140000__init_menu_and_role_menu.sql} 里，
 * 绑给 {@code org_admin} 与 {@code teacher}，<b>{@code super_admin} 一条都没绑</b>。
 *
 * <p><b>最后这一点不是巧合，是本模块租户隔离的一道闸</b>：租户插件对超管会话<b>整体放行</b>
 * （{@code EduMatrixTenantLineHandler#ignoreTable} 第三个分支），
 * 超管若能调课程接口就是跨租户全可见且<b>不报错</b>。03-03 §0.2 也写着
 * 「平台超管（{@code super_admin}）不参与本模块业务操作」。
 * {@code CourseTenantIsolationIT} 有一条断言超管调本控制器返回 403 ——
 * 谁把 course 菜单绑给了 {@code super_admin}，那条会立刻红。
 *
 * <p>详情（§1.2）与章节树、课时列表等辅助读接口不单独发 {@code perms}，
 * 随所在页面的 {@code :list} 一并放行 —— 契约 §3.1 三条边界之 2。
 */
@RestController
@RequestMapping("/api/v1/course/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    /** 接口 1 §1.1 课程分页列表。 */
    @GetMapping
    @SaCheckPermission("course:course:list")
    public R<PageResult<CourseListVO>> page(CoursePageQuery query) {
        return R.ok(courseService.page(query));
    }

    /** 接口 2 §1.2 课程详情。随 {@code :list} 放行（契约 §3.1 边界 2）。 */
    @GetMapping("/{id}")
    @SaCheckPermission("course:course:list")
    public R<CourseDetailVO> detail(@PathVariable Long id) {
        return R.ok(courseService.detail(id));
    }

    /** 接口 3 §1.3 创建课程。{@code ownerNodeId} 由服务端写入，请求体不接受。 */
    @PostMapping
    @SaCheckPermission("course:course:add")
    @OperLog(module = "课程管理", action = "创建课程")
    public R<CreatedIdVO> create(@RequestBody @Valid CourseCreateReq req) {
        return R.ok(courseService.create(req));
    }

    /** 接口 4 §1.4 修改课程。要求 {@code owner_node_id} = 我的节点，否则 403。 */
    @PutMapping("/{id}")
    @SaCheckPermission("course:course:edit")
    @OperLog(module = "课程管理", action = "修改课程")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid CourseUpdateReq req) {
        courseService.update(id, req);
        return R.ok();
    }

    /** 接口 5 §1.5 删除课程。已上架须先下架（{@code 20005}）。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("course:course:remove")
    @OperLog(module = "课程管理", action = "删除课程")
    public R<Void> delete(@PathVariable Long id) {
        courseService.delete(id);
        return R.ok();
    }

    /** 接口 6 §1.6 课程上下架。 */
    @PutMapping("/{id}/shelf")
    @SaCheckPermission("course:course:status")
    @OperLog(module = "课程管理", action = "课程上下架")
    public R<CourseShelfVO> shelf(@PathVariable Long id, @RequestBody @Valid CourseShelfReq req) {
        return R.ok(courseService.shelf(id, req));
    }
}
