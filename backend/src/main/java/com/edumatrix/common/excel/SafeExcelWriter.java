package com.edumatrix.common.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * {@code .xlsx} 写出的<b>唯一</b>入口 —— {@code 00-通用约定} §7.4「Excel 写出防公式注入」的落地。
 *
 * <p>§7.4 逐字：「生成任何 {@code .xlsx}（失败报告、成绩报表、进度报表、账号密码表）时，
 * 字符串单元格若以 {@code =} {@code +} {@code -} {@code @} {@code Tab} {@code CR} 开头，
 * <b>必须前置单引号或显式设为文本格式</b>。」
 *
 * <h2>攻击链是两段的，所以防线也要两处</h2>
 * <p>§7.4 原文：「不做此处理时：导入的"姓名"列填入 {@code =cmd 加管道符的公式}，
 * 会被原样写回失败报告，管理员打开并启用编辑即在其终端执行；
 * <b>且该值一旦入库，之后每一份含姓名的导出报表都会携带它</b>，
 * 受害面从单人扩散到全部导出者。」
 * <ul>
 *   <li><b>写出侧</b>（本类）：任何 {@code .xlsx} 的字符串单元格都转义 —— 这是<b>兜底</b>，
 *       哪怕库里已经躺着脏数据也不会引爆；</li>
 *   <li><b>读入侧</b>（{@link ExcelInputValidator}）：拒绝这类值入库 —— 这是<b>止血</b>。</li>
 * </ul>
 * <p>两处缺一不可，且<b>写出侧是主力</b>：读入侧只能拦住"以后"，拦不住已经入库的。
 *
 * <h2>为什么是"前置单引号"而不是 {@code quotePrefixed} 样式</h2>
 * <p>§7.4 给了两个选项。选前者是因为 {@code 04-实施计划.md} 模块 05 的「做完什么算做完」
 * 逐字要求「构造姓名为 {@code =1+1} 的数据导出后，<b>单元格内容以单引号开头</b>、
 * Excel 打开不执行公式」—— 验收标准点名了这个形态。
 * 代价是管理员在 Excel 里会看到一个可见的 {@code '}，这是有意接受的：
 * <b>一个看得见的引号，好过一个看不见的公式</b>。
 *
 * <h2>只转义字符串单元格</h2>
 * <p>数值、日期单元格原样写。给分数加单引号会让它变成文本，
 * 于是报表里的成绩<b>没法求和、没法排序</b> —— 而那是导出报表的主要用途
 * （03-05 §5.1/§5.2 的列结构里有大量数值列）。
 *
 * <h2>用 SXSSF 而不是 XSSF</h2>
 * <p>模块 17 规则 13：导出单任务上限 <b>5 万行</b>。XSSF 把整个工作簿放内存，
 * 5 万行 × 十几列在 {@code -Xmx1g} 的单实例上是真的会 OOM；
 * SXSSF 只保留滑动窗口内的行。<b>调用方必须 {@link #close()}</b>（它要删临时文件）。
 */
public final class SafeExcelWriter implements AutoCloseable {

    /**
     * 触发转义的六个前导字符（{@code 00-通用约定} §7.4 逐字：{@code =} {@code +} {@code -}
     * {@code @} {@code Tab} {@code CR}）。
     *
     * <p><b>不要"顺手"往里加或减</b>：这六个是分册穷举的。少一个就是一条可用的注入路径；
     * 多一个会把正常内容也加上引号（例如把 {@code (} 加进来，
     * 「(备注)」这种开头的评语就全带引号了）。
     */
    private static final char[] DANGEROUS_PREFIXES = {'=', '+', '-', '@', '\t', '\r'};

    /** SXSSF 滑动窗口行数。100 行足够，再大只是多占内存。 */
    private static final int ROW_ACCESS_WINDOW = 100;

    private final SXSSFWorkbook workbook;
    private final Sheet sheet;
    private int nextRowIndex;

    public SafeExcelWriter(String sheetName) {
        this.workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        this.sheet = workbook.createSheet(sheetName);
    }

    /**
     * 转义单个字符串值：命中六个前导字符之一即前置 {@code '}。
     *
     * <p><b>公开成 static 是为了可测</b>，也为了让"这条规则长什么样"只有一处答案。
     * {@code null} 原样返回（空单元格不是攻击面）。
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        for (char dangerous : DANGEROUS_PREFIXES) {
            if (first == dangerous) {
                return "'" + value;
            }
        }
        return value;
    }

    /**
     * 写一个<b>字符串</b>单元格。这是本类的核心原语 —— 任何往 {@code .xlsx} 写字符串的地方
     * 都必须经过它，而不是直接 {@code cell.setCellValue(String)}。
     */
    public static void setStringCell(Cell cell, String value) {
        cell.setCellValue(escape(value));
    }

    /** 表头。表头一般是硬编码的，但仍然走同一条转义 —— 少一个"这里不用"的例外。 */
    public void writeHeader(List<String> headers) {
        writeRow(headers.toArray());
    }

    /**
     * 写一行。
     *
     * <p>类型分派：{@code Number} → 数值单元格（<b>不转义</b>，见类注释）；
     * {@code null} → 空单元格；其余一律 {@code toString()} 后按字符串转义。
     * 把 {@code Number} 之外的一切都当字符串是有意的 ——
     * <b>拿不准的时候按字符串处理是安全的那一侧</b>。
     */
    public void writeRow(Object... values) {
        Row row = sheet.createRow(nextRowIndex++);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(i);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                setStringCell(cell, String.valueOf(value));
            }
        }
    }

    /** 落到输出流。写完仍需 {@link #close()}。 */
    public void writeTo(OutputStream out) throws IOException {
        workbook.write(out);
    }

    /** 释放 SXSSF 的临时文件。<b>不调用会在磁盘上留下垃圾</b>。 */
    @Override
    public void close() throws IOException {
        try {
            workbook.dispose();
        } finally {
            workbook.close();
        }
    }
}
