package com.edumatrix.org.member.controller;

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
import com.edumatrix.org.member.dto.TeacherCreateReq;
import com.edumatrix.org.member.dto.TeacherPageQuery;
import com.edumatrix.org.member.dto.TeacherStudentQuery;
import com.edumatrix.org.member.dto.TeacherUpdateReq;
import com.edumatrix.org.member.service.OrgTeacherService;
import com.edumatrix.org.member.vo.MemberCreatedVO;
import com.edumatrix.org.member.vo.TeacherStudentVO;
import com.edumatrix.org.member.vo.TeacherVO;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;

/**
 * 教师管理接口（03-02 §5.1~§5.5，接口 11 / 12 / 13 / 14 / 15）。
 *
 * <p><b>本节路径 {@code {id}} 是教师<b>档案 ID</b>（{@code org_teacher.id}）</b>，
 * 不是节点 ID —— §5 导语逐字写着这一句。与 §4（管理员用节点 ID）相反。
 *
 * <p>接口 11 的 perms 拆分（F-30 定案）见 {@code OrgAdminController} 类注释。
 * <b>接口 15 用的是 {@code org:student:list}</b>（已绑 teacher）而不是人员管理那一组 ——
 * §5.5 逐字：「与接口 16（学生分页列表）传 {@code nodeId=教师节点ID} +
 * {@code directOnly=true} <b>等价</b>」，它返回的是学员不是教师，
 * 归「学员管理」那个页面的 {@code :list}。<b>这一处没有冲突，不属于 F-30。</b>
 */
@RestController
@RequestMapping("/api/v1/org/teachers")
public class OrgTeacherController {

    private final OrgTeacherService teacherService;

    public OrgTeacherController(OrgTeacherService teacherService) {
        this.teacherService = teacherService;
    }

    /**
     * 接口 11 §5.1 教师分页列表。{@code org_admin}；{@code teacher}（仅本人一条）。
     *
     * <p><b>{@code org:teacher:list} 是本轮从 {@code org:staff:list} 拆出来的</b>，
     * 拆的理由与三个 perms 的分工见 {@code OrgAdminController} 的类注释（F-30 定案）。
     *
     * <p><b>教师只看到自己一行是数据权限自然的结果，没有特判</b>：教师子树里只可能有学生，
     * 按 {@code node_type = 2} 过滤后只剩本人，而 SQL 的 {@code (n.id = #{rootId} OR ...)}
     * 分支把本人那行包含在内。
     */
    @GetMapping
    @SaCheckPermission("org:teacher:list")
    public R<PageResult<TeacherVO>> page(TeacherPageQuery query) {
        return R.ok(teacherService.page(query));
    }

    /** 接口 12 §5.2 新建教师。<b>三写一事务</b>；工号机构内唯一 → {@code 10201}。 */
    @PostMapping
    @SaCheckPermission("org:teacher:add")
    @OperLog(module = "人员管理", action = "新建教师", saveParams = false)
    public R<MemberCreatedVO> create(@Valid @RequestBody TeacherCreateReq req) {
        return R.ok(teacherService.create(req));
    }

    /** 接口 13 §5.3 修改教师。<b>调岗不走本接口</b>，须用接口 4 移动节点。 */
    @PutMapping("/{id}")
    @SaCheckPermission("org:teacher:edit")
    @OperLog(module = "人员管理", action = "修改教师")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody TeacherUpdateReq req) {
        teacherService.update(id, req);
        return R.ok();
    }

    /** 接口 14 §5.4 删除教师。<b>名下仍有学员 → {@code 10206}</b>（含已退课/已归档的）。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("org:teacher:remove")
    @OperLog(module = "人员管理", action = "删除教师")
    public R<Void> delete(@PathVariable("id") Long id) {
        teacherService.delete(id);
        return R.ok();
    }

    /**
     * 接口 15 §5.5 教师名下学员列表。{@code org_admin}；{@code teacher}（仅本人的 {@code {id}}）。
     *
     * <p>教师查他人的 {@code {id}} 返回 <b>404</b>（不暴露存在性）——
     * 由 {@code SubtreeScopeHelper} 的子树判定天然承担：别人的教师节点不在他的子树里。
     */
    @GetMapping("/{id}/students")
    @SaCheckPermission("org:student:list")
    public R<PageResult<TeacherStudentVO>> students(@PathVariable("id") Long id,
                                                    TeacherStudentQuery query) {
        return R.ok(teacherService.students(id, query));
    }
}
