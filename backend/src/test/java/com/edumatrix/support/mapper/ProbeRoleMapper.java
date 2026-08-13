package com.edumatrix.support.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.common.entity.TenantEntity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 探针：走 MyBatis-Plus 的 {@code insert} / {@code updateById}，用来验证
 * {@code AuditFieldHandler} 的署名字段自动填充。
 *
 * <p>借 {@code sys_role} 这张表只是因为它是继承 {@link TenantEntity} 的普通业务表，
 * 且模块 01 已经在用它做 §2.9 的验收。<b>它不是 {@code system/role} 的实体</b> ——
 * 那个归模块 03。
 */
@Mapper
public interface ProbeRoleMapper extends BaseMapper<ProbeRoleMapper.ProbeRole> {

    @TableName("sys_role")
    class ProbeRole extends TenantEntity {

        private static final long serialVersionUID = 1L;

        private String roleName;
        private String roleKey;
        private Integer status;
        private Integer sort;

        public String getRoleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
        }

        public String getRoleKey() {
            return roleKey;
        }

        public void setRoleKey(String roleKey) {
            this.roleKey = roleKey;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public Integer getSort() {
            return sort;
        }

        public void setSort(Integer sort) {
            this.sort = sort;
        }
    }
}
