package com.edumatrix.org.node.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code sys_user} 的窄读写，服务 §3.1/§3.2 的 {@code refUserName}/{@code refUserPhone}、
 * §3.3 的姓名同步与 §3.6 的重置密码。
 *
 * <h2>这是工单已授权的路径，不是越界</h2>
 * <p>04-实施计划.md 模块 06 的「涉及表」逐字：<b>写</b>：{@code org_node}、
 * {@code org_node_change_log}、<b>{@code sys_user}（重置密码）</b>。
 * §3.1/§3.2 的响应字段 {@code refUserName}（「恒非空」）、{@code refUserPhone}
 * 也只能从这张表取。
 *
 * <h2>⚠ 曾登记为待交接，模块 07 核对后判定<b>不交接</b>，本类转为常驻</h2>
 * <p>原登记（{@code com.edumatrix.org.node} 的 {@code package-info} 第 3 条）写的是
 * 「将来 {@code system/user} 对外暴露 Service 后改调它」，与
 * {@code system/user/mapper/StudentQuotaMapper} 互为镜像。<b>模块 07 判定这条不成立</b>，
 * 理由两条，逐条见那张清单：
 * <ol>
 *   <li><b>工单已授权</b> —— 04-实施计划.md 模块 07 的「涉及表」写栏逐字列着
 *       {@code sys_user}、{@code sys_user_role}。{@code org} 领域读写 {@code sys_user}
 *       是模块 07 工单明确授权的（建人要插账号、绑角色），当初「表在对方领域」这个成因，
 *       在模块 07 之后<b>反而消失了</b>：{@code org} 本来就要直连这张表；
 *   <li><b>反向 SPI 会形成双向 Bean 依赖</b> —— 方向与 {@code system} 消费 {@code org}
 *       的那条 SPI 相反，两端都是 Bean，构造器循环风险是真的。
 * </ol>
 *
 * <h2>模块 07 把建人/删人所需的账号读写并入本类，<b>不另开第二个 {@code sys_user} Mapper</b></h2>
 * <p>{@code org} 领域内一张表只有一个入口。下半段的方法全部服务
 * 03-02 接口 8 / 12 / 17（建人）与 10 / 14 / 19（删人）。
 *
 * <p><b>删人写 {@code deleted_at}，不写 {@code status}</b>（03-02 §4.4 / §5.4 / §6.4 各有一整段，
 * 契约 §2.3）：{@code status} 只表达「账号级封禁」，「这个人已被删除」是另一件事。
 * 二者混用会重演停用那个坑 —— 将来做「误删恢复」时，恢复方要面对一个 {@code status = 1}
 * 却分不清是被风控封的还是被删的账号。写 {@code deleted_at} 顺带统一了「用户名是否释放」：
 * {@code uk_username(username, deleted_at)} 自动放行同名重建。
 *
 * <p><b>口令哈希不在这里做</b>：一律走 {@code common/account/PasswordHasher}
 * （SPI，实现在 {@code auth}）。自己 {@code new BCryptPasswordEncoder} 会让 cost 分叉，
 * 而 BCrypt 把 cost 编码在密文里，两边都验得过 —— <b>不报错、不失败，
 * 只是安全强度悄悄回退</b>（{@code PasswordHasher} 类注释）。
 */
@Mapper
public interface NodeAccountMapper {

