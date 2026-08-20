package com.edumatrix.common.media;

/**
 * 云端<b>还没准备好</b>，不是失败 —— {@code GetPlayInfo} 返回 {@code Forbidden.IllegalStatus}。
 *
 * <h2>为什么必须与「返回成功但挑不到流」分成两条路</h2>
 * <p>实测原文：
 * <pre>Currently Video Status is Transcoding and AuditStatus is Init</pre>
 * <b>事件到达与云端状态翻转之间有时间差</b>：转码完成事件已经进队列，而点播侧的媒资状态
 * 还没翻到可取播放信息。这一刻去调 {@code GetPlayInfo} 拿到的是这个错误，
 * <b>不是</b>「转码产物有问题」。
 *
 * <table border="1">
 *   <caption>两条路，处置相反</caption>
 *   <tr><th>情形</th><th>含义</th><th>处置</th></tr>
 *   <tr><td>{@code Forbidden.IllegalStatus}</td><td>云端未就绪，<b>等一等还有救</b></td>
 *       <td><b>不删消息</b>、不改状态，下一轮重来</td></tr>
 *   <tr><td>调用成功但挑不出 m3u8+加密</td><td>转码产物真的不对（模板组配错 / 未加密 MP4）</td>
 *       <td>置 {@code status=3} + 告警（契约 §1 第 3 条）</td></tr>
 * </table>
 *
 * <p><b>混在一起的后果是实的</b>：撞上一次时间差，就把一条<b>本来好好的视频永久标成转码失败</b>，
 * 而它需要人工去点「重新发起转码」才能救回来 —— 那是一次「看起来像转码失败的成功」。
 *
 * <p><b>顺带</b>：报错里那句 {@code AuditStatus is Init} 说明账号若开了内容审核，
 * 媒资可能卡在<b>审核态</b>，而 {@code vod_video.status} 的状态机（契约 §5）里<b>没有这一档</b>。
 * 本轮不实现，已登记待需方定（见模块 09 的文档提交）。
 */
public class VodNotReadyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 阿里云的错误码。见类注释。 */
    public static final String ILLEGAL_STATUS = "Forbidden.IllegalStatus";

    public VodNotReadyException(String message) {
        super(message);
    }
}
