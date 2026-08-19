package com.edumatrix.system.file.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.file.entity.SysFile;

/**
 * {@code sys_file} 的唯一入口（03-01 §7.1~§7.3 与 {@code TempFileCleanupJob}）。
 *
 * <p><b>租户条件一律由插件注入</b>，本类一个字都不写。{@code sys_file} 不在契约 §2.9
 * 的平台级放行清单里 —— 放行会让任意租户读到别家机构的学生名单文件。
 */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {

    /**
     * 7 天保留期清理的候选（{@code TempFileCleanupJob}）。
     *
     * <h2>为什么是<b>正向白名单</b>而不是 {@code NOT IN} 黑名单</h2>
     * <p>白名单下新增 bizType 时默认<b>不清理</b>；黑名单下默认<b>清理</b>。
     * 后者意味着模块 08 加一个 {@code course_cover} 就会让全部课程封面
     * 在 7 天后被静默删掉 —— 表现是课程列表的图全变成裂图，
     * 而没有任何一处会报错。方向选错的代价不对称，所以选白名单。
     *
     * <p>{@code bizTypes} 由调用方从 {@code FileBizType} 里筛出，不写死在 SQL 里。
     *
     * <p><b>只查未删除的行</b>：{@code deleted_at = 0} 由 {@code @TableLogic} 自动追加，
     * 这里不重复写（写了会变成 {@code AND deleted_at = 0 AND deleted_at = 0}，
     * 不出错但会让下一个人以为逻辑删除没有全局生效）。
     */
    @Select("<script>"
            + "SELECT id, file_url AS fileUrl, biz_type AS bizType, storage, tenant_id AS tenantId "
            + "FROM sys_file "
            + "WHERE create_time &lt; #{deadline} "
            + "  AND biz_type IN <foreach item='t' collection='bizTypes' open='(' separator=',' close=')'>#{t}</foreach> "
            + "ORDER BY id LIMIT #{limit}"
            + "</script>")
    List<SysFile> selectCleanupCandidates(@Param("deadline") LocalDateTime deadline,
                                          @Param("bizTypes") List<String> bizTypes,
                                          @Param("limit") int limit);
}
