package com.edumatrix;

import java.util.TimeZone;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EduMatrix 后端启动类。
 *
 * <p><b>为什么在这里设 JVM 默认时区</b>：{@code 00-通用约定} §6 与契约 §6.1 要求
 * <b>服务器、数据库、接口三层一致</b>地跑在东八区。数据库侧由
 * {@code connection-init-sql: SET time_zone = '+08:00'} 与容器的 {@code --default-time-zone}
 * 保证，接口侧由 {@code common/config/JacksonConfig} 保证，而 JVM 这一层如果不显式设定，
 * 就取操作系统时区 —— 本机开发与线上容器（通常是 UTC）会给出相差 8 小时的
 * {@code LocalDateTime.now()}，且不报错。
 *
 * <p>契约 §6.1 已写死「全部租户均位于东八区，不支持跨时区租户」，这不是「暂未支持」：
 * {@code stat_*} 按自然日结算、作业 {@code deadline} 判定、{@code vod_heartbeat_log}
 * 按月分区的边界，三处口径都建立在「只有一个时区」之上。
 *
 * <p><b>包结构不要改</b>（05-工程结构.md §C2 / §F1）：领域包只能是契约 §6.2 的八个路由前缀
 * （{@code auth system org course vod question homework stat}）外加三个非领域包
 * （{@code common job integration}）。特别是 {@code com.edumatrix.org} —— 它与
 * {@code org.springframework} 顶层同名但实测无碍（Java 一律从根解析包名），
 * <b>不要"顺手改成 organization"</b>，改了就与契约 §6.2 的前缀对不上，
 * URL 与包路径的互推关系断掉。
 */
@SpringBootApplication
@MapperScan("com.edumatrix.**.mapper")
public class EduMatrixApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(EduMatrixApplication.class, args);
    }
}
