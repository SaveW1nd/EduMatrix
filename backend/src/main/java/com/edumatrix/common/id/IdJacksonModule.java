package com.edumatrix.common.id;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * bigint ID 的序列化模块：<b>包装类型 {@code Long} → JSON 字符串</b>（00-通用约定 §5）。
 *
 * <h2>为什么必须做</h2>
 * <p>雪花 ID 是 19 位，而 JavaScript {@code Number} 的安全整数上限是 2^53-1（16 位）。
 * 直接传数字会<b>静默丢精度</b> —— 表现是列表里点进去 404、授权提交后对不上人，
 * 而不是任何一处报错。所以序列化<b>全局生效，不逐字段加注解</b>：
 * 逐字段的做法早晚会漏一个，而漏掉的那个不会有人发现。
 *
 * <h2>为什么只转包装 {@code Long}、不转基本类型 {@code long}</h2>
 * <p>因为两份文档对同一个词有不同要求，而这个区分让两边都成立：
 * <ul>
 *   <li>00-通用约定 §5：<b>所有 bigint ID</b> 一律序列化为字符串；
 *   <li>00-通用约定 §4.2：分页响应的 {@code total} 类型是 <b>long（数字）</b>；
 *       §6：时长<b>一律以秒为整数传输</b>。
 * </ul>
 * 一刀切地把所有 {@code Long} 转成字符串，{@code total} 就会变成 {@code "138"}，
 * 与 §4.2 对不上；一刀切地不转，ID 就丢精度。
 *
 * <p><b>因此本项目的类型约定是</b>（写在这里，因为它是上面那条规则的使用说明）：
 * <table border="1">
 *   <caption>DTO / VO 的数值类型选择</caption>
 *   <tr><th>字段语义</th><th>声明类型</th><th>JSON 形态</th></tr>
 *   <tr><td>ID（主键、外键、ID 数组元素）</td><td>{@code Long} / {@code List<Long>}</td><td>字符串</td></tr>
 *   <tr><td>计数、总数、时长秒数、字节数</td><td>{@code long} / {@code int} / {@code Integer}</td><td>数字</td></tr>
 * </table>
 * <p>ID 天然可空（未分配导师、无关联视频），用包装类型本来就对；计数用基本类型或
 * {@code Integer}，两个诉求正好不冲突。
 *
 * <p><b>请求侧同样一律以字符串传递</b>（§5）：Jackson 反序列化 {@code "195..."} → {@code Long}
 * 是默认支持的，无需额外配置。
 */
public class IdJacksonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public IdJacksonModule() {
        super("EduMatrixIdModule");
        // 只注册包装类型。基本类型 long 走 Jackson 默认的数字序列化，
        // 从而让 PageResult.total（long）保持为数字（00-通用约定 §4.2）
        addSerializer(Long.class, ToStringSerializer.instance);
    }
}
