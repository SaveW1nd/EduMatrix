package com.edumatrix.org.member.controller;

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
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.org.member.dto.AssignTeacherBatchReq;
import com.edumatrix.org.member.dto.AssignTeacherReq;
import com.edumatrix.org.member.dto.StudentArchiveReq;
import com.edumatrix.org.member.dto.StudentCreateReq;
import com.edumatrix.org.member.dto.StudentPageQuery;
import com.edumatrix.org.member.dto.StudentQuitReq;
import com.edumatrix.org.member.dto.StudentUnarchiveReq;
import com.edumatrix.org.member.dto.StudentUpdateReq;
import com.edumatrix.org.member.dto.TransferAdminReq;
import com.edumatrix.org.member.service.OrgStudentService;
import com.edumatrix.org.member.service.StudentAssignService;
import com.edumatrix.org.member.service.StudentChangeLogService;
import com.edumatrix.org.member.service.StudentLifecycleService;
import com.edumatrix.org.member.vo.BatchAssignedVO;
import com.edumatrix.org.member.vo.MemberCreatedVO;
import com.edumatrix.org.member.vo.StudentArchivedVO;
import com.edumatrix.org.member.vo.StudentAssignedVO;
import com.edumatrix.org.member.vo.StudentChangeLogVO;
import com.edumatrix.org.member.vo.StudentQuitVO;
import com.edumatrix.org.member.vo.StudentUnarchivedVO;
import com.edumatrix.org.member.vo.StudentVO;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import jakarta.validation.Valid;

/**
 * 学生管理接口（03-02 §6.1~§6.11，接口 16~26，共 11 个）。
 *
 * <p><b>本节路径 {@code {id}} 是学生<b>档案 ID</b>（{@code org_student.id}）</b>，
 * §6 导语逐字。这一点有一个结构性后果：<b>没有 {@code org_student} 档案的学生节点
 * （03-01 §2.2 建出的孤儿数据，F-22）在本节 11 个接口里一个都寻址不到</b> ——
 * 不是被过滤掉，是它连主键都没有。模块 06 的接口 4（移动节点）以节点 ID 寻址，
 * 那才是唯一能碰到它的入口，所以那里必须就「查不到档案行时放不放行」做决定，
 * <b>本模块不必、也无法做同一个决定</b>。详见 04-实施计划.md §E 的 F-22 新证据段。
 *
 * <h2>路由不会互相吃掉</h2>
 * <p>{@code POST /students/archive}、{@code /transfer-admin}、{@code /assign-teacher-batch}
 * 都是「{@code /students} 后一个字面量段」，而<b>本控制器没有 {@code POST /students/{id}}</b>，
 * 因此不存在「{@code {id}} 匹配到 {@code archive}」的可能。
 * 其余带 {@code {id}} 的写接口都是两段（{@code /{id}/quit} 等），段数就不同。
 *
 * <h2>接口 17 的权限：按<b>菜单绑定数据 + PRD</b>，不按 §6.2 的权限栏</h2>
 * <p>{@code org:student:add} 已绑 {@code org_admin} 与 <b>{@code teacher}</b>
 * （菜单行 {@code ...100301} 的 remark 逐字写着「teacher 建的学生直接挂本人节点
 * （PRD F1-3 规则 4）」）。而 §6.2 的权限栏写的是「仅 {@code org_admin}」——
 * <b>三比一</b>（PRD F1-3 规则 4、04-实施计划.md 模块 07 规则 4、菜单数据）。
 * 照「仅 org_admin」实现会让「<b>教师创建 → 即刻成为其名下学员</b>」这半句
 * <b>永远跑不到</b>，而它是模块 07 的完成判据之一。
 * <b>已登记为 04-实施计划.md §E 的 F-29</b>，分册待订正。
 */
@RestController
@RequestMapping("/api/v1/org/students")
public class OrgStudentController {

    private final OrgStudentService studentService;
    private final StudentAssignService assignService;
    private final StudentLifecycleService lifecycleService;
    private final StudentChangeLogService changeLogService;

    public OrgStudentController(OrgStudentService studentService,
                                StudentAssignService assignService,
                                StudentLifecycleService lifecycleService,
                                StudentChangeLogService changeLogService) {
        this.studentService = studentService;
        this.assignService = assignService;
        this.lifecycleService = lifecycleService;
        this.changeLogService = changeLogService;
    }

    /** 接口 16 §6.1 学生分页列表。{@code org_admin}；{@code teacher}（自动限定为名下学员）。 */
    @GetMapping
    @SaCheckPermission("org:student:list")
    public R<PageResult<StudentVO>> page(StudentPageQuery query) {
        return R.ok(studentService.page(query));
    }

    /**
     * 接口 17 §6.2 创建学生。<b>三写一事务</b>；权限取舍见类注释（F-29）。
     *
     * <p><b>{@code guardianConsent != true} 在参数校验阶段就返回 {@code 400}</b>，
     * 请求根本进不到 Service —— PRD F7-1 的自检项「无任何节点/账号/档案产生」
     * 因此是结构上成立的。
     */
    @PostMapping
    @SaCheckPermission("org:student:add")
    @OperLog(module = "学员管理", action = "创建学生", saveParams = false)
    public R<MemberCreatedVO> create(@Valid @RequestBody StudentCreateReq req) {
        return R.ok(studentService.create(req));
    }

