package com.edumatrix.system.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.edumatrix.auth.service.LoginLogService;
import com.edumatrix.common.operlog.OperLogWriter;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.support.IntegrationTest;
import com.edumatrix.support.TestCurrentContextProvider;
import com.edumatrix.system.log.dto.LoginLogPageQuery;
import com.edumatrix.system.log.dto.OperLogPageQuery;
import com.edumatrix.system.log.service.LogQueryService;
import com.edumatrix.system.log.vo.LoginLogVO;
import com.edumatrix.system.log.vo.OperLogVO;

/**
 * §8.1 / §8.2 日志查询 —— F-25 列的第四件事。
 *
 * <h2>本类里最重要的是那两条 {@code LEFT JOIN} 用例</h2>
 * <p>两张日志表都<b>没有</b> {@code real_name} 列，必须回 {@code sys_user} 取；
 * 而两边的 {@code user_id} 都<b>合法地为 {@code null}</b>：
 * <ul>
 *   <li>{@code sys_login_log}：DDL 注释逐字「登录失败且<b>账号不存在</b>时为 {@code NULL}」——
 *       那正是撞库探测留下的行；</li>
 *   <li>{@code sys_oper_log}：Job / Worker / 事件消费路径没有操作人
 *       （{@code OperLogWriter} 的四档表；填 0 会指向平台超管，是假审计记录）——
 *       模块 07 的删除请求脱敏留痕（PRD F7-3）就是这种行。</li>
 * </ul>
 * <p>写成内连接的话，<b>这两类行会被整批过滤掉</b>，而表现是：
 * 接口 200、有数据、分页正常，就是查不到那些行。没有任何东西报错。
 * 把 {@code LEFT JOIN} 改成 {@code JOIN} → 本类的两条立刻红。
 */
@IntegrationTest
class SysLogQueryIT {

    private static final long TENANT_A = 1953827104412590001L;
    private static final long ADMIN_USER_ID = 1953827104412590102L;
    private static final long ADMIN_NODE_ID = 1953827104412590001L;

    @Autowired
    private LogQueryService logQueryService;

    @Autowired
    private LoginLogService loginLogService;

    @Autowired
    private OperLogWriter operLogWriter;

    @Autowired
    private TestCurrentContextProvider contextProvider;

    @BeforeEach
    void asOrgAdmin() {
        contextProvider.asTenantUser(TENANT_A, ADMIN_USER_ID, ADMIN_NODE_ID);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    // ====================================================================
    // §8.1 登录日志
    // ====================================================================

    @Test
    @DisplayName("T-13【LEFT JOIN】账号不存在的失败登录仍然查得到（改成内连接即红）")
    void failedLoginWithUnknownAccountIsStillListed() {
        String ghost = unique("ghost");
        // user_id = null —— 撞库探测的典型形态
        loginLogService.recordFailure(null, ghost, TENANT_A, "用户名或密码错误");

        LoginLogPageQuery query = new LoginLogPageQuery();
        query.setUsername(ghost);
        PageResult<LoginLogVO> page = logQueryService.pageLoginLogs(query);

        assertThat(page.getTotal())
                .as("内连接会把撞库失败的记录整批过滤掉 —— 而那是这张表最重要的用途")
                .isEqualTo(1);
        LoginLogVO row = page.getList().get(0);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getRealName()).as("查不到关联用户时为 null，不是查不到这一行").isNull();
        assertThat(row.getStatus()).isEqualTo(1);
        assertThat(row.getMsg()).isEqualTo("用户名或密码错误");
    }

