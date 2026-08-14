package com.edumatrix.system.tenant.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.edumatrix.common.account.PasswordHasher;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.response.BizException;
import com.edumatrix.system.role.entity.SysRole;
import com.edumatrix.system.role.mapper.SysRoleMapper;
import com.edumatrix.system.tenant.dto.TenantCreateReq;
import com.edumatrix.system.tenant.entity.SysTenant;
import com.edumatrix.system.tenant.mapper.SysTenantMapper;
import com.edumatrix.system.tenant.vo.TenantCreatedVO;
import com.edumatrix.system.user.entity.SysUser;
import com.edumatrix.system.user.entity.SysUserRole;
import com.edumatrix.system.user.mapper.SysUserMapper;
import com.edumatrix.system.user.mapper.SysUserRoleMapper;
import com.edumatrix.system.user.service.PlatformNodeWriter;

/**
 * 开通机构（03-01 §5.3）——<b>整个系统的起点</b>：在此之前，库里一个机构都没有、
 * 一个能建人的管理员都没有。
 *
 * <h2>三步同一事务，顺序固定不可调换（契约 §2.1 / §5.3）</h2>
 * <pre>
 * ① 插 sys_tenant             root_node_id 【暂为 NULL】
 * ② 插 sys_user + org_node + 回填 node_id + 建档轨迹 + sys_user_role
 * ③ 回写 sys_tenant.root_node_id
 * </pre>
 *
 * <h2>为什么必须是这个顺序：一个真实的循环依赖</h2>
 * <p>机构根节点的 {@code tenant_id} 来自租户行的 id，而租户行的 {@code root_node_id}
 * 来自根节点的 id——<b>两行互相指向对方</b>。解开它只有一个办法：让其中一根指针先空着。
 * 所以 DDL 对 {@code sys_tenant.root_node_id} 写的是「<b>【必须允许 NULL】</b>」，
 * 而那个 NULL 的存续时间<b>就是这个事务的长度</b>，对外永远不可见。
 *
 * <h2>为什么四个 id 里有三个是同一个值</h2>
 * <p>契约 §2.1：超管的直接子节点 = 一个机构 = 一个租户，<b>该节点的 tenant_id 即节点自身 id</b>；
 * §5.0 的树形图与 §5.3 的响应字段说明进一步写死：机构根节点<b>就是</b>机构最高管理员本人
 * （树上每个节点都是一个人，{@code ref_user_id} 恒非空），因此
 * {@code sys_tenant.id == sys_tenant.root_node_id == org_node.id == sys_user.node_id}。
 * 只有 {@code sys_user.id} 是另一个值。
 *
 * <p><b>这一条不成立时不会报错</b>：树能建出来、账号能登录，只是此后所有按 {@code tenant_id}
 * 过滤的查询都对不上一个"看起来存在"的根节点。判据 3 那条四值恒等断言就是它的回归测试。
 *
 * <h2>@Transactional 的两个不可省之处</h2>
 * <ul>
 *   <li>{@code rollbackFor = Exception.class}：默认只回滚 {@code RuntimeException}，
 *       而这里任何一步失败都必须整体回滚（§5.3：「步骤②③任一失败则整个事务回滚，
 *       不会留下『有租户无根节点』或『有根节点无管理员』的半成品」）；
 *   <li>本方法是<b>唯一</b>的事务边界：{@link PlatformNodeWriter#createTenantRootNode}
 *       自身不开事务，它的类注释写明「调用方必须已在事务内」。
 * </ul>
 */
@Service
public class TenantProvisionService {

    /**
     * 初始密码长度：<b>12 位</b>（§5.3 逐字：「12 位，含大小写字母与数字」，
     * 响应示例 {@code aB3kQ9mZ7x2P} 亦为 12 位纯字母数字）。
     *
     * <p><b>与 03-01 §2.5 的 16 位含符号是两套规格，刻意不共用</b>：口令规格由各自分册定，
     * 共用一套的话，将来 §2.5 改长度或加字符类，会<b>悄悄改掉本接口的口令形态</b>——
     * 而这个口令是要由超管抄下来、口头或书面转交给机构联系人的，形态变化有现实成本。
     * 两处都在自己的类里写死自己的规格，改一处不影响另一处。
     */
    private static final int INITIAL_PASSWORD_LENGTH = 12;

    /**
     * 字符集去掉了形近字符 {@code 0/O/1/l/I}——理由与 {@code SysUserService} 那处相同：
     * <b>这个口令是要念给人听、抄给人看的</b>。去掉它们仍满足 §5.3「含大小写字母与数字」。
     */
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";

    /** 预置角色 {@code org_admin}（契约 §2.9：四个内置角色 {@code tenant_id = 0}）。 */
    private static final String ROLE_KEY_ORG_ADMIN = "org_admin";

