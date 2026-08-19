package com.edumatrix.system.log.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.tenant.TenantHelper;
import com.edumatrix.system.log.dto.LoginLogPageQuery;
import com.edumatrix.system.log.dto.OperLogPageQuery;
import com.edumatrix.system.log.mapper.SysLoginLogQueryMapper;
import com.edumatrix.system.log.mapper.SysOperLogQueryMapper;
import com.edumatrix.system.log.vo.LoginLogVO;
import com.edumatrix.system.log.vo.OperLogVO;

/**
 * 两张日志表的查询（03-01 §8.1 / §8.2）。<b>只读，没有删除接口</b>。
 *
 * <p>§8 引言逐字：「日志由框架切面自动记录，<b>仅提供查询，不提供删除接口</b>
 * （归档清理为运维行为）」。契约 §7.2 第 5 条要求两张表保留 ≥ 6 个月
 * <b>且不参与"删除请求"的清理</b>。
 *
 * <h2>⚠ 「保留 ≥ 6 个月」这条现在<b>没有任何代码或运维脚本承载</b></h2>
 * <p>本模块能证明的只有「{@code TempFileCleanupJob} 跑完之后那两张表一行不少」
 * （{@code TempFileCleanupJobIT} 的 T-9），证明不了保留期本身 ——
 * 没有归档任务、没有保留期检查，也没有任何东西会在有人手工
 * {@code DELETE FROM sys_oper_log} 时失败。<b>已登记 F-33</b>，不让它继续无声。
 *
 * <h2>{@code tenantId} 参数的收窄</h2>
 * <p>§8.1/§8.2 参数表都写着「{@code tenantId}｜仅 super_admin 可用」。
 * 非超管传了会被本类<b>清空</b>而不是报错 —— 因为它越不出租户边界
 * （租户插件的 {@code WHERE tenant_id = ?} 还在，那才是隔离本身），
 * 报错只会把一个无害的多余参数变成一次用户可见的失败。
 * <b>清空而不是照用</b>：照用会让 org_admin 传一个别家租户 ID 时得到空列表，
 * 而空列表与"那家租户确实没有日志"长得一模一样 —— 一个可被用来探测租户存在性的信号。
 */
@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    private final SysLoginLogQueryMapper loginLogQueryMapper;
    private final SysOperLogQueryMapper operLogQueryMapper;

    public LogQueryService(SysLoginLogQueryMapper loginLogQueryMapper,
                           SysOperLogQueryMapper operLogQueryMapper) {
        this.loginLogQueryMapper = loginLogQueryMapper;
        this.operLogQueryMapper = operLogQueryMapper;
    }

    /** §8.1 分页查询登录日志。 */
    public PageResult<LoginLogVO> pageLoginLogs(LoginLogPageQuery query) {
        query.setTenantId(narrowTenantFilter(query.getTenantId()));
        Page<LoginLogVO> page = newPage(query.getPageNum(), query.getPageSize());
        var result = loginLogQueryMapper.selectPage(page, query);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /** §8.2 分页查询操作日志 —— F-25 列的第四件事。 */
    public PageResult<OperLogVO> pageOperLogs(OperLogPageQuery query) {
        query.setTenantId(narrowTenantFilter(query.getTenantId()));
        Page<OperLogVO> page = newPage(query.getPageNum(), query.getPageSize());
        var result = operLogQueryMapper.selectPage(page, query);
        return PageResult.of(result.getTotal(), result.getRecords());
    }

    /** 见类注释：非超管传的 {@code tenantId} 一律清空。 */
    private Long narrowTenantFilter(Long requested) {
        if (requested == null) {
            return null;
        }
        if (TenantHelper.isSuperAdminSession()) {
            return requested;
        }
        log.warn("非超管会话传了 tenantId={}，已忽略（03-01 §8.1/§8.2：仅 super_admin 可用）。"
                + "租户隔离由插件保证，本参数只是额外收窄", requested);
        return null;
    }

    /** {@code pageSize} 上限 100 由 {@code PageResult} 强制（{@code 00-通用约定} §4.1）。 */
    private static <T> Page<T> newPage(Integer pageNum, Integer pageSize) {
        return new Page<>(PageResult.normalizePageNum(pageNum), PageResult.normalizePageSize(pageSize));
    }
}
