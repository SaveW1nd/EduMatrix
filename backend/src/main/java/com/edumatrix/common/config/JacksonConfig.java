package com.edumatrix.common.config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.edumatrix.common.id.IdJacksonModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;

/**
 * JSON 序列化装配：ID 字符串化 + 时间格式 + 时区（00-通用约定 §5 / §6，契约 §6.1）。
 *
 * <h2>时间</h2>
 * <table border="1">
 *   <caption>00-通用约定 §6</caption>
 *   <tr><th>项目</th><th>约定</th></tr>
 *   <tr><td>时区</td><td>统一东八区 {@code Asia/Shanghai}，<b>服务器、数据库、接口三层一致</b></td></tr>
 *   <tr><td>日期时间</td><td>{@code yyyy-MM-dd HH:mm:ss}（请求与响应一致）</td></tr>
 *   <tr><td>纯日期</td><td>{@code yyyy-MM-dd}（如 {@code statDate}、{@code entryDate}）</td></tr>
 *   <tr><td>时长</td><td>一律以<b>秒</b>为整数传输，前端自行格式化</td></tr>
 * </table>
 *
 * <p><b>接口不接受也不返回时间戳（毫秒数）与 ISO8601 带时区格式</b> —— 避免多端解析不一致。
 * 所以这里显式关掉 {@code WRITE_DATES_AS_TIMESTAMPS} 并给三个 JSR-310 类型指定格式，
 * 而不是依赖 {@code spring.jackson.date-format}（后者只作用于 {@code java.util.Date}，
 * 管不到 {@code LocalDateTime}）。
 *
 * <p><b>东八区不是"暂时只支持"</b>：契约 §6.1 写明全部租户均位于东八区，且
 * {@code stat_*} 按自然日结算、作业 {@code deadline} 判定、{@code vod_heartbeat_log}
 * 按月分区的边界三处口径都建立在"只有一个时区"之上。要接海外机构必须重新定义这三处，
 * 不是加一个时区字段就能解决。
 */
@Configuration
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String TIME_PATTERN = "HH:mm:ss";
    public static final String ZONE_ID = "Asia/Shanghai";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer edumatrixJacksonCustomizer() {
        DateTimeFormatter dateTime = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter date = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter time = DateTimeFormatter.ofPattern(TIME_PATTERN);

        JavaTimeModule javaTime = new JavaTimeModule();
        javaTime.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTime));
        javaTime.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTime));
        javaTime.addSerializer(LocalDate.class, new LocalDateSerializer(date));
        javaTime.addDeserializer(LocalDate.class, new LocalDateDeserializer(date));
        javaTime.addSerializer(LocalTime.class, new LocalTimeSerializer(time));
        javaTime.addDeserializer(LocalTime.class, new LocalTimeDeserializer(time));

        return builder -> builder
                .timeZone(TimeZone.getTimeZone(ZONE_ID))
                .modules(javaTime, new IdJacksonModule())
                // 不输出时间戳：接口一律用 yyyy-MM-dd HH:mm:ss
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
