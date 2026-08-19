package com.edumatrix.support.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.edumatrix.common.tenant.TenantHelper;

/**
 * {@code TempFileCleanupJobIT} 的探针（<b>只在 {@code src/test}</b>）。
 *
 * <h2>为什么要造"7 天前的行"而不是等时间</h2>
 * <p>{@code sys_file.create_time} 的 DDL 是 {@code DEFAULT CURRENT_TIMESTAMP}，
 * 走正常上传路径写出来的行永远是"刚刚"。而要验的恰恰是<b>边界</b>
 * （8 天前会清、6 天前不清），所以必须能显式指定 {@code create_time}。
 *
 * <h2>为什么日志表的计数走裸 SQL</h2>
 * <p>T-9 要断言的是「{@code TempFileCleanupJob} 跑完之后两张日志表<b>一行不少</b>」。
 * 用 {@code system/log} 的查询 Mapper 去数会带上租户条件与分页，
 * 而这里要的是<b>整张表的物理行数</b> —— 那才是"有没有被删"的直接证据。
 *
 * <p>{@code tenant_id} 由调用方用 {@link TenantHelper#runWithTenant} 提供，
 * 与被测路径走同一条通道。
 */
@Mapper
public interface ProbeCleanupMapper {

    /** 造一行指定 {@code create_time} 的 {@code sys_file}。 */
    @Insert("INSERT INTO sys_file (id, file_name, file_url, file_size, file_type, storage, "
            + "biz_type, tenant_id, create_time) "
            + "VALUES (#{id}, #{fileName}, #{fileUrl}, 1024, 'xlsx', #{storage}, "
            + "#{bizType}, #{tenantId}, #{createTime})")
    int insertFileAt(@Param("id") Long id,
                     @Param("fileName") String fileName,
                     @Param("fileUrl") String fileUrl,
                     @Param("storage") Integer storage,
                     @Param("bizType") String bizType,
                     @Param("tenantId") Long tenantId,
                     @Param("createTime") String createTime);

    /** 取某行的 {@code deleted_at}；0 = 未删除。查不到返回 {@code null}。 */
    @Select("SELECT deleted_at FROM sys_file WHERE id = #{id}")
    Long selectDeletedAt(@Param("id") Long id);

    /** 造一行指定 {@code login_time} 的登录日志（T-9 用）。 */
    @Insert("INSERT INTO sys_login_log (id, user_id, username, ip, status, msg, login_time, tenant_id) "
            + "VALUES (#{id}, NULL, #{username}, '127.0.0.1', 1, '探针', #{loginTime}, #{tenantId})")
    int insertLoginLogAt(@Param("id") Long id,
                         @Param("username") String username,
                         @Param("loginTime") String loginTime,
                         @Param("tenantId") Long tenantId);

    /** 造一行指定 {@code oper_time} 的操作日志（T-9 用）。 */
    @Insert("INSERT INTO sys_oper_log (id, user_id, module, action, method, oper_time, tenant_id) "
            + "VALUES (#{id}, NULL, '探针模块', #{action}, '探针', #{operTime}, #{tenantId})")
    int insertOperLogAt(@Param("id") Long id,
                        @Param("action") String action,
                        @Param("operTime") String operTime,
                        @Param("tenantId") Long tenantId);

    /** 整张表的物理行数 —— T-9 的直接证据。 */
    @Select("SELECT COUNT(*) FROM sys_login_log")
    long countLoginLogRows();

    /** 同上。 */
    @Select("SELECT COUNT(*) FROM sys_oper_log")
    long countOperLogRows();

    /** 指定行是否还在（不看 {@code deleted_at}，看它有没有被<b>物理</b>删掉）。 */
    @Select("SELECT COUNT(*) FROM sys_login_log WHERE id = #{id}")
    int loginLogExists(@Param("id") Long id);

    /** 同上。 */
    @Select("SELECT COUNT(*) FROM sys_oper_log WHERE id = #{id}")
    int operLogExists(@Param("id") Long id);
}
