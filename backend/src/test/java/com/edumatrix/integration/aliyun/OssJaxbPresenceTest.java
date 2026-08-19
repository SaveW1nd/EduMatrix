package com.edumatrix.integration.aliyun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.annotation.XmlRootElement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JAXB 在运行期真的可用 —— 守的是 {@code pom.xml} 里那三个 JAXB 坐标。
 *
 * <h2>为什么这条测试值得存在</h2>
 * <p>{@code aliyun-sdk-oss} 用 JAXB 解析 OSS 的 XML 响应，而 JAXB 自 Java 11 起已从 JDK 移出。
 * 缺这三个坐标时：
 * <ul>
 *   <li><b>编译期毫无异常</b>（业务代码不直接引用 JAXB）；</li>
 *   <li>dev / test 走 {@code LocalObjectStorage}，<b>一条 OSS 调用都不会发生</b>；</li>
 *   <li>于是第一次 {@code NoClassDefFoundError} 出现在<b>生产的第一次上传</b>。</li>
 * </ul>
 * <p>这正是本项目已出现四次的「以为存在、实际从未生效」那一族的镜像 ——
 * 区别只是这次失效的是依赖不是保障。本测试把它拉回到 {@code mvn verify}。
 *
 * <p><b>为什么不只是 {@code Class.forName}</b>：光有 {@code javax.xml.bind} 的<b>接口</b>
 * （jaxb-api）而缺 {@code jaxb-runtime} 时，{@code Class.forName} 照样成功，
 * 只有真正 {@code newInstance} 才会抛 "Implementation of JAXB-API has not been found"。
 * 所以这里真的建一次上下文。
 */
class OssJaxbPresenceTest {

    @XmlRootElement(name = "Probe")
    static class Probe {
        public String name;
    }

    @Test
    @DisplayName("JAXB API 与实现都在 classpath 上（缺 jaxb-runtime 时本用例会红）")
    void jaxbContextCanBeCreated() {
        assertThatCode(() -> {
            JAXBContext context = JAXBContext.newInstance(Probe.class);
            assertThat(context).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("javax.activation 在 classpath 上（OSS SDK 的 MIME 处理依赖它）")
    void activationIsPresent() {
        assertThatCode(() -> Class.forName("javax.activation.DataHandler"))
                .doesNotThrowAnyException();
    }
}
