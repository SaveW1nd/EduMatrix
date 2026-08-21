package com.edumatrix.system.user.service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.system.user.entity.SysUser;
import com.edumatrix.system.user.mapper.SysUserMapper;

/**
 * {@code common/account/UserNameReader} 的实现：把 {@code system} 领域的
 * 用户姓名暴露给 {@code course}（模块 08 的 {@code createByName} 作者署名）。
 *
 * <p>与 {@code auth/session/AuthAccountProvider}、
 * {@code system/file/service/SystemFileProvider} 同构。<b>不含任何判定</b>。
 *
 * <p>只取 {@code id} 与 {@code real_name} 两列 —— 署名场景不需要手机号，
 * 而 {@code sys_user} 里装着手机号（PRD §7.6 最小必要）。
 */
@Component
public class SysUserNameProvider implements UserNameReader {

    private final SysUserMapper sysUserMapper;

    public SysUserNameProvider(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public Map<Long, String> realNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getRealName)
                .in(SysUser::getId, userIds.stream().distinct().toList()));
        Map<Long, String> names = new LinkedHashMap<>();
        for (SysUser user : users) {
            names.put(user.getId(), user.getRealName());
        }
        return names;
    }

}
