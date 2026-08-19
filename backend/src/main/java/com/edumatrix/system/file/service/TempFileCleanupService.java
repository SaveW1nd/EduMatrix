package com.edumatrix.system.file.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.file.FileBizType;
import com.edumatrix.system.file.entity.SysFile;
import com.edumatrix.system.file.mapper.SysFileMapper;
import com.edumatrix.system.tenant.entity.SysTenant;
import com.edumatrix.system.tenant.mapper.SysTenantMapper;

/**
 * 敏感文件 7 天保留期清理的业务实现（{@code TempFileCleanupJob} 调它）。
 *
 * <p>Job 只做三件事 —— 取租户上下文、分片、调本类（05-工程结构.md §H「集中的边界」：
 * 「{@code Job} 类只做三件事……<b>不写 SQL、不写业务判断、不注入 Mapper</b>」）。
 *
 * <h2>清理范围是<b>正向白名单</b>，这一条是本类最要紧的设计</h2>
 * <p>{@code 00-通用约定} §7.4 末行逐字：「<b>导入源文件、失败报告、导出报表</b>
 * 一律保留 7 天后由定时任务物理清理」，加上 03-01 §7.3 点名的
 * {@code credential_sheet}（「含明文初始密码，保留 7 天后物理清理」），共四个。
 *
 * <p>写成 {@code NOT IN} 黑名单的话，模块 08 新增一个 {@code course_cover} 就会
 * 让全部课程封面在 7 天后被静默删掉 —— 表现是课程列表的图全变裂图，
 * 而没有任何一处报错。<b>两种写法的错误代价不对称，所以选白名单。</b>
 */
@Service
public class TempFileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TempFileCleanupService.class);

    /** {@code sys_tenant.status}：0 正常（契约 §5 枚举；停用的租户不必再跑清理）。 */
    private static final int TENANT_STATUS_NORMAL = 0;

    /**
     * 会被清理的四个 bizType（见类注释）。
     *
     * <p><b>从 {@link FileBizType} 逐个点名，不是"取某个属性为 true 的全部"</b>：
     * 属性法会让将来某个 bizType 一改标志位就悄悄进入清理范围，
     * 而这里要的恰恰是「加一个进来必须有人显式写一行」。
     */
    private static final List<String> CLEANUP_BIZ_TYPES = List.of(
            FileBizType.IMPORT_EXCEL.code(),
            FileBizType.FAIL_REPORT.code(),
            FileBizType.CREDENTIAL_SHEET.code(),
            FileBizType.EXPORT_REPORT.code());

    private final SysFileMapper sysFileMapper;
    private final SysTenantMapper sysTenantMapper;
    private final FileService fileService;

    public TempFileCleanupService(SysFileMapper sysFileMapper,
                                  SysTenantMapper sysTenantMapper,
                                  FileService fileService) {
        this.sysFileMapper = sysFileMapper;
        this.sysTenantMapper = sysTenantMapper;
        this.fileService = fileService;
    }

    /** 供测试与运维核对清理范围。 */
    public static List<String> cleanupBizTypes() {
        return CLEANUP_BIZ_TYPES;
    }

    /**
     * 活跃租户清单。
     *
     * <p><b>不需要 {@code ignore()}</b>：{@code sys_tenant} 是全库仅有的两张
     * <b>不带 {@code tenant_id} 列</b>的表之一，压根不进租户插件
     * （{@code EduMatrixTenantLineHandler} 的 {@code TABLES_WITHOUT_TENANT_COLUMN}）。
     * 与 {@code OrgStudentMapper#selectActiveTenantIds} 同一条理由，
     * 全库的 {@code ignore()} 调用点仍然只有 1 处。
     *
     * <p>用 MyBatis-Plus 的 {@code selectObjs} 而不是自写 SQL —— {@code system/tenant}
     * 与本类同域，直接用它已有的 {@code BaseMapper} 即可，不为一句查询另开方法。
     */
    public List<Long> activeTenantIds() {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<SysTenant>()
                .select(SysTenant::getId)
                .eq(SysTenant::getStatus, TENANT_STATUS_NORMAL)
                .orderByAsc(SysTenant::getId);
        return sysTenantMapper.selectList(wrapper).stream().map(SysTenant::getId).toList();
    }

    /** 扫描超期候选。调用方必须已用 {@code runWithTenant} 包住（租户条件由插件注入）。 */
    public List<CleanupCandidate> findExpired(LocalDateTime deadline, int limit) {
        return sysFileMapper.selectCleanupCandidates(deadline, CLEANUP_BIZ_TYPES, limit)
                .stream()
                .map(f -> new CleanupCandidate(f.getId(), f.getFileUrl(), f.getBizType(), f.getStorage()))
                .toList();
    }

    /**
     * 清一个文件：<b>先删对象、成功后才写 {@code deleted_at}</b>。
     *
     * <p><b>顺序不能反。</b>反了会留下删不掉的 OSS 孤儿对象 ——
     * 库里已删、没人知道 key，那份含明文初始密码的账号密码表就永远留在桶里，
     * 而系统显示它"已清理"。这正是本项目定义的头号故障形态。
     *
     * <p><b>逐个一个事务</b>：一批互相独立的动作，一个失败不该拖垮另外 999 个。
     * 失败项的 {@code deleted_at} 仍为 0，下次调度重扫。
     *
     * @return 是否成功清理
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean purge(CleanupCandidate candidate) {
        if (candidate.storage() == null || candidate.storage() != fileService.storageType()) {
            log.warn("跳过清理：sys_file.storage={} 与当前存储实现 {} 不一致 fileId={}。"
                            + "这一行的对象在另一个后端上，删了库行会留下孤儿对象",
                    candidate.storage(), fileService.storageType(), candidate.fileId());
            return false;
        }
        try {
            fileService.deleteObject(candidate.objectKey());
        } catch (RuntimeException e) {
            log.error("对象删除失败，保留库行等下次重扫 fileId={} key={}",
                    candidate.fileId(), candidate.objectKey(), e);
            return false;
        }
        // deleted_at 由 @TableLogic 的 delval 表达式写入（契约 §2.2：毫秒时间戳，
        // 不是 0/1 —— 同一业务键要能容纳任意多条已删除行）
        sysFileMapper.deleteById(candidate.fileId());
        return true;
    }

    /** 清理候选的最小事实。 */
    public record CleanupCandidate(Long fileId, String objectKey, String bizType, Integer storage) {
    }
}
