package com.edumatrix.org.node.mapper;

import java.util.List;

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
 * <h2>⚠ 但它跨的是 {@code system} 领域的表，登记在交接清单里</h2>
 * <p>与 {@code system/user/mapper/StudentQuotaMapper}（{@code system} 读 {@code org_student}）
 * <b>互为镜像</b>：都是「表在对方领域、对方 Service 还没有」时开的窄口。
 * 将来 {@code system/user} 对外暴露 Service 后改调它 —— 届时检查③禁止直接 import 实体，
 * 所以对方 Service 要返回自己的 DTO。
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
