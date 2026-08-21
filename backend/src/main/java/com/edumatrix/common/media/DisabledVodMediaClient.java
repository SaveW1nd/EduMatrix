package com.edumatrix.common.media;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * 点播<b>未配置</b>时的空实现 —— 与 {@code integration/aliyun/VodClient} <b>互为反面</b>，
 * 装配条件写在同一组属性上（{@code edumatrix.vod.region} / {@code .template-group-id}），
 * 与 {@code common/file/LocalObjectStorage} ↔ {@code OssClient} 同型。
 *
 * <p>为什么是「默认关掉 + 一条写明后果的 WARN」而不是启动失败，见
 * {@link DisabledVodEventSource} 的类注释（同一条论证）。
 *
 * <h2>被调到时<b>响亮失败</b>，不返回一个假的空结果</h2>
 * <p>上传凭证接口在这里返回 {@code null} 或空凭证的话，前端会拿到一个
 * 「200 但传不上去」的响应 —— 接口 200、字段齐全、结果错，正是 1 号失败模式。
 * 所以这里抛 {@link IllegalStateException}，由全局异常处理器转 500，
 * 并在 message 里写清<b>缺哪个环境变量</b>（F-58：本项没有对应的业务错误码，
 * 新增一个要改契约 §6.3 与 00-通用约定 §9.3，为一个部署期状态开永久号位不划算）。
 *
 * <p><b>唯一的例外是 {@link #getPlayInfo}</b>：它只被事件消费调用，而没有队列就没有事件，
 * 走不到这里；真走到了同样响亮失败。
 */
@Component
@ConditionalOnExpression("'${edumatrix.vod.region:}'.trim() == ''"
        + " or '${edumatrix.vod.template-group-id:}'.trim() == ''")
public class DisabledVodMediaClient implements VodMediaClient {

    private static final Logger log = LoggerFactory.getLogger(DisabledVodMediaClient.class);

    static final String NOT_CONFIGURED =
            "点播服务未配置（ALIYUN_VOD_REGION / ALIYUN_VOD_TEMPLATE_GROUP_ID 为空）——"
                    + "上传凭证与重新发起转码接口不可用。这是部署级配置缺失，不是业务错误";

    public DisabledVodMediaClient() {
        log.warn("点播 = 未配置（ALIYUN_VOD_REGION / ALIYUN_VOD_TEMPLATE_GROUP_ID 为空）—— "
                + "上传凭证（03-03 §7.1）与重新发起转码（§7.5）两个接口将失败；"
                + "媒资列表、删除、禁用/启用不受影响。转码模板组仍在需方侧配置中");
    }

    @Override
    public VodUploadCredential createUploadVideo(String title, String fileName, long fileSize) {
        throw new IllegalStateException(NOT_CONFIGURED);
    }

    @Override
    public VodUploadCredential refreshUploadVideo(String cloudVideoId) {
        throw new IllegalStateException(NOT_CONFIGURED);
    }

    @Override
    public VodPlayInfo getPlayInfo(String cloudVideoId) {
        // 没有队列就没有事件，正常走不到这里；真走到了也要响亮失败而不是返回空集——
        // 返回空集会被挑流判成「挑不到」，把一次配置缺失伪装成一次转码失败（置 status=3）
        throw new IllegalStateException(NOT_CONFIGURED);
    }

    @Override
    public void submitTranscodeJobs(String cloudVideoId) {
        throw new IllegalStateException(NOT_CONFIGURED);
    }

    @Override
    public boolean enabled() {
        return false;
    }

    /** 空实现下没有任何流可挑 —— 保留这个常量位以免调用方对 {@code null} 与空集分叉处理。 */
    static VodPlayInfo empty() {
        return new VodPlayInfo(List.of(), null);
    }

    @Override
    public String getVideoPlayAuth(String cloudVideoId) {
        throw new IllegalStateException(NOT_CONFIGURED);
    }
}
