package com.edumatrix.common.excel;

import java.util.Optional;

/**
 * Excel 导入的<b>字符白名单</b> —— {@code 00-通用约定} §7.4「Excel 导入字符白名单」的落地。
 *
 * <p>§7.4 逐字：「姓名、学号、节点名等文本字段<b>拒绝以 {@code = + - @} 开头</b>的值，
 * 并<b>限制为中英文、数字与有限标点</b>。」
 *
 * <h2>它是止血，{@link SafeExcelWriter} 才是主力</h2>
 * <p>§7.4 描述的攻击链有两段：脏值<b>进不进得来</b>（本类），脏值<b>出去时会不会引爆</b>
 * （{@code SafeExcelWriter}）。本类只能拦住"以后"，拦不住已经入库的 ——
 * 而 §7.4 原文强调的正是「该值一旦入库，之后<b>每一份</b>含姓名的导出报表都会携带它」。
 * <b>所以本类可以宽，写出侧不能松。</b>
 *
 * <h2>读入侧误拒是"响"的，放过是"哑"的 —— 所以宁宽勿严</h2>
 * <p>误拒一个真实姓名，用户当场就会投诉（「我这个名字为什么导不进去」）；
 * 而放过一个脏值，没有任何人会知道，直到某个管理员打开一份导出报表。
 * 两类错误的可发现性完全不对称，所以本类的白名单<b>刻意取宽</b>：
 * <ul>
 *   <li><b>汉字按 {@code Character.UnicodeScript.HAN} 判</b>，不用
 *       {@code \\u4E00-\\u9FFF} 这个区间。后者漏掉扩展 A/B 区，而生僻姓氏（如「𡵅」）
 *       就落在那里 —— 一个用了三十年的姓名被系统判成"非法字符"，是很难解释的；</li>
 *   <li><b>允许空格</b>：外籍学员姓名（{@code Mary Smith}）与部分少数民族姓名带空格。
 *       空格不属于需方定案的那七个标点，是本模块按「宁宽勿严」这条明确指示补的，
 *       已登记 F-35；</li>
 *   <li>四个危险前导字符（{@code = + - @}）<b>仍然一律拒绝</b> —— 那是硬线。</li>
 * </ul>
 *
 * <h2>标点白名单：需方定案的七个</h2>
 * <p>{@code · - _ ( ) （ ） .} —— §7.4 只写了「有限标点」没有穷举，
 * 这七个是模块 05 提出、需方拍板的（已登记 F-35，供后续复核）。
 * 覆盖的真实形态：「王·阿依古丽」「高一(3)班」「高一（3）班」「2026级-A班」
 * 「S20260001_01」「张三.李四」。
 */
public final class ExcelInputValidator {

    /**
     * 危险前导字符（§7.4 <b>读入侧</b>逐字：{@code = + - @}，<b>四个</b>）。
     *
     * <p><b>注意与写出侧的六个不同</b>：{@code SafeExcelWriter} 多了 {@code Tab} 与 {@code CR}。
     * 这不是遗漏 —— §7.4 的两行原文本来就一个写四个、一个写六个。
     * 写出侧要多防两个，是因为那两个字符在单元格里不可见，
     * 而读入侧的值来自用户填写的表格，前导 Tab/CR 通常是手滑而不是攻击，
     * 且它们会被 POI 读成前后空白。<b>照抄分册，不自行统一。</b>
     */
    private static final char[] DANGEROUS_PREFIXES = {'=', '+', '-', '@'};

    /** 需方定案的七个标点（D-6）。 */
    private static final String ALLOWED_PUNCTUATION = "·-_()（）.";

    private ExcelInputValidator() {
    }

    /** 校验结果：通过时 {@code empty}，否则带一句可直接进失败报告的中文原因。 */
    public static Optional<String> validate(String fieldLabel, String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        char first = value.charAt(0);
        for (char dangerous : DANGEROUS_PREFIXES) {
            if (first == dangerous) {
                return Optional.of(fieldLabel + "不能以 " + dangerous + " 开头");
            }
        }
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (!isAllowed(codePoint)) {
                return Optional.of(fieldLabel + "含不允许的字符：" + new String(Character.toChars(codePoint)));
            }
            i += Character.charCount(codePoint);
        }
        return Optional.empty();
    }

    /** 便捷判定。要把原因写进失败报告时用 {@link #validate}。 */
    public static boolean isValid(String value) {
        return validate("值", value).isEmpty();
    }

    /**
     * 允许的字符：汉字（全部 Han，含扩展区）、ASCII 字母数字、空格、七个标点。
     *
     * <p><b>刻意不允许</b>：{@code < > " ' & / \ ; % $}。它们不构成公式注入，
     * 但会流进富文本与 SQL 拼接的下游 —— PRD §7.3 第 7 条要求「富文本白名单过滤防 XSS」，
     * 而姓名会被渲染进管理端页面与跑马灯水印。
     */
    private static boolean isAllowed(int codePoint) {
        if (codePoint == ' ') {
            return true;
        }
        if (codePoint < 128) {
            return Character.isLetterOrDigit(codePoint)
                    || ALLOWED_PUNCTUATION.indexOf((char) codePoint) >= 0;
        }
        if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
            return true;
        }
        // 全角括号与间隔号在 BMP 的标点区，逐个列（不整段放行标点区）
        return ALLOWED_PUNCTUATION.indexOf((char) codePoint) >= 0;
    }
}