    @Test
    @DisplayName("【保留侧】账号存在时 realName 要真的取回来（否则 LEFT JOIN 写了等于没写）")
    void successfulLoginCarriesRealNameFromSysUser() {
        String username = unique("13800000001");
        loginLogService.recordSuccess(ADMIN_USER_ID, username, TENANT_A);

        LoginLogPageQuery query = new LoginLogPageQuery();
        query.setUsername(username);
        PageResult<LoginLogVO> page = logQueryService.pageLoginLogs(query);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList().get(0).getRealName())
                .as("§8.1 响应示例里有 realName，而 sys_login_log 没有这一列")
                .isEqualTo("示例机构管理员");
    }

    @Test
    @DisplayName("status 过滤生效（0 成功 1 失败）")
    void statusFilterWorks() {
        String username = unique("filter");
        loginLogService.recordSuccess(ADMIN_USER_ID, username, TENANT_A);
        loginLogService.recordFailure(ADMIN_USER_ID, username, TENANT_A, "验证码错误或已过期");

        LoginLogPageQuery all = new LoginLogPageQuery();
        all.setUsername(username);
        assertThat(logQueryService.pageLoginLogs(all).getTotal()).isEqualTo(2);

        LoginLogPageQuery failedOnly = new LoginLogPageQuery();
        failedOnly.setUsername(username);
        failedOnly.setStatus(1);
        PageResult<LoginLogVO> failed = logQueryService.pageLoginLogs(failedOnly);
        assertThat(failed.getTotal()).isEqualTo(1);
        assertThat(failed.getList().get(0).getMsg()).isEqualTo("验证码错误或已过期");
    }

    // ====================================================================
    // §8.2 操作日志 —— F-25 第四件事
    // ====================================================================

    @Test
    @DisplayName("T-13'【LEFT JOIN】无操作人的 Job 留痕仍然查得到（模块 07 的脱敏留痕就是这种行）")
    void jobWrittenRowWithoutUserIdIsStillListed() {
        String action = unique("脱敏");
        // user_id = null + 显式 tenantId —— 契约 §2.8 规则 1 的 Job 路径
        operLogWriter.write(null, "学生管理", action, "AnonymizeArchivedStudentJob#1",
                null, null, OperLogWriter.STATUS_SUCCESS, null, 0, TENANT_A);

        OperLogPageQuery query = new OperLogPageQuery();
        query.setAction(action);
        PageResult<OperLogVO> page = logQueryService.pageOperLogs(query);

        assertThat(page.getTotal())
                .as("内连接会把全部无人值守的操作记录过滤掉，"
                        + "含 PRD F7-3 的删除请求脱敏留痕与契约 §2.8 规则 3 的孤儿事件告警")
                .isEqualTo(1);
        OperLogVO row = page.getList().get(0);
        assertThat(row.getUserId()).isNull();
        assertThat(row.getUsername()).isNull();
        assertThat(row.getRealName()).isNull();
    }

    @Test
    @DisplayName("【保留侧】有操作人时 username / realName 要真的取回来")
    void webWrittenRowCarriesOperatorIdentity() {
        String action = unique("修改用户");
        operLogWriter.write(ADMIN_USER_ID, "用户管理", action, "PUT /api/v1/system/users/1",
                "{\"id\":\"1\"}", "58.246.120.88", OperLogWriter.STATUS_SUCCESS, null, 46, TENANT_A);

        OperLogPageQuery query = new OperLogPageQuery();
        query.setAction(action);
        PageResult<OperLogVO> page = logQueryService.pageOperLogs(query);

        assertThat(page.getTotal()).isEqualTo(1);
        OperLogVO row = page.getList().get(0);
        assertThat(row.getUsername()).isEqualTo("13800000001");
        assertThat(row.getRealName()).isEqualTo("示例机构管理员");
        assertThat(row.getCostMs()).isEqualTo(46);
        assertThat(row.getIp()).isEqualTo("58.246.120.88");
        assertThat(row.getOperTime()).isNotNull().isBefore(LocalDateTime.now().plusMinutes(1));
    }

    @Test
    @DisplayName("失败行查得到，且带 status=1 与 error_msg（只看成功等于漏掉审计最要看的部分）")
    void failedOperationIsQueryable() {
        String action = unique("上传文件");
        operLogWriter.write(ADMIN_USER_ID, "文件管理", action, "POST /api/v1/system/files",
                null, null, OperLogWriter.STATUS_FAIL, "code=10011 文件类型不支持或大小超出限制", 12, TENANT_A);

        OperLogPageQuery query = new OperLogPageQuery();
        query.setAction(action);
        OperLogVO row = logQueryService.pageOperLogs(query).getList().get(0);

        assertThat(row.getStatus()).isEqualTo(OperLogWriter.STATUS_FAIL);
        assertThat(row.getErrorMsg()).contains("10011");
    }

    @Test
    @DisplayName("module 模糊、action 精确 —— §8.2 参数表逐字如此，不是笔误")
    void moduleIsFuzzyWhileActionIsExact() {
        String action = unique("导出");
        operLogWriter.write(ADMIN_USER_ID, "数据中心", action, "POST /api/v1/stat/exports",
                null, null, OperLogWriter.STATUS_SUCCESS, null, 5, TENANT_A);

        OperLogPageQuery fuzzyModule = new OperLogPageQuery();
        fuzzyModule.setModule("数据");
        fuzzyModule.setAction(action);
        assertThat(logQueryService.pageOperLogs(fuzzyModule).getTotal()).isEqualTo(1);

        OperLogPageQuery partialAction = new OperLogPageQuery();
        partialAction.setAction("导出");
        assertThat(logQueryService.pageOperLogs(partialAction).getList())
                .as("action 是精确匹配，传前缀不该命中那条带后缀的记录")
                .noneMatch(r -> action.equals(r.getAction()));
    }

    // ====================================================================
    // tenantId 参数的收窄
    // ====================================================================

    @Test
    @DisplayName("非超管传 tenantId 被忽略而不是报错，也不用来探测别家租户")
    void tenantIdParamIsIgnoredForNonSuperAdmin() {
        String action = unique("修改租户配置");
        operLogWriter.write(ADMIN_USER_ID, "租户配置", action, "PUT /api/v1/system/tenant-configs/x",
                null, null, OperLogWriter.STATUS_SUCCESS, null, 3, TENANT_A);

        OperLogPageQuery query = new OperLogPageQuery();
        query.setAction(action);
        // 传一个别家租户 ID：若被照用，会得到空列表 —— 而空列表与"那家租户确实没日志"
        // 长得一样，正好是一个可用来探测租户存在性的信号
        query.setTenantId(9999999999999999L);

        assertThat(logQueryService.pageOperLogs(query).getTotal())
                .as("参数被清空，仍按插件注入的本租户查 —— 结果不受这个越权参数影响")
                .isEqualTo(1);
    }
}