    /**
     * 批量取账号的姓名与手机号（§3.1 的 {@code refUserName}、§3.2 再加 {@code refUserPhone}）。
     *
     * <p>一次查询而不是逐行查 —— 一层树最多 2000 行（§3.1 的服务端硬上限），
     * 逐行就是 2000 次往返。
     */
    @Select("<script>"
            + "SELECT id, real_name AS realName, phone FROM sys_user "
            + " WHERE deleted_at = 0 AND id IN "
            + "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<UserBriefRow> selectUserBriefs(@Param("userIds") List<Long> userIds);

    /**
     * 账号所在的节点（{@code sys_user.node_id}）；不存在时返回 {@code null}。
     *
     * <p>数据权限的入口参数。取法与 {@code SysUserService#currentNodeId} 一致，
     * 理由见 {@code CurrentNodeResolver}。
     */
    @Select("SELECT node_id FROM sys_user WHERE id = #{userId} AND deleted_at = 0")
    Long selectNodeIdByUserId(@Param("userId") Long userId);

    /** 单个账号的姓名与手机号；不存在时返回 {@code null}。 */
    @Select("SELECT id, real_name AS realName, phone FROM sys_user "
            + " WHERE id = #{userId} AND deleted_at = 0")
    UserBriefRow selectUserBrief(@Param("userId") Long userId);

    /**
     * §3.3：人员节点改名时同步 {@code sys_user.real_name}。
     *
     * <p>分册原文：「人员节点的 {@code nodeName} 修改会<b>同步</b> {@code sys_user.real_name}
     * （也可通过接口 9 / 13 / 18 修改）」。两处必须同值 —— {@code org_node.node_name}
     * 的 DDL 注释也写着「与 {@code sys_user.real_name} 同步」。
     */
    @Update("UPDATE sys_user SET real_name = #{realName}, update_by = #{operatorId} "
            + " WHERE id = #{userId} AND deleted_at = 0")
    int updateRealName(@Param("userId") Long userId,
                       @Param("realName") String realName,
                       @Param("operatorId") Long operatorId);

    /**
     * §3.6：写新密文并置 {@code pwd_reset_flag = 1}（下次登录强制改密）。
     *
     * <p><b>两件事一条 UPDATE</b>：分册把它们写在同一个事务里，而它们本就是同一行的两列，
     * 分两条只会多一次行锁往返，且留下「改了密码没置标志」的中间态。
     *
     * <p><b>不碰 {@code status}</b>：分册原文「目标节点或其账号已停用时<b>照常可重置</b>
     * （重置不解除停用）」。
     */
    @Update("UPDATE sys_user SET password = #{encodedPassword}, pwd_reset_flag = 1, "
            + "       update_by = #{operatorId} "
            + " WHERE id = #{userId} AND deleted_at = 0")
    int resetPassword(@Param("userId") Long userId,
                      @Param("encodedPassword") String encodedPassword,
                      @Param("operatorId") Long operatorId);

    // =====================================================================
    // 以下为模块 07 新增：建人 / 删人所需的 sys_user、sys_user_role 窄读写
    // =====================================================================

    /**
     * 建账号。<b>{@code tenant_id} 必须显式传入且取自父节点</b>（契约 §2.8 规则 1「从数据显式取」）。
     *
     * <p>为什么不走实体 + MyBatis-Plus 自动注入：本包没有 {@code sys_user} 实体，
     * 也不该有 —— 它是 {@code system} 领域的实体。这里只声明模块 07 真正写的那几列。
     *
     * <p><b>{@code node_id} 先占位 {@code 0}</b>：它是 {@code NOT NULL}，而节点要等账号 id
     * 出来才能建（{@code ref_user_id} 指向它）。建完节点立刻回写
     * （{@link #updateNodeId}）—— 两步在同一事务内，中间态不可见。
     *
     * <p>唯一键冲突由调用方翻成 {@code 10001} / {@code 10013}，按约束名区分。
     */
    @Insert("INSERT INTO sys_user "
            + "(id, username, password, user_type, real_name, phone, node_id, status, "
            + " pwd_reset_flag, tenant_id, create_by, update_by, remark) "
            + "VALUES (#{id}, #{username}, #{password}, #{userType}, #{realName}, #{phone}, 0, 0, "
            + " 1, #{tenantId}, #{operatorId}, #{operatorId}, #{remark})")
    int insertUser(@Param("id") Long id,
                   @Param("username") String username,
                   @Param("password") String password,
                   @Param("userType") Integer userType,
                   @Param("realName") String realName,
                   @Param("phone") String phone,
                   @Param("tenantId") Long tenantId,
                   @Param("operatorId") Long operatorId,
                   @Param("remark") String remark);

    /** 建节点后回写 {@code sys_user.node_id}（三写一事务的第 4 步）。 */
    @Update("UPDATE sys_user SET node_id = #{nodeId} WHERE id = #{userId} AND deleted_at = 0")
    int updateNodeId(@Param("userId") Long userId, @Param("nodeId") Long nodeId);

    /**
     * 改账号基础信息（接口 9 / 13 / 18）。
     *
     * <p>{@code username} 传 {@code null} 表示不修改（三个分册的参数表都写着「留空表示不修改」），
     * 故用 {@code COALESCE} 而不是无条件覆盖 —— 无条件覆盖会把它写成 NULL，
     * 而 {@code username} 是 {@code NOT NULL}，那是一次 500 而不是一次「没改」。
     */
    @Update("UPDATE sys_user SET real_name = #{realName}, phone = #{phone}, "
            + "       username = COALESCE(#{username}, username), update_by = #{operatorId} "
            + " WHERE id = #{userId} AND deleted_at = 0")
    int updateAccount(@Param("userId") Long userId,
                      @Param("realName") String realName,
                      @Param("phone") String phone,
                      @Param("username") String username,
                      @Param("operatorId") Long operatorId);

    /**
     * 删人：写 {@code deleted_at}，<b>不写 {@code status}</b>（理由见类注释）。
     *
     * <p>值取毫秒时间戳，与 {@code @TableLogic(delval = "UNIX_TIMESTAMP(NOW(3))*1000")}
     * 同源 —— 注解 SQL 不受 {@code @TableLogic} 管，这里手写同一个表达式。
     */
    @Update("UPDATE sys_user SET deleted_at = UNIX_TIMESTAMP(NOW(3))*1000, update_by = #{operatorId} "
            + " WHERE id = #{userId} AND deleted_at = 0")
    int softDeleteUser(@Param("userId") Long userId, @Param("operatorId") Long operatorId);

    /** 手机号<b>本租户内</b>唯一（{@code uk_tenant_phone}）→ {@code 10013}。租户条件由插件注入。 */
    @Select("SELECT COUNT(1) FROM sys_user "
            + " WHERE phone = #{phone} AND deleted_at = 0 "
            + "   AND (#{excludeUserId} IS NULL OR id <> #{excludeUserId})")
    long countByPhone(@Param("phone") String phone,
                      @Param("excludeUserId") Long excludeUserId);

    /**
     * 用户名<b>全局</b>唯一（{@code uk_username}）→ {@code 10001}。
     *
     * <p><b>本条必须跨租户判定</b>，而租户插件会给它注入 {@code AND tenant_id = ?} ——
     * 那样跨租户重名查不出来，最终由唯一索引抛 {@code DuplicateKeyException} 兜住（500）。
     * 所以这里<b>不依赖本方法</b>做最终判定：真正的把关是唯一索引，本方法只负责在同租户内
     * 提前给出可读的 {@code 10001}。跨租户重名仍由 {@code DuplicateKeyException} 翻译，
     * 与 {@code SysUserService#insertUserOrThrowDuplicate} 的取舍逐字相同
     * （「索引是真相，本方法是提示」）。
     */
    @Select("SELECT COUNT(1) FROM sys_user "
            + " WHERE username = #{username} AND deleted_at = 0 "
            + "   AND (#{excludeUserId} IS NULL OR id <> #{excludeUserId})")
    long countByUsername(@Param("username") String username,
                         @Param("excludeUserId") Long excludeUserId);

    /**
     * 按 {@code role_key} 取内置角色 id。
     *
     * <p>{@code sys_role} 是<b>平台级行放行表</b>（{@code EduMatrixTenantLineHandler}
     * 的 {@code platformRowTables}），四个内置角色 {@code tenant_id = 0} 对全租户可见。
     * <b>这里不写 {@code OR tenant_id = 0}</b> —— 契约 §2.9 明令那条放行逻辑只写在插件里一处。
     */
    @Select("SELECT id FROM sys_role WHERE role_key = #{roleKey} AND deleted_at = 0 LIMIT 1")
    Long selectRoleIdByKey(@Param("roleKey") String roleKey);

    /** 绑角色。{@code tenant_id} 显式传入，理由同 {@link #insertUser}。 */
    @Insert("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, create_by, update_by) "
            + "VALUES (#{id}, #{userId}, #{roleId}, #{tenantId}, #{operatorId}, #{operatorId})")
    int insertUserRole(@Param("id") Long id,
                       @Param("userId") Long userId,
                       @Param("roleId") Long roleId,
                       @Param("tenantId") Long tenantId,
                       @Param("operatorId") Long operatorId);

    /** 删人时解绑角色（逻辑删除，理由同 {@link #softDeleteUser}）。 */
    @Update("UPDATE sys_user_role SET deleted_at = UNIX_TIMESTAMP(NOW(3))*1000 "
            + " WHERE user_id = #{userId} AND deleted_at = 0")
    int softDeleteUserRoles(@Param("userId") Long userId);

    /**
     * 删除请求脱敏的 {@code sys_user} 那半边：{@code real_name} 覆写为姓氏 + {@code *}，
     * {@code phone} 覆写为掩码（PRD F7-3、契约 §7.2 第 3 条）。
     *
     * <h2>掩码在 SQL 里算，不在 Java 里算</h2>
     * <p>因为<b>原值不能出现在应用层</b>：一旦读回 Java，它就会进日志、进堆转储、
     * 进 APM 的 SQL 参数采样。一条 UPDATE 让原值从头到尾没离开过数据库。
     *
     * <p>{@code phone} 为 {@code NULL} 或不足 7 位时 {@code CONCAT} 结果为 {@code NULL}
     * / 短串，用 {@code CASE} 显式处理 —— <b>{@code NULL} 保持 {@code NULL}</b>：
     * 本来就没填的字段保持没填，这不是「置 NULL 脱敏」，恰恰相反
     * （区分「提没提过删除请求」靠的是 {@code org_student.anonymized_at}）。
     *
     * <p><b>{@code username} 不动</b>：它是登录账号，改了这个人就再也登不进来 ——
     * 而归档学员本就被 {@code 10015} 挡在登录之外，脱敏不需要再动它一次。
     * 且 {@code uk_username} 上还有唯一约束，批量脱敏会撞键。
     */
    @Update("UPDATE sys_user SET "
            + "  real_name = CONCAT(LEFT(real_name, 1), '*'), "
            + "  phone = CASE WHEN phone IS NULL THEN NULL "
            + "               WHEN CHAR_LENGTH(phone) < 7 THEN '****' "
            + "               ELSE CONCAT(LEFT(phone, 3), '****', RIGHT(phone, 4)) END "
            + " WHERE id = #{userId} AND deleted_at = 0")
    int anonymizeAccount(@Param("userId") Long userId);

    /** 账号的 {@code username}；不存在返回 {@code null}。 */
    @Select("SELECT username FROM sys_user WHERE id = #{userId} AND deleted_at = 0")
    String selectUsername(@Param("userId") Long userId);

    /** {@code id / real_name / phone} 的窄投影。 */
    class UserBriefRow {
        private Long id;
        private String realName;
        private String phone;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getRealName() {
            return realName;
        }

        public void setRealName(String realName) {
            this.realName = realName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }
}
