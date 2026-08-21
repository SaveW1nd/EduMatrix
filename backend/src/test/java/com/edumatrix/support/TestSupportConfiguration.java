package com.edumatrix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.edumatrix.common.media.VodEvent;
import com.edumatrix.common.media.VodEventPayloadParser;
import com.edumatrix.common.media.VodEventSource;
import com.edumatrix.common.media.VodMediaClient;
import com.edumatrix.common.media.VodNotReadyException;
import com.edumatrix.common.media.VodPlayInfo;
import com.edumatrix.common.media.VodPlayStream;
import com.edumatrix.common.tenant.TenantHelper;

/**
 * 测试上下文的补充装配。
 *
 * <p>{@link TestCurrentContextProvider} 标 {@link Primary}，压过
 * {@code TenantConfig} 里那个 {@code @ConditionalOnMissingBean} 的默认实现 ——
 * 这也顺带验证了「模块 02 只要注册一个自己的实现 Bean，默认实现就自动让位」这条约定成立。
 *
 * <p>{@link FakeVodMediaClient} 同理压过 {@code common/media/DisabledVodMediaClient}
 * —— 测试环境没有 {@code ALIYUN_VOD_*}，真 {@code VodClient} 不装配、Disabled 顶上，
 * 而 Disabled 被调到时是<b>响亮失败</b>（那是它的设计）。上传凭证与重新发起转码
 * 这两个接口的判定顺序要测，就得有一个不联网的替身。
 * <b>放这里而不是新起一个 {@code @TestConfiguration}</b>：{@code TenantHelper} 的 provider
 * 是<b>静态字段</b>，多一个 Spring 测试上下文就会把它指向别人的 provider
 * （模块 06 / 07 / 08 的底座注释里逐字写过同一条）。
 */
@TestConfiguration
public class TestSupportConfiguration {

    @Bean
    @Primary
    public TestCurrentContextProvider testCurrentContextProvider() {
        TestCurrentContextProvider provider = new TestCurrentContextProvider();
        // 静态门面同步指向它，否则 TenantHelper 仍读默认实现
        TenantHelper.setProvider(provider);
        return provider;
    }

    /**
     * 不联网的点播替身。<b>只记录调用、返回固定凭证，不做任何判定</b> ——
     * 判定在 {@code VodVideoService}，替身里出现 {@code if} 就说明逻辑被复制到了第二处。
     */
    @Bean
    @Primary
    public FakeVodMediaClient fakeVodMediaClient() {
        return new FakeVodMediaClient();
    }

    /** 供用例断言「调没调云、调了几次」。 */
    public static class FakeVodMediaClient implements VodMediaClient {

        public final java.util.List<String> calls = new java.util.ArrayList<>();
        /** 置 true 时下一次调用抛异常 —— 用来验「云调失败则整体回滚，没有中间态」。 */
        public boolean failNext;
        /** {@code getPlayInfo} 要返回的流；默认空（= 挑不到）。 */
        public java.util.List<VodPlayStream> streams = java.util.List.of();
        /** 置 true 时 {@code getPlayInfo} 抛「云端未就绪」——与「挑不到流」是两条路。 */
        public boolean notReady;

        /** 两个模板组：IT 里断言「上传时选加密走了哪一个」。 */
        public String defaultGroup = "TPL-PLAIN";
        public String encryptedGroup = "TPL-ENCRYPTED";

        @Override
        public String defaultTemplateGroupId() {
            return defaultGroup;
        }

        @Override
        public String encryptedTemplateGroupId() {
            return encryptedGroup;
        }

        public void reset() {
            calls.clear();
            failNext = false;
            notReady = false;
            streams = java.util.List.of();
            playAuth = "FAKE-PLAY-AUTH-0123456789";
            playAuthFails = false;
            defaultGroup = "TPL-PLAIN";
            encryptedGroup = "TPL-ENCRYPTED";
        }

        /** 一路正常的加密 HLS（GetPlayInfo 形态：Encrypt 是 Long=1、Duration 是 String）。 */
        public void oneEncryptedHls(String duration) {
            streams = java.util.List.of(new VodPlayStream("m3u8", 1L, "AliyunVoDEncryption",
                    null, "https://vod.example.cn/x.m3u8", duration, 9486668L, "SD"));
        }

