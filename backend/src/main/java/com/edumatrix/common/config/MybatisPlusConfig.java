package com.edumatrix.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.edumatrix.common.id.IdWorker;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.tenant.EduMatrixTenantLineHandler;
import com.edumatrix.common.tenant.PlatformRowTenantLineInnerInterceptor;

/**
 * MyBatis-Plus 装配。
 *
 * <h2>拦截器顺序不能随便排</h2>
 * <p>MyBatis-Plus 的 {@code InnerInterceptor} 按加入顺序执行，官方要求
 * <b>多租户在前、分页在后</b>。反过来的话，分页插件会先按<b>没有租户条件</b>的 SQL
 * 去生成 {@code COUNT} 语句 —— 于是列表里是本租户的 10 条，{@code total} 却是全库的 3000 条。
 * 这个错不会报错，只会让分页器显示 300 页、翻到第 2 页却空空如也。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(EduMatrixTenantLineHandler tenantLineHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ① 多租户：必须排在分页之前
        interceptor.addInnerInterceptor(new PlatformRowTenantLineInnerInterceptor(tenantLineHandler));

        // ② 分页：pageSize 上限 100（00-通用约定 §4.1）。
        //    这是第二道防线 —— 第一道是 PageResult.normalizePageSize。两道都要有：
        //    只靠第一道时，某个接口忘了调用它，一次 pageSize=100000 就是一次拖库。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit((long) PageResult.MAX_PAGE_SIZE);
        // 页码超出总页数时返回空列表，而不是回到第一页 —— 回到第一页会让调用方
        // 以为自己拿到的是第 N 页的数据
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // ③ 全表更新/删除阻断。
        //    契约要求核心业务数据禁止物理删除、一律逻辑删除；而一条漏了 WHERE 的
        //    UPDATE 会把整张表的 deleted_at 一次写掉，且逻辑删除本身让它"看起来"是合法操作。
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    /**
     * 让 MyBatis-Plus 的 {@code id-type: assign_id} 走本项目的雪花实现。
     *
     * <p>不接这一手的话会有两个序列：实体不写 id 时用 MP 自带的生成器，
     * 业务代码显式调用时用 {@link IdWorker} —— 两者的 workerId 来源不同，
     * 多实例部署下重复 ID 的概率翻倍，而且排查时会以为只有一个地方要看。
     */
    @Bean
    public IdentifierGenerator identifierGenerator(IdWorker idWorker) {
        return entity -> idWorker.next();
    }
}
