package com.edumatrix.system.user.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.user.entity.SystemOrgNodeChangeLog;

/**
 * {@code org_node_change_log} 的窄写入，<b>只写 03-01 §2.2 的一条建档轨迹</b>。
 *
 * <h2>⚠ 临时构件，与 {@link SystemOrgNodeChangeLog} 同批交接给模块 06</h2>
 *
 * <p><b>只有插入，没有更新与删除</b>，这不是没写完：PRD F1-9 规则 2
 * 「轨迹只增不改不删，系统不提供任何编辑/删除接口」。DDL 的 {@code deleted_at}
 * 注释也逐字写着「轨迹业务上禁止删除，恒为 0」。
 */
@Mapper
public interface SystemOrgNodeChangeLogMapper extends BaseMapper<SystemOrgNodeChangeLog> {
}
