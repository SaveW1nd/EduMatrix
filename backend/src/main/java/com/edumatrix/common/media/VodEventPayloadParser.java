package com.edumatrix.common.media;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把队列里的一条消息体解析成 {@link VodEvent}。
 *
 * <h2>为什么单独一个类，而不是写在 {@code SmqClient} 里</h2>
 * <p><b>为了让它能在没有云账号的情况下被测到，并且让集成测试走的是同一段解析代码。</b>
 * 生产上 {@code /etc/edumatrix/db.env} 里连 {@code ALIYUN_SMQ_*} 都还没有，
 * 而「报文形状写错」是本模块最可能的生产事故 —— 假实现全绿而生产上每条消息都解析失败。
 * 把解析抽出来之后：① 可以拿<b>真实报文</b>写单元测试（见 {@code VodEventPayloadParserTest}）；
 * ② 集成测试里的假队列喂的是<b>原始 JSON 文本</b>，走的仍是本类，不是另一份手搓的解析。
 *
 * <h2>本类<b>不抛异常</b></h2>
 * <p>解析不出来时返回一个 {@link VodEvent#parsed()} 为 {@code false} 的对象，
 * 由调用方走孤儿处置（{@code sys_oper_log} + {@code vod_callback_orphan_total{reason="parse_failed"}}
 * + 删消息）。抛异常会让整轮消费中断，而队列里可能只有那一条是坏的。
 *
 * <h2>Base64：两种编码都吃</h2>
 * <p>轻量消息队列的消息体<b>是否 Base64 编码取决于队列的编码设置</b>
 * （控制台同时展示「原始内容」与「Base64 解码后」）。猜错一边的后果是<b>每条消息都解析失败</b>。
 * 所以这里不猜：先按 JSON 试，不像 JSON 再 Base64 解一次再试。两条路都有测试钉住。
 */
public final class VodEventPayloadParser {

    /** 契约 §6.1：服务器、数据库、接口三层统一东八区，不支持跨时区租户。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper JSON = new ObjectMapper();

    private VodEventPayloadParser() {
    }

    /**
     * @param receiptHandle 队列给的删除句柄，原样带进 {@link VodEvent}
     * @param body          消息体（JSON 文本，或它的 Base64）
     */
    public static VodEvent parse(String receiptHandle, String body) {
        String json = decodeIfBase64(body);
        JsonNode root;
        try {
            root = JSON.readTree(json == null ? "" : json);
        } catch (Exception e) {
            return unparsable(receiptHandle, json);
        }
        if (root == null || !root.isObject()) {
            return unparsable(receiptHandle, json);
        }
        return new VodEvent(receiptHandle,
                text(root, "EventType"),
                text(root, "Status"),
                text(root, "VideoId"),
                eventTime(text(root, "EventTime")),
                number(root, "Size"),
                text(root, "ErrorCode"),
                text(root, "ErrorMessage"),
                json);
    }

    /**
     * <b>UTC → 东八区</b>。报文里的 {@code EventTime} 形如 {@code 2026-08-19T15:07:07Z}
     * （末尾的 {@code Z} 就是 UTC），对应东八区 {@code 2026-08-19 23:07:07}，<b>差 8 小时</b>。
     *
     * <p><b>不转换是一次典型的「不报错的故障」</b>：字段齐全、值也像时间，只是全部早 8 小时。
     * 契约 §6.1 要求三层统一 {@code Asia/Shanghai}，且 {@code vod_heartbeat_log} 的月分区边界
     * 与 {@code stat_*} 的自然日结算都建立在「只有一个时区」上 —— 这里错了会往下游渗。
     *
     * <p><b>用 {@link Instant#parse} 而不是 {@code LocalDateTime.parse}</b>：后者会把
     * {@code Z} 当成解析失败（它不认时区偏移），或在换用宽松格式后<b>把 UTC 时刻直接当成本地时刻</b> ——
     * 那正好就是「早 8 小时」这个 bug 的写法。
     *
     * @return 解析不出时 {@code null}（本字段不参与 {@link VodEvent#parsed()}：
     *         时间读不出来不足以让整条事件作废，状态推进不依赖它）
     */
    static LocalDateTime eventTime(String utcText) {
        if (utcText == null || utcText.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(utcText.trim()), ZONE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 不像 JSON 就当作 Base64 再解一次。<b>解不开或解出来仍不像 JSON 时原样返回</b> ——
     * 让它走下游的「解析失败」分支，而不是在这里吞掉。
     */
    private static String decodeIfBase64(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8).trim();
            return decoded.startsWith("{") ? decoded : trimmed;
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private static VodEvent unparsable(String receiptHandle, String body) {
        return new VodEvent(receiptHandle, null, null, null, null, null, null, null, body);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Long number(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() || !node.canConvertToLong() ? null : node.asLong();
    }
}
