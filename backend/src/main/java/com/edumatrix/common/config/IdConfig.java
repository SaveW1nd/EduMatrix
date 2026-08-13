package com.edumatrix.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.edumatrix.common.id.IdWorker;

/**
 * 雪花 ID 装配。
 *
 * <p>{@code SNOWFLAKE_WORKER_ID} / {@code SNOWFLAKE_DATACENTER_ID} 是<b>部署级参数</b>
 * （05-工程结构.md §F4，本项由模块 01 新增登记），走环境变量、不入库、不按机构分配。
 *
 * <p><b>启动时把取到的值打进日志</b>：多实例部署下两台机器配同一个 workerId 会产生重复主键，
 * 而重复主键只在写入时才报错、报的还是唯一键冲突 —— 日志里有这一行，排查会快很多。
 */
@Configuration
public class IdConfig {

    private static final Logger log = LoggerFactory.getLogger(IdConfig.class);

    @Bean
    public IdWorker idWorker(@Value("${edumatrix.id.worker-id:0}") long workerId,
                             @Value("${edumatrix.id.datacenter-id:0}") long datacenterId) {
        IdWorker idWorker = new IdWorker(workerId, datacenterId);
        // 静态门面与 Bean 指向同一个实例：业务代码调 IdWorker.nextId()、
        // MyBatis-Plus 的 assign_id 走 IdentifierGenerator，两条路必须是同一个序列
        IdWorker.setInstance(idWorker);
        log.info("雪花 ID 就绪 workerId={} datacenterId={}（多实例部署时必须逐台不同）",
                workerId, datacenterId);
        return idWorker;
    }
}