    private final SysTenantMapper sysTenantMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PlatformNodeWriter platformNodeWriter;
    private final PasswordHasher passwordHasher;

    private final SecureRandom random = new SecureRandom();

    public TenantProvisionService(SysTenantMapper sysTenantMapper,
                                  SysUserMapper sysUserMapper,
                                  SysUserRoleMapper sysUserRoleMapper,
                                  SysRoleMapper sysRoleMapper,
                                  PlatformNodeWriter platformNodeWriter,
                                  PasswordHasher passwordHasher) {
        this.sysTenantMapper = sysTenantMapper;
        this.sysUserMapper = sysUserMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.platformNodeWriter = platformNodeWriter;
        this.passwordHasher = passwordHasher;
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantCreatedVO create(TenantCreateReq req) {
        assertExpireTimeInFuture(req.getExpireTime());
        Long orgAdminRoleId = requirePresetOrgAdminRoleId();

        // ------------------------------------------------------------------
        // ① 插租户行，root_node_id 暂为 NULL
        // ------------------------------------------------------------------
        // 【id 自己生成而不是交给 ASSIGN_ID】：步骤② 的节点 id 与 tenant_id 都要用它，
        // 而实体插入后虽然也能读回 id，但那样"这个值是什么"就分散在两步之间。
        // 显式取一次，此后三步共用同一个变量 —— 恒等关系在代码里是【看得见】的
        long tenantId = IdWorker.nextId();

        SysTenant tenant = new SysTenant();
        tenant.setId(tenantId);
        // 暂为 NULL：解循环依赖（契约 §2.1）。步骤③ 补上
        tenant.setRootNodeId(null);
        tenant.setName(req.getName());
        tenant.setContactName(req.getContactName());
        tenant.setContactPhone(req.getContactPhone());
        tenant.setExpireTime(req.getExpireTime());
        tenant.setStatus(SysTenant.STATUS_NORMAL);
        tenant.setMaxStudentCount(req.getMaxStudentCount());
        tenant.setRemark(req.getRemark());
        insertTenantOrThrowDuplicateName(tenant);

        // ------------------------------------------------------------------
        // ② 建机构最高管理员：账号 + 节点（同一个人）+ 角色绑定
        // ------------------------------------------------------------------
        String initialPassword = generateInitialPassword();
        long adminUserId = IdWorker.nextId();

        SysUser admin = new SysUser();
        admin.setId(adminUserId);
        admin.setUsername(req.getAdminUsername());
        // 明文永不落库（PRD §7.3 安全条款 1）。走 common/account 的 SPI —— 自己 new 一个
        // BCryptPasswordEncoder 会让 cost 分叉，而分叉不报错、只是安全强度悄悄回退
        admin.setPassword(passwordHasher.hash(initialPassword));
        admin.setUserType(SysUser.USER_TYPE_ADMIN);
        admin.setRealName(req.getAdminRealName());
        // node_id 是 NOT NULL，而节点要等账号 id 出来才能建（ref_user_id 指向它）。
        // 先占位 0，建完节点立刻回写 —— 两步同事务，中间态不可见（与 §2.2 同一手法）
        admin.setNodeId(0L);
        admin.setStatus(SysUser.STATUS_NORMAL);
        // PRD F1-1 规则 6：初始密码强制首次登录修改。登录响应据此给 needChangePassword = true
        admin.setPwdResetFlag(1);
        // 【必须显式写 tenant_id】超管会话下租户插件整体放行（ignoreTable 返回 true），
        // INSERT 时不注入租户列。取值是【本次新建的租户】，不是会话租户（超管的是 0）——
        // 沿用会话会把这个管理员建到平台租户下，而且不报错
        admin.setTenantId(tenantId);
        insertAdminOrThrowDuplicateUsername(admin);

        // 节点 id 【就是】租户 id；tenant_id 亦然。两个例外都写死在这个方法里面
        platformNodeWriter.createTenantRootNode(tenantId, adminUserId, req.getName());

        // ref_user_id ↔ node_id 互为反向引用（PRD F1-1 规则 2 / 判据 1）
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, adminUserId)
                .set(SysUser::getNodeId, tenantId));

        SysUserRole binding = new SysUserRole(adminUserId, orgAdminRoleId);
        // 同样显式写：这一行必须跟着新租户走，不是跟着超管的会话
        binding.setTenantId(tenantId);
        sysUserRoleMapper.insert(binding);

        // ------------------------------------------------------------------
        // ③ 回写 root_node_id
        // ------------------------------------------------------------------
        sysTenantMapper.fillRootNodeId(tenantId, tenantId);

