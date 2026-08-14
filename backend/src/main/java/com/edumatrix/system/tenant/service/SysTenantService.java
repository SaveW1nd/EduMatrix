package com.edumatrix.system.tenant.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.account.SessionRevoker;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.system.tenant.dto.TenantPageQuery;
import com.edumatrix.system.tenant.dto.TenantRenewReq;
import com.edumatrix.system.tenant.dto.TenantStatusReq;
import com.edumatrix.system.tenant.dto.TenantUpdateReq;
import com.edumatrix.system.tenant.entity.SysTenant;
import com.edumatrix.system.tenant.mapper.SysTenantMapper;
import com.edumatrix.system.tenant.mapper.TenantOrgMapper;
import com.edumatrix.system.tenant.vo.TenantDetailVO;
import com.edumatrix.system.tenant.vo.TenantListVO;
import com.edumatrix.system.tenant.vo.TenantRenewedVO;
import com.edumatrix.system.user.entity.SysUser;
import com.edumatrix.system.user.mapper.SysUserMapper;

/**
 * 租户的查询与生命周期（03-01 §5.1 / §5.2 / §5.4~§5.7）。开通走
 * {@link TenantProvisionService}。
 *
 * <h2>本组六个接口一律仅 {@code super_admin}</h2>
 * <p>§5 导语：「平台超管专用，其余角色（含 {@code org_admin}）调用一律返回 403。
 * 本组接口为平台级操作，<b>不做租户注入</b>」。这条约束<b>不靠代码里的 if</b>，
 * 靠 {@code sys_role_menu} 的初始化数据：{@code system:tenant:list/query/add/edit/remove/renew/status}
 * 只绑了 {@code super_admin}，于是 {@code @SaCheckPermission} 对 {@code org_admin} 天然 403。
 *
 * <h2>为什么这里没有子树过滤</h2>
 * <p>§0.2 的唯一数据权限规则（可见范围 = 本节点子树）作用在<b>树上的对象</b>；
 * 租户不是树上的节点，它是<b>树的边界</b>本身。而 {@code sys_tenant} 连 {@code tenant_id}
 * 列都没有（契约 §2.9 的两张纯平台级表之一），租户插件对它 {@code ignoreTable} 恒 true。
 */
@Service
public class SysTenantService {

    private static final Logger log = LoggerFactory.getLogger(SysTenantService.class);

    private final SysTenantMapper sysTenantMapper;
    private final TenantOrgMapper tenantOrgMapper;
    private final SysUserMapper sysUserMapper;
    private final SessionRevoker sessionRevoker;

    public SysTenantService(SysTenantMapper sysTenantMapper,
                            TenantOrgMapper tenantOrgMapper,
                            SysUserMapper sysUserMapper,
                            SessionRevoker sessionRevoker) {
        this.sysTenantMapper = sysTenantMapper;
        this.tenantOrgMapper = tenantOrgMapper;
        this.sysUserMapper = sysUserMapper;
        this.sessionRevoker = sessionRevoker;
    }

    // =====================================================================
    // §5.1 分页查询租户
    // =====================================================================

    public PageResult<TenantListVO> page(TenantPageQuery query) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<SysTenant>()
                .like(hasText(query.getName()), SysTenant::getName, query.getName())
                .eq(hasText(query.getContactPhone()), SysTenant::getContactPhone, query.getContactPhone())
                .eq(query.getStatus() != null, SysTenant::getStatus, query.getStatus())
                // 「到期时间早于该时刻」——用于筛选临期租户。expire_time 为 NULL 是"永久"，
                // MySQL 的 NULL 比较天然不命中，正合语义：永久有效的机构不该出现在临期名单里
                .lt(query.getExpireBefore() != null, SysTenant::getExpireTime, query.getExpireBefore())
                .orderByDesc(SysTenant::getCreateTime)
                .orderByAsc(SysTenant::getId);

