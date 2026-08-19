package com.edumatrix.auth.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumatrix.auth.entity.AuthLoginLog;

/**
 * {@code sys_login_log} 的写入（PRD F1-1：登录成功与失败都留痕）。
 *
 * <p>本模块只写不读 —— 登录日志的<b>查询</b>接口是 03-01 §8.1，归模块 05。
 *
 * <h2>⚠ 读侧已落地：{@code system/log/mapper/SysLoginLogQueryMapper}（模块 05）</h2>
 * <p>同一张表<b>两个 Mapper</b>，这是有意的：§8.1 的查询接口按 05-工程结构.md §C2
 * 落 {@code system/log/}，而 {@code check_backend_conventions.sh} 检查③
 * <b>禁止 {@code system} import {@code auth}</b>；反向做成 SPI 又要把 7 个查询条件的
 * DTO 与 VO 塞进 {@code common}，且与 {@code auth} 已实现的两个 {@code common/account}
 * SPI 形成双向 Bean 依赖。两侧<b>不共享任何 SQL</b>（这边 INSERT 全列，那边
 * SELECT + WHERE + 分页 + JOIN），所以不是「两份同源实现」那一族。
 *
 * <p><b>{@code sys_login_log} 的列变更必须同时改两处</b>：
 * {@link com.edumatrix.auth.entity.AuthLoginLog} 的字段声明，
 * 与 {@code SysLoginLogQueryMapper#selectPage} 的 SELECT 列表。
 * 那一侧有一条对称注释指回这里。<b>检查③ 拦 import 不拦表，这一处没有自动守卫</b>，
 * 靠的就是这两条互指的注释 —— 所以它们不是装饰。
 */
@Mapper
public interface AuthLoginLogMapper extends BaseMapper<AuthLoginLog> {
}
