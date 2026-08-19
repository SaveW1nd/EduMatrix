package com.edumatrix.system.log.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.response.R;
import com.edumatrix.system.log.dto.LoginLogPageQuery;
import com.edumatrix.system.log.dto.OperLogPageQuery;
import com.edumatrix.system.log.service.LogQueryService;
import com.edumatrix.system.log.vo.LoginLogVO;
import com.edumatrix.system.log.vo.OperLogVO;

import cn.dev33.satoken.annotation.SaCheckPermission;

/**
 * 日志查询两接口（03-01 §8.1 / §8.2）。<b>接口总数不变</b>：两条都在 03-01 目录表里。
 *
 * <h2>这两个<b>要</b>加 {@code @SaCheckPermission}，与同模块的文件三接口相反</h2>
 * <p>差别不是随意的：契约 §3.1 边界 0 免注解的是<b>学生端接口</b>，
 * 而 §8.1/§8.2 的允许角色只有 {@code super_admin} 与 {@code org_admin}
 * （两节的权限段逐字），学生根本不在其中。
 *
 * <p>{@code system:log:login} / {@code system:log:oper} 是契约 §3.1
 * 「遗留例外」里点名<b>永久豁免</b>的两个（第三段是日志种类而非动作词）——
 * 原文：「三者已在多处分册引用，改名需同步修订分册、菜单初始化脚本与后端注解三处，
 * 而收益只是命名整齐，故<b>定案为永久豁免、原样保留并在此登记</b>」。
 * <b>不要"顺手"把它们改成 {@code :list}</b>：那会让菜单初始化数据与注解分叉，
 * 表现是接口 403 而菜单还在。
 *
 * <h2>没有删除接口</h2>
 * <p>§8 引言逐字：「仅提供查询，<b>不提供删除接口</b>（归档清理为运维行为）」。
 * 契约 §7.2 第 5 条：两张表保留 ≥ 6 个月且不参与"删除请求"的清理。
 * 也因此本类<b>没有任何写端点</b>，{@code OperLogCoverageTest} 扫不到它。
 */
@RestController
@RequestMapping("/api/v1/system/logs")
public class SysLogController {

    private final LogQueryService logQueryService;

    public SysLogController(LogQueryService logQueryService) {
        this.logQueryService = logQueryService;
    }

    /**
     * §8.1 分页查询登录日志。{@code org_admin} 仅本租户；{@code super_admin} 平台全量
     * （可传 {@code tenantId} 过滤）。
     */
    @GetMapping("/login")
    @SaCheckPermission("system:log:login")
    public R<PageResult<LoginLogVO>> loginLogs(LoginLogPageQuery query) {
        return R.ok(logQueryService.pageLoginLogs(query));
    }

    /** §8.2 分页查询操作日志 —— F-25 列的第四件事，切面写的行在这里被查出来。 */
    @GetMapping("/oper")
    @SaCheckPermission("system:log:oper")
    public R<PageResult<OperLogVO>> operLogs(OperLogPageQuery query) {
        return R.ok(logQueryService.pageOperLogs(query));
    }
}
