package com.edumatrix.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.edumatrix.common.media.VodMediaClient;
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

        public void reset() {
            calls.clear();
            failNext = false;
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
                String title, String fileName, long fileSize) {
            record("createUploadVideo:" + title);
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
        public com.edumatrix.common.media.VodPlayInfo getPlayInfo(String cloudVideoId) {
            record("getPlayInfo:" + cloudVideoId);
            return new com.edumatrix.common.media.VodPlayInfo(java.util.List.of(), null);
        }

        @Override
        public void submitTranscodeJobs(String cloudVideoId) {
            record("submitTranscodeJobs:" + cloudVideoId);
        }
    }
}
