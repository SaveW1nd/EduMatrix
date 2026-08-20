package com.edumatrix.org.grant.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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

    /**
     * 批量插入授权行（接口 38）。
     *
     * <h2>为什么不是循环调 {@code insert(entity)}</h2>
     * <p>单次上限是 <b>5000 行</b>（契约 §2.5）。逐行插就是 5000 次往返，
     * 一次点击可以慢到秒级 —— 而<b>接口仍然返回 200</b>。
     * 调用方按 500 一批切好再调本方法。
     *
     * <h2>{@code tenant_id} 不写在这里，交给插件注入</h2>
     * <p>契约 §2.9：本接口是<b>有会话</b>的写入，租户条件由 MyBatis-Plus 租户插件
     * 解析 INSERT 后自动补列。手写一份就有了两处真相，而其中一处迟早写错。
     *（{@code NodeAccountMapper#insertUserRole} 显式传 {@code tenantId} 是另一回事：
     * 那条跑在<b>租户开通</b>流程里，那时会话属于平台超管、不是目标租户。）
     *
     * <h2>{@code deleted_at} 显式写 0</h2>
     * <p>注解 SQL 不受 {@code @TableLogic} 管（那只作用于 MyBatis-Plus 自己生成的语句），
     * DDL 虽有默认值 0，但把它写出来是为了让「这一列的取值口径」在读代码时就能看见 ——
     * 撤销时写的是毫秒时间戳，不是 1（见 {@link OrgResourceGrant} 类注释）。
     */
    @Insert("<script>"
            + "INSERT INTO org_resource_grant (id, resource_type, resource_id, target_node_id, "
            + "  valid_start, valid_end, grant_source, source_ref_id, grant_by, grant_time, "
            + "  create_by, create_time, update_by, update_time, deleted_at) VALUES "
            + "<foreach collection='rows' item='r' separator=','>"
            + "(#{r.id}, #{r.resourceType}, #{r.resourceId}, #{r.targetNodeId}, "
            + " #{r.validStart}, #{r.validEnd}, #{r.grantSource}, #{r.sourceRefId}, "
            + " #{operatorId}, NOW(), #{operatorId}, NOW(), #{operatorId}, NOW(), 0)"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("rows") List<OrgResourceGrant> rows,
                    @Param("operatorId") Long operatorId);
}