        Page<SysTenant> page = new Page<>(
                PageResult.normalizePageNum(query.getPageNum()),
                PageResult.normalizePageSize(query.getPageSize()));
        Page<SysTenant> result = sysTenantMapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), toListVOs(result.getRecords()));
    }

    private List<TenantListVO> toListVOs(List<SysTenant> tenants) {
        if (tenants.isEmpty()) {
            return List.of();
        }
        // 本页全部租户的在读学生数一次查完，避免逐行往返（本页最多 100 行）
        Set<Long> tenantIds = new LinkedHashSet<>();
        tenants.forEach(t -> tenantIds.add(t.getId()));
        Map<Long, Long> studentCounts = new HashMap<>();
        for (TenantOrgMapper.TenantStudentCount row
                : tenantOrgMapper.countActiveStudentsByTenants(List.copyOf(tenantIds))) {
            studentCounts.put(row.getTenantId(), row.getStudentCount());
        }

        List<TenantListVO> list = new ArrayList<>(tenants.size());
        for (SysTenant tenant : tenants) {
            TenantListVO vo = new TenantListVO();
            vo.setId(tenant.getId());
            vo.setName(tenant.getName());
            vo.setRootNodeId(tenant.getRootNodeId());
            vo.setContactName(tenant.getContactName());
            vo.setContactPhone(tenant.getContactPhone());
            vo.setExpireTime(tenant.getExpireTime());
            vo.setStatus(tenant.getStatus());
            vo.setMaxStudentCount(tenant.getMaxStudentCount());
            // 一个学生都没有的租户不出现在分组结果里 —— 缺省补 0，不是 null：
            // "还没有学生"是 0 这个确定的事实，不是"数不出来"
            vo.setCurrentStudentCount(studentCounts.getOrDefault(tenant.getId(), 0L));
            vo.setCreateTime(tenant.getCreateTime());
            vo.setRemark(tenant.getRemark());
            list.add(vo);
        }
        return list;
    }

    // =====================================================================
    // §5.2 查询租户详情
    // =====================================================================

    public TenantDetailVO detail(Long id) {
        SysTenant tenant = requireTenant(id);

        TenantDetailVO vo = new TenantDetailVO();
        vo.setId(tenant.getId());
        vo.setName(tenant.getName());
        vo.setRootNodeId(tenant.getRootNodeId());
        vo.setContactName(tenant.getContactName());
        vo.setContactPhone(tenant.getContactPhone());
        vo.setExpireTime(tenant.getExpireTime());
        vo.setStatus(tenant.getStatus());
        vo.setMaxStudentCount(tenant.getMaxStudentCount());
        vo.setCurrentStudentCount(tenantOrgMapper.countActiveStudents(tenant.getId()));
        vo.setNodeCount(tenantOrgMapper.countLiveNodes(tenant.getId()));
        vo.setCreateTime(tenant.getCreateTime());
        vo.setUpdateTime(tenant.getUpdateTime());
        vo.setRemark(tenant.getRemark());

        // 机构最高管理员 = 机构根节点本人（契约 §2.1 / §5.0），因此
        // adminNodeId 恒等于 rootNodeId，不需要（也不该）再去树上找"挂在根节点下的那个管理员"
        vo.setAdminNodeId(tenant.getRootNodeId());
        SysUser admin = findRootAdmin(tenant.getRootNodeId());
        if (admin != null) {
            vo.setAdminUserId(admin.getId());
            vo.setAdminUsername(admin.getUsername());
        }
        return vo;
    }

    /**
     * 按机构根节点找它的账号。
     *
     * <p><b>按 {@code node_id} 反查 {@code sys_user} 而不是读 {@code org_node.ref_user_id}</b>：
     * 两者互为反向引用，任取一条都能到达；取 {@code sys_user} 这一条是因为详情要的
     * {@code adminUsername} 本来就在这张表上，走 {@code idx_node_id} 一次查询拿齐，
     * 另一条要查两次。
     *
     * <p>查不到时返回 {@code null}（VO 里那两个字段留空）而不是抛异常：租户行还在、
     * 只是管理员账号被删了，这是一个<b>值得让平台侧看见</b>的异常状态，
     * 而把详情接口整个打成 500 反而看不见。
     */
    private SysUser findRootAdmin(Long rootNodeId) {
        if (rootNodeId == null) {
            return null;
        }
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getNodeId, rootNodeId)
                .eq(SysUser::getUserType, SysUser.USER_TYPE_ADMIN)
                .last("LIMIT 1"));
    }

    // =====================================================================
    // §5.4 修改租户
    // =====================================================================

    /**
     * 改机构信息。<b>不改 {@code expireTime}</b>（走 §5.6）、<b>不改 {@code rootNodeId}</b>（只读）、
     * <b>不改 {@code status}</b>（走 §5.7）。
     *
     * <p>机构名称同步刷机构根节点的 {@code node_name}，「随之影响全机构的 {@code nodePath} 展示」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, TenantUpdateReq req) {
        SysTenant tenant = requireTenant(id);
        assertMaxStudentCountNotBelowCurrent(tenant.getId(), req.getMaxStudentCount());

        try {
            sysTenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                    .eq(SysTenant::getId, id)
                    .set(SysTenant::getName, req.getName())
                    .set(SysTenant::getContactName, req.getContactName())
                    .set(SysTenant::getContactPhone, req.getContactPhone())
                    .set(SysTenant::getMaxStudentCount, req.getMaxStudentCount())
                    .set(SysTenant::getRemark, req.getRemark()));
        } catch (DuplicateKeyException e) {
            // uk_name：改成另一个已存在的机构名，与 §5.3 同样返回 400
            throw new BizException(ErrorCode.BAD_REQUEST, "机构名称已存在");
        }

        if (tenant.getRootNodeId() != null) {
            tenantOrgMapper.updateNodeName(tenant.getRootNodeId(), tenant.getId(), req.getName());
        }
    }

    /** §5.4：{@code maxStudentCount} 不得低于该租户当前在读学生数，否则 400。 */
    private void assertMaxStudentCountNotBelowCurrent(Long tenantId, Integer maxStudentCount) {
        if (maxStudentCount == null) {
            return;
        }
        long current = tenantOrgMapper.countActiveStudents(tenantId);
        if (maxStudentCount < current) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "maxStudentCount 不得低于当前在读学生数（" + current + "）");
        }
    }

    // =====================================================================
    // §5.5 删除租户（逻辑删除）
    // =====================================================================

    /**
     * 逻辑删除租户，并<b>级联逻辑删除机构根节点及其整棵子树</b>。
     *
     * <h2>为什么级联范围只到 {@code org_node}</h2>
     * <p>§5.5 原文只写了这一句：「机构根节点及其整棵子树一并逻辑删除（<b>唯一允许级联逻辑删除
     * 整棵子树的场景</b>）」，并紧接着说「租户业务数据<b>整体保留（软删除，可恢复）</b>」。
     * <b>没有提 {@code sys_user}，本实现就不动它</b>——而且不动它是有实质理由的：
     * {@code uk_username(username, deleted_at)} 让逻辑删除<b>释放用户名</b>，
     * 删一个租户就把它全部账号的用户名释放出去，别人可以立刻注册走；
     * 等到"可恢复"真的发生时，那些用户名可能已经不属于它了。
     *
     * <h2>"该租户全员即时禁止登录"不需要额外动作</h2>
     * <p>租户行软删后 {@code AuthOrgMapper#selectTenant}（条件带 {@code deleted_at = 0}）查不到，
     * {@code LoginCheckService} 的第⑥步按「租户行查不到也判 {@code 10007}」拒登。
     *
     * <h2>已在线的 Token 为什么不在这里踢</h2>
     * <p><b>因为进得来这里的租户必然已经停用过</b>——本接口的前置条件是 {@code status = 1}
     * （§5.5：「仅允许删除已停用的租户，防误删」），而 §5.7 停用那一步已经把全员踢下线了。
     * §5.5 原文也确实没有"作废在线 Token"这一句。<b>这个不对称是前置条件带来的，不是漏写</b>；
     * 若将来 §5.5 取消"必须先停用"，这里就必须补上踢线。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysTenant tenant = requireTenant(id);
        if (tenant.getStatus() == null || tenant.getStatus() != SysTenant.STATUS_DISABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅允许删除已停用（status=1）的租户，请先停用");
        }

        sysTenantMapper.deleteById(id);
        int nodes = tenantOrgMapper.softDeleteTenantSubtree(id);
        log.info("租户删除：tenantId={} 级联逻辑删除节点 {} 个（03-01 §5.5：唯一允许级联的场景）", id, nodes);
    }

    // =====================================================================
    // §5.6 租户续期
    // =====================================================================

    public TenantRenewedVO renew(Long id, TenantRenewReq req) {
        SysTenant tenant = requireTenant(id);
        if (req.getExpireTime() == null || !req.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "expireTime 须晚于当前时间");
        }
        // 允许早于原到期时间 —— §5.6：「早于用于纠错回调」。这里【故意】不判它与原值的先后

        sysTenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                .eq(SysTenant::getId, id)
                .set(SysTenant::getExpireTime, req.getExpireTime()));

        TenantRenewedVO vo = new TenantRenewedVO();
        vo.setId(tenant.getId());
        vo.setName(tenant.getName());
        vo.setExpireTime(req.getExpireTime());
        vo.setStatus(tenant.getStatus());
        return vo;
    }

    // =====================================================================
    // §5.7 启用/停用租户
    // =====================================================================

    /**
     * 改 {@code sys_tenant.status}；<b>停用时作废该租户全员的在线 Token</b>（§5.7 原文）。
     *
     * <h2>为什么改的是租户行而不是机构根节点</h2>
     * <p>机构根节点<b>不可停用</b>（PRD F1-1 规则 7）。{@code org_node.status} 的停用会走
     * 冻结集、返回 {@code 10017}（「账号已被停用或其上级已停用」，找机构管理员）；
     * 而租户停用返回 {@code 10007}（「租户已停用或服务已到期」，找平台）。
     * 契约 §2.3 与 00-通用约定 §9.2 都写明<b>这两个码不得合并</b>——合并了用户就会走错求助路径，
     * 而这类错误没有任何技术手段能事后发现，只表现为"客服说不归他们管"。
     *
     * <h2>踢线为什么逐个账号，以及为什么可以同步做</h2>
     * <p>契约 §2.3 否决过「遍历子树逐个 {@code logout}」——但它否的是<b>机构管理员停用节点</b>
     * 这个高频动作（停一个 1.1 万人的分支、每天都可能发生）。
     * <b>租户停用是平台侧的低频动作</b>（欠费/违规冻结，一个租户一辈子一两次），
     * 调用者是超管、没有并发。<b>同一个做法在不同频率下是不同的方案。</b>
     *
     * <p>所以这里<b>同步逐个作废，并接受这个接口可能跑几秒</b>，三条理由：
     * <ol>
     *   <li><b>不能异步</b>：异步化会造出"接口已返回成功、但人还在线"的窗口，
     *       而 §5.7 的语义就是"停用后全员在线 Token 作废"。这与冻结集那两条顺序约束同向
     *       ——<b>宁可多等一会，不可漏放一瞬</b>；
     *   <li><b>没有可用的异步基础设施</b>：线程池/Job 的落点在模块 05/16，
     *       现在自建一套等于替它们做设计决策（还要一并定 traceId 继承与失败重试）；
     *   <li><b>不设上限截断</b>：截断意味着一部分人静默地留在线上，那正是本条要防的事。
     * </ol>
     * <p>代价用<b>可观测性</b>兜住：踢线前后各记一条 INFO（租户、账号数、耗时），
     * 于是"这个接口跑了 3 秒"在日志里是可解释的，而不是一个谜。
     *
     * <p><b>启用不踢线</b>：那会把一个刚被恢复的租户全员莫名其妙踢下线（与 §2.6 同理）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, TenantStatusReq req) {
        requireTenant(id);

        sysTenantMapper.update(null, new LambdaUpdateWrapper<SysTenant>()
                .eq(SysTenant::getId, id)
                .set(SysTenant::getStatus, req.getStatus()));

        if (req.getStatus() == SysTenant.STATUS_DISABLED) {
            revokeAllTenantSessions(id);
        }
    }

    private void revokeAllTenantSessions(Long tenantId) {
        // 只取 id 列：整行取回来对万级用户是纯浪费。
        // tenant_id 显式写 —— 超管会话下插件整体放行，不写就是【全平台】踢线
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId)
                .eq(SysUser::getTenantId, tenantId));

        long start = System.currentTimeMillis();
        log.info("租户停用：tenantId={} 开始作废在线会话，账号数={}（03-01 §5.7）", tenantId, users.size());
        for (SysUser user : users) {
            // Redis 不参与事务回滚。SessionRevoker 的注释：事务内调用或提交后调用皆可，
            // 但绝不可「先返回成功、再异步作废」
            sessionRevoker.revokeAllSessions(user.getId());
        }
        log.info("租户停用：tenantId={} 会话作废完成，账号数={} 耗时={}ms",
                tenantId, users.size(), System.currentTimeMillis() - start);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * 路径上的 {@code {id}} 查不到（不存在或已逻辑删除）→ <b>404</b>。
     *
     * <p>契约 §2.4 三分法的第一条：「我要操作的东西」越界/不存在 → 404，不暴露存在性。
     * 本组接口不存在"跨租户"的情形（超管跨全平台），所以这里只有"确实没有"这一种。
     */
    private SysTenant requireTenant(Long id) {
        SysTenant tenant = id == null ? null : sysTenantMapper.selectById(id);
        if (tenant == null) {
            throw BizException.notFound(id);
        }
        return tenant;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
