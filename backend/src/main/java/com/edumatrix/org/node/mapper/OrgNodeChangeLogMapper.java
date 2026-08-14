package com.edumatrix.org.node.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.org.node.entity.OrgNodeChangeLog;

/**
 * {@code org_node_change_log} 的写入（<b>只插入</b>）。
 *
 * <p><b>没有更新与删除不是没写完</b>：PRD F1-9 规则 2「轨迹只增不改不删，
 * 系统不提供任何编辑/删除接口」；DDL 的 {@code deleted_at} 注释也逐字写着
 * 「轨迹业务上禁止删除，恒为 0」。{@code BaseMapper} 自带的 {@code deleteById} /
 * {@code updateById} <b>一处都不要调</b>。
 *
 * <p>读侧（03-02 接口 26 学生异动轨迹）归模块 07，本模块不提供查询。
 */
@Mapper
public interface OrgNodeChangeLogMapper extends BaseMapper<OrgNodeChangeLog> {
}
