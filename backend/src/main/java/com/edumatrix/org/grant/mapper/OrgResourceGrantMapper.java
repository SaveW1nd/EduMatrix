package com.edumatrix.org.grant.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.org.grant.entity.OrgResourceGrant;

/**
 * {@code org_resource_grant} 的<b>写侧</b> Mapper —— 模块 11 专用。
 *
 * <p><b>读侧不在这里</b>：「某节点能否使用某资源」走
 * {@code common/grant/ResourceGrantReader}（需方定案 F，全系统唯一口径）。
 * 本 Mapper 只负责本模块自己的写入与本模块接口的分页查询。
 *
 * <p><b>租户条件由插件注入</b>，一个字不写（契约 §2.9）；
 * {@code deleted_at = 0} 由 {@code @TableLogic} 自动追加（走 MyBatis-Plus 的方法时）。
 */
@Mapper
public interface OrgResourceGrantMapper extends BaseMapper<OrgResourceGrant> {
}
