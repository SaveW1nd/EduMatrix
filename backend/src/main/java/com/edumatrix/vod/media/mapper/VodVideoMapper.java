package com.edumatrix.vod.media.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.vod.media.entity.VodVideo;

/**
 * {@code vod_video} 的 Mapper。租户条件由插件自动注入（契约 §2.1 / §2.9）。
 *
 * <p><b>事件消费那条「按 {@code vod_file_id} 反查租户」的查询不在这里</b> ——
 * 它必须绕过租户插件（那一刻还不知道租户是谁，租户是这次查询的<b>结果</b>而不是前提），
 * 故单独放在 {@code VodEventLookupMapper} 上，用 {@code @InterceptorIgnore} 逐语句豁免，
 * 并被 {@code scripts/check_backend_conventions.sh} 的检查⑤ 列进「跨租户逃生舱」清单。
 */
@Mapper
public interface VodVideoMapper extends BaseMapper<VodVideo> {
}
