package com.edumatrix.vod.media.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.edumatrix.common.media.VodEvent;
import com.edumatrix.common.media.VodMediaClient;
import com.edumatrix.common.media.VodPlayInfo;
import com.edumatrix.common.media.VodPlayStream;

/**
 * 挑流：反调 {@code GetPlayInfo}，按契约 §1 第 2 条挑出<b>唯一一路</b>加密 HLS。
 *
 * <h2>为什么不直接用事件报文里那一路</h2>
 * <p>真实报文的 {@code StreamInfos[0]} <b>已经带了 {@code Format} 与 {@code Encrypt}</b>，
 * 理论上不调 {@code GetPlayInfo} 也判得出来。但那条 {@code FileUrl} 实测仍是 {@code http://}，
 * 存进 {@code hls_url} 会被浏览器按混合内容拦截。
 * 所以契约 §2.8「报文里的 URL 一律不采信」在这里是<b>双重理由</b>：既为 HTTPS，也为防伪造
 * （报文内容不参与写库）。事件只当作<b>状态推进信号</b>。
 *
 * <p>事件里那些流仍有用：<b>先按事件形态过一遍</b>，能提前发现「模板组配了多档」
 * 这类问题并把诊断信息带进日志 —— 而不必等 {@code GetPlayInfo} 回来才知道。
 */
@Component
public class VodPlayInfoResolver {

    private final VodMediaClient vodClient;

    public VodPlayInfoResolver(VodMediaClient vodClient) {
        this.vodClient = vodClient;
    }

    /**
     * @param playUrl         挑中的 https 播放地址
     * @param durationSeconds 向上取整的秒数
     * @param sizeBytes       该路字节数；取不到为 null
     * @param reason          挑不到时说清为什么（写进 {@code remark} 与 ERROR 日志）
     */
    public record Picked(String playUrl, int durationSeconds, Long sizeBytes, String reason) {
        public boolean usable() {
            return playUrl != null;
        }

        static Picked fail(String reason) {
            return new Picked(null, 0, null, reason);
        }
    }

    /**
     * 挑流。<b>抛 {@code VodNotReadyException} 时由调用方按「未就绪、不删消息」处置</b> ——
     * 本方法不吞它。
     */
    public Picked pick(VodEvent event) {
        // ① 先看事件自己报了几路：多路 = 模板组配了多档（契约 §1 第 1 条警告的情形），
        //    这条诊断信息在 GetPlayInfo 回来之前就能拿到
        List<?> fromEvent = event.encryptedHlsStreams();
        if (event.streams() != null && event.streams().size() > 1) {
            return Picked.fail("模板组配了多档：事件报了 " + event.streams().size()
                    + " 路流。契约 §1 部署约定第 1 条只允许单一清晰度加密 HLS 输出 —— "
                    + "多档下 hls_url 只存得下一路，学生实际看到的清晰度不可控");
        }
        if (event.streams() != null && !event.streams().isEmpty() && fromEvent.isEmpty()) {
            return Picked.fail("事件里那一路不是「加密 m3u8 且自身转码成功」："
                    + event.streams().get(0).describe());
        }

        // ② 播放地址一律反调 GetPlayInfo 取 —— 见类注释
        VodPlayInfo playInfo = vodClient.getPlayInfo(event.vodFileId());
        List<VodPlayStream> candidates = playInfo.streams() == null ? List.of()
                : playInfo.streams().stream().filter(VodPlayStream::isEncryptedHls).toList();

        if (candidates.isEmpty()) {
            return Picked.fail("GetPlayInfo 挑不出 Format==m3u8 且 Encrypt==1 的流（共 "
                    + (playInfo.streams() == null ? 0 : playInfo.streams().size()) + " 路）");
        }
        if (candidates.size() > 1) {
            // 分册只写了「空集置 3」，多路没写（F-54）。同样置 3：
            // 任选一路 = 学生看到的清晰度不可控，正是契约 §1 第 1 条要禁的
            return Picked.fail("GetPlayInfo 挑出 " + candidates.size()
                    + " 路加密 m3u8 —— 模板组配了多档，无法确定该给学生哪一路");
        }
        VodPlayStream stream = candidates.get(0);
        if (!stream.isHttps()) {
            // 【不做 http→https 改写】改写是猜测（未必可达），而存一条播不了的地址
            // 是「看起来成功了」的失败，比直接失败难排查得多
            return Picked.fail("挑中的播放地址不是 https —— 全站 HTTPS 下会被浏览器按混合内容拦截。"
                    + "不做协议改写（改了也未必可达）");
        }
        Integer duration = stream.durationSeconds();
        if (duration == null || duration <= 0) {
            // 【绝不当成 0】那会写出一条 duration=0 的「正常」媒资：
            // 课时能上架、完播判定的分母是 0，且不报错
            return Picked.fail("时长解析不出或非正：Duration=" + stream.duration());
        }
        return new Picked(stream.playUrl(), duration, stream.sizeBytes(), null);
    }
}
