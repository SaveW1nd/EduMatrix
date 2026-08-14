package com.edumatrix.auth.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.auth.entity.AuthLoginLog;

/**
 * {@code sys_login_log} 的写入（PRD F1-1：登录成功与失败都留痕）。
 *
 * <p>本模块只写不读 —— 登录日志的<b>查询</b>接口是 03-01 §8.1，归模块 05。
 */
@Mapper
public interface AuthLoginLogMapper extends BaseMapper<AuthLoginLog> {
}
