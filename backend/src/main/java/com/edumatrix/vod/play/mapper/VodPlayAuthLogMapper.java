package com.edumatrix.vod.play.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.vod.play.entity.VodPlayAuthLog;

/** 播放凭证审计写入。 */
@Mapper
public interface VodPlayAuthLogMapper extends BaseMapper<VodPlayAuthLog> {
}