        return toCreatedVO(tenantId, adminUserId, req, initialPassword);
    }

    // =====================================================================
    // 校验与冲突翻译
    // =====================================================================

    /** §5.3 参数表：{@code expireTime} 须晚于当前时间，否则 400。 */
    private void assertExpireTimeInFuture(LocalDateTime expireTime) {
        if (expireTime == null || !expireTime.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "expireTime 须晚于当前时间");
        }
    }

    /**
     * 取平台预置的 {@code org_admin} 角色 id。
     *
     * <p><b>按 {@code role_key} 查而不是硬编码那个雪花 id</b>：id 只是初始化数据的一个实现细节，
     * 而 {@code role_key} 是契约 §3 定死的四个值之一。硬编码的话，将来重跑初始化数据
     * （或换一套 id）会让本接口<b>绑到一个不存在的角色</b>上——而那不会当场报错，
     * 只表现为新开通的机构管理员登录后 {@code perms} 为空、所有按钮消失。
     *
     * <p>{@code tenant_id = 0} 显式写：预置角色是平台级行（契约 §2.9）。本查询在超管会话下
     * 走的是插件整体放行通道，不写就可能命中某个租户自建的同名角色。
     */
    private Long requirePresetOrgAdminRoleId() {
        SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, ROLE_KEY_ORG_ADMIN)
                .eq(SysRole::getTenantId, 0L)
                .last("LIMIT 1"));
        if (role == null) {
            // 初始化数据缺失属于部署缺陷，不是调用方的错 —— 500 而不是 400
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "平台预置角色 org_admin 不存在，请检查 V202608140000__init_menu_and_role_menu.sql 是否已执行");
        }
        return role.getId();
    }

    /** {@code uk_name(name, deleted_at)} 冲突 → <b>400</b>（§5.3：「机构名称重复返回 400」）。 */
    private void insertTenantOrThrowDuplicateName(SysTenant tenant) {
        try {
            sysTenantMapper.insert(tenant);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "机构名称已存在");
        }
    }

    /**
     * {@code uk_username(username, deleted_at)} 冲突 → <b>{@code 10001}</b>
     * （§5.3 错误码表里唯一的一条）。
     *
     * <p>不预先查一次"这个用户名是不是被删过"：唯一索引末尾的 {@code deleted_at}
     * 已经让同名重建自动放行（契约 §2.2），这里只需把冲突翻成业务码。
     */
    private void insertAdminOrThrowDuplicateUsername(SysUser admin) {
        try {
            sysUserMapper.insert(admin);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
    }

    // =====================================================================
    // 初始口令
    // =====================================================================

    /**
     * §5.3：12 位，含大小写字母与数字，<b>由系统随机生成，不由超管指定</b>。
     *
     * <p><b>禁止任何固定默认密码</b>：固定常量会出现在文档与工单里，攻击者拿到用户名列表
     * 即可批量撞库命中所有「已开通未改密」的机构。三类各先保证一个再随机补齐，
     * 否则纯随机有概率生成不含数字的串——而那种串连本系统 §1.6 改密时的强度校验都过不了。
     */
    private String generateInitialPassword() {
        String all = UPPER + LOWER + DIGIT;
        List<Character> chars = new ArrayList<>(INITIAL_PASSWORD_LENGTH);
        chars.add(pick(UPPER));
        chars.add(pick(LOWER));
        chars.add(pick(DIGIT));
        while (chars.size() < INITIAL_PASSWORD_LENGTH) {
            chars.add(pick(all));
        }
        // 洗牌，免得前三位的类别恒定
        for (int i = chars.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Character tmp = chars.get(i);
            chars.set(i, chars.get(j));
            chars.set(j, tmp);
        }
        StringBuilder sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }

    private char pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }

    // =====================================================================
    // 响应装配
    // =====================================================================

    private TenantCreatedVO toCreatedVO(long tenantId, long adminUserId,
                                        TenantCreateReq req, String initialPassword) {
        SysTenant saved = sysTenantMapper.selectById(tenantId);

        TenantCreatedVO vo = new TenantCreatedVO();
        vo.setId(tenantId);
        vo.setName(saved.getName());
        // 三个字段【同一个值】。并列返回正是为了让一次不相等当场暴露 ——
        // 与 §2.2 把 nodeType 与 userType 并列返回是同一手法
        vo.setRootNodeId(saved.getRootNodeId());
        vo.setAdminNodeId(tenantId);
        vo.setExpireTime(saved.getExpireTime());
        vo.setStatus(saved.getStatus());
        vo.setMaxStudentCount(saved.getMaxStudentCount());
        vo.setAdminUserId(adminUserId);
        vo.setAdminUsername(req.getAdminUsername());
        vo.setAdminRealName(req.getAdminRealName());
        // 根节点即机构名，故面包屑只有一段（§5.3 响应示例）。
        // 不去查一次 nodePath：它的组成在这里是已知的常量形态，查一次只会引入
        // 「查出来的和写下去的不一致」这一种新的失败可能
        vo.setAdminNodePath(saved.getName());
        vo.setInitialPassword(initialPassword);
        vo.setCreateTime(saved.getCreateTime());
        return vo;
    }
}
