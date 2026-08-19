package com.edumatrix.course.catalog.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.course.catalog.entity.CrsMaterial;

/** {@code crs_material}。租户条件由插件注入（契约 §2.9）。 */
@Mapper
public interface CrsMaterialMapper extends BaseMapper<CrsMaterial> {
}
