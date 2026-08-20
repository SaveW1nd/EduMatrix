package com.edumatrix.common.media;

import java.util.List;

/**
 * {@code GetPlayInfo} 的返回（03-03 §7.2）。
 *
 * @param streams  全部播放流。挑流规则见 {@link VodPlayStream#isEncryptedHls()}
 * @param coverUrl {@code VideoBase.CoverURL}。<b>同样可能是 http</b>，
 *                 调用方要按 https 过一遍再决定写不写（见 {@code VodEventConsumeService}）
 */
public record VodPlayInfo(List<VodPlayStream> streams, String coverUrl) {
}
