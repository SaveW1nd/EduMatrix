package com.edumatrix.system.tenant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.system.tenant.entity.SysTenant;

/**
 * {@code sys_tenant} 的读写（03-01 §5）。
 *
 * <p><b>本表不进租户插件</b>：它没有 {@code tenant_id} 列，是全库仅有的两张纯平台级表之一
 * （契约 §2.9），{@code ignoreTable} 对它恒返回 true。因此这里的查询<b>不带也不需要
 * 任何租户条件</b>，而这<b>不是</b>一次跨租户越权读——本组接口本就是平台级操作
 * （§5 导语：「本组接口为平台级操作，不做租户注入」），且 {@code system:tenant:*}
 * 六个权限标识只绑了 {@code super_admin}。
 */
@Mapper
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    /**
     * 03-01 §5.3 步骤③：回写 {@code root_node_id}。
     *
     * <p><b>单独一条 SQL 而不是 {@code updateById}(实体)</b>：这一步的语义是
     * 「把步骤①留下的 NULL 补上」，只该动这一列。用实体更新要么带上全部字段
     * （把刚插入的值再写一遍，多一次覆盖窗口），要么依赖 MP 的"非 null 才更新"
     * ——而<b>本模块恰恰在跟 NULL 打交道</b>，靠"null 不更新"表达"要把 null 改成非 null"
     * 是把两件相反的事压在同一个约定上。
     */
    @Update("UPDATE sys_tenant SET root_node_id = #{rootNodeId} WHERE id = #{tenantId}")
    int fillRootNodeId(@Param("tenantId") Long tenantId, @Param("rootNodeId") Long rootNodeId);
}