        /**
         * 一路<b>不加密</b>的 HLS —— F-114 第二半：需方把模板组配成不加密输出时的真实形态。
         *
         * <p>{@code Encrypt} 不是 1、{@code EncryptType} 为空，其余与加密那一路同形。
         * <b>这条路此前全库零覆盖</b>，正是「传一个不加密视频就被标成转码失败」能一直绿着的原因。
         */
        public void onePlainHls(String duration) {
            streams = java.util.List.of(new VodPlayStream("m3u8", 0L, null,
                    null, "https://vod.example.cn/plain.m3u8", duration, 8123456L, "SD"));
        }

        /** 模块 12：下发的播放凭证。IT 里断言它原样出现在响应 playAuth 字段上。 */
        public String playAuth = "FAKE-PLAY-AUTH-0123456789";

        /** 置 true 模拟阿里云侧失败（缺 RAM 权限、网络等），用于验证不把内部异常泄露给学生端。 */
        public boolean playAuthFails;

        @Override
        public String getVideoPlayAuth(String cloudVideoId) {
            calls.add("getVideoPlayAuth:" + cloudVideoId);
            if (playAuthFails) {
                throw new IllegalStateException("取播放凭证失败（GetVideoPlayAuth） videoId=" + cloudVideoId);
            }
            return playAuth;
        }

        public long playAuthCalls() {
            return calls.stream().filter(c -> c.startsWith("getVideoPlayAuth")).count();
        }

        public long playInfoCalls() {
            return calls.stream().filter(c -> c.startsWith("getPlayInfo")).count();
        }

        private void record(String call) {
            calls.add(call);
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("探针：云端调用失败");
            }
        }

        @Override
        public com.edumatrix.common.media.VodUploadCredential createUploadVideo(
                String title, String fileName, long fileSize, String templateGroupId) {
            // 把模板组记进调用轨迹 —— IT 靠它断言「选了加密就真的走了加密那个组」
            record("createUploadVideo:" + title + ":tpl=" + templateGroupId);
            return new com.edumatrix.common.media.VodUploadCredential(
                    "cloud-" + Math.abs((title + fileName).hashCode()), "auth-token", "upload-address");
        }

        @Override
        public com.edumatrix.common.media.VodUploadCredential refreshUploadVideo(String cloudVideoId) {
            record("refreshUploadVideo:" + cloudVideoId);
            return new com.edumatrix.common.media.VodUploadCredential(
                    cloudVideoId, "auth-token-refreshed", "upload-address");
        }

        @Override
        public VodPlayInfo getPlayInfo(String cloudVideoId) {
            record("getPlayInfo:" + cloudVideoId);
            if (notReady) {
                throw new VodNotReadyException("探针：Currently Video Status is Transcoding "
                        + "and AuditStatus is Init");
            }
            // CoverURL 刻意给一个【真实形态】的值：http:// + Expires 签名。
            // 消费侧不写它，本条就是那个口径的反面证据
            return new VodPlayInfo(streams,
                    "http://outin-x/snapshots/y.jpg?Expires=1787169594&Signature=abc");
        }

        @Override
        public void submitTranscodeJobs(String cloudVideoId, String templateGroupId) {
            record("submitTranscodeJobs:" + cloudVideoId + ":tpl=" + templateGroupId);
        }
    }

    /**
     * 不联网的队列替身。压过 {@code common/media/DisabledVodEventSource}。
     *
     * <p><b>喂进去的是原始 JSON 文本</b>，走的仍是 {@code VodEventPayloadParser} ——
     * 而不是另一份手搓的解析。否则测的就不是生产那条链路了。
     */
    @Bean
    @Primary
    public FakeVodEventSource fakeVodEventSource() {
        return new FakeVodEventSource();
    }

    /** 供用例断言「哪条消息被删了、哪条还留着」。 */
    public static class FakeVodEventSource implements VodEventSource {

        private final java.util.List<VodEvent> pending = new java.util.ArrayList<>();
        public final java.util.List<String> deleted = new java.util.ArrayList<>();

        public void reset() {
            pending.clear();
            deleted.clear();
        }

        /** 投一条消息（原始 JSON 文本，与生产同一段解析）。 */
        public void offer(String receiptHandle, String rawJson) {
            pending.add(VodEventPayloadParser.parse(receiptHandle, rawJson));
        }

        public boolean deletedContains(String receiptHandle) {
            return deleted.contains(receiptHandle);
        }

        @Override
        public java.util.List<VodEvent> receive(int max) {
            java.util.List<VodEvent> batch = java.util.List.copyOf(pending);
            pending.clear();
            return batch;
        }

        @Override
        public void delete(String receiptHandle) {
            deleted.add(receiptHandle);
        }

        @Override
        public long queueDepth() {
            return pending.size();
        }
    }
}
