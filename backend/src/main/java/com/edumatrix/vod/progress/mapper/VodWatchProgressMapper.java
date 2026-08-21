package com.edumatrix.vod.progress.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.vod.progress.entity.VodWatchProgress;

/**
 * 学习进度读写。<b>模块 12 只读、模块 13 读写，共用本 Mapper</b>——
 * 不要在模块 13 里另起一个（同一张表两份实现，约定检查⑥ 的形态）。
 */
@Mapper
public interface VodWatchProgressMapper extends BaseMapper<VodWatchProgress> {
}
