package com.edumatrix.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.edumatrix.EduMatrixApplication;

/**
 * 集成测试的公共标注：起真实的 Spring 上下文，连
 * {@code deploy/docker-compose.dev.yml} 起的 MySQL 8 与 Redis 7。
 *
 * <p><b>为什么不用内存库</b>：本模块要验的三件事 —— Flyway 基线建出 41 张表、
 * {@code vod_heartbeat_log} 的月分区就位、租户插件对 {@code sys_role} 放行
 * {@code tenant_id = 0} —— <b>没有一件能在 H2 上验</b>：H2 不支持 MySQL 的
 * RANGE 分区语法，也不会以同样的方式解析基线里的 {@code CREATE DATABASE / USE}。
 * 在内存库上跑通只能证明"在内存库上跑得通"。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(classes = {EduMatrixApplication.class, TestSupportConfiguration.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public @interface IntegrationTest {
}