    /** 接口 18 §6.3 修改学生。已退课/已归档不可修改 → {@code 10203}。 */
    @PutMapping("/{id}")
    @SaCheckPermission("org:student:edit")
    @OperLog(module = "学员管理", action = "修改学生")
    public R<Void> update(@PathVariable("id") Long id, @Valid @RequestBody StudentUpdateReq req) {
        studentService.update(id, req);
        return R.ok();
    }

    /** 接口 19 §6.4 删除学生。<b>学习记录一律保留不删</b>（契约 §2.5 规则 5）。 */
    @DeleteMapping("/{id}")
    @SaCheckPermission("org:student:remove")
    @OperLog(module = "学员管理", action = "删除学生")
    public R<Void> delete(@PathVariable("id") Long id) {
        studentService.delete(id);
        return R.ok();
    }

    /** 接口 20 §6.5 分配导师 = 把学生节点移动到该导师节点下（走模块 06 的移动事务）。 */
    @PostMapping("/{id}/assign-teacher")
    @SaCheckPermission("org:student:assign")
    @OperLog(module = "学员管理", action = "分配导师")
    public R<StudentAssignedVO> assign(@PathVariable("id") Long id,
                                       @Valid @RequestBody AssignTeacherReq req) {
        return R.ok(assignService.assign(id, req));
    }

    /** 接口 21 §6.6 批量分配导师。<b>整批成功或整批回滚，不做部分成功</b>（规则 6）。 */
    @PostMapping("/assign-teacher-batch")
    @SaCheckPermission("org:student:assign")
    @OperLog(module = "学员管理", action = "批量分配导师")
    public R<BatchAssignedVO> assignBatch(@Valid @RequestBody AssignTeacherBatchReq req) {
        return R.ok(assignService.assignBatch(req));
    }

    /** 接口 22 §6.7 转交给其他管理员。<b>跨子树转交被禁止</b>，须由共同上级执行。 */
    @PostMapping("/transfer-admin")
    @SaCheckPermission("org:student:transfer")
    @OperLog(module = "学员管理", action = "转交管理员")
    public R<com.edumatrix.org.member.vo.TransferredVO> transferAdmin(
            @Valid @RequestBody TransferAdminReq req) {
        return R.ok(assignService.transferAdmin(req));
    }

    /** 接口 23 §6.8 学生退课。<b>流失口径的唯一数据来源</b>；不移动节点。 */
    @PostMapping("/{id}/quit")
    @SaCheckPermission("org:student:quit")
    @OperLog(module = "学员管理", action = "学生退课")
    public R<StudentQuitVO> quit(@PathVariable("id") Long id,
                                 @Valid @RequestBody StudentQuitReq req) {
        return R.ok(lifecycleService.quit(id, req));
    }

    /**
     * 接口 24 §6.9 批量毕业归档。<b>仅管理员可执行</b>（PRD F1-8 规则 5，
     * {@code org:student:archive} 未绑 teacher，教师调用返回 403）。
     *
     * <p><b>{@code archiveReason=2} 启动 30 日脱敏倒计时，不可逆。</b>
     */
    @PostMapping("/archive")
    @SaCheckPermission("org:student:archive")
    @OperLog(module = "学员管理", action = "批量毕业归档")
    public R<StudentArchivedVO> archive(@Valid @RequestBody StudentArchiveReq req) {
        return R.ok(lifecycleService.archive(req));
    }

    /** 接口 25 §6.10 归档恢复。已脱敏 → {@code 10209}；在读 → {@code 10204}。仅管理员。 */
    @PostMapping("/{id}/unarchive")
    @SaCheckPermission("org:student:unarchive")
    @OperLog(module = "学员管理", action = "归档恢复")
    public R<StudentUnarchivedVO> unarchive(@PathVariable("id") Long id,
                                            @Valid @RequestBody StudentUnarchiveReq req) {
        return R.ok(lifecycleService.unarchive(id, req));
    }

    /**
     * 接口 26 §6.11 学生异动轨迹。{@code org_admin}；{@code teacher}（仅名下学员）；
     * {@code student}（<b>仅本人</b>，{@code {id}} 非本人时 403）。
     *
     * <h2>为什么这里是 {@code @SaCheckOr} 而不是单个 {@code @SaCheckPermission}</h2>
     * <p>这是本模块<b>唯一一个学生角色也能调</b>的接口，而契约 §3.1 边界 0 逐字：
     * 「<b>学生端接口一律不加 {@code @SaCheckPermission}，因而不发 {@code perms}</b>，
     * 只按角色与数据权限判定」——{@code student} 在 {@code sys_role_menu} 里<b>一行绑定都没有</b>
     * （F-1 定案时专门删掉了那 2 条）。
     *
     * <p>于是单个 {@code @SaCheckPermission("org:changeLog:list")} 会让学生拿 403，
     * 而完全不加又会让管理员与教师失去 perms 这道门。{@code @SaCheckOr} 让两侧各按各的判据：
     * 管理员 / 教师走 perms，学生走角色，<b>「仅本人」那一半在 Service 里判并返回 403</b>
     * （契约 §2.4 三分法：「我没资格做这件事」→ 403，不是 404）。
     *
     * <p><b>这不是给学生发了 perms</b>：{@code /auth/me} 返回的 {@code perms}
     * 仍然一个都没有，边界 0 没有被破坏。
     */
    @GetMapping("/{id}/change-logs")
    @SaCheckOr(
            permission = @SaCheckPermission("org:changeLog:list"),
            role = @SaCheckRole("student"))
    public R<List<StudentChangeLogVO>> changeLogs(@PathVariable("id") Long id) {
        return R.ok(changeLogService.list(id));
    }
}
