package com.edumatrix.common.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Excel 写出防公式注入 —— {@code 00-通用约定} §7.4 与
 * {@code 04-实施计划.md} 模块 05「做完什么算做完」的验收。
 *
 * <p>验收标准逐字：「构造姓名为 {@code =1+1} 的数据导出后，
 * <b>单元格内容以单引号开头</b>、Excel 打开不执行公式」。
 *
 * <h2>攻击侧 + 保留侧，缺一不可</h2>
 * <p>只测「转义生效」的话，把 {@code SafeExcelWriter} 换成
 * 「<b>所有单元格前面都加单引号</b>」也会全绿 —— 而那会让每一份成绩报表里的
 * 分数都变成文本，没法求和、没法排序，即导出报表的主要用途整体失效。
 * {@link #normalContentSurvivesUntouched} 与 {@link #numericCellsAreNotEscaped}
 * 就是那条保留侧。
 */
class SafeExcelWriterTest {

    /** 写一张表并用 POI 重新打开 —— 断言的是"落盘之后长什么样"，不是内存里的中间态。 */
    private static Sheet writeAndReopen(List<Object[]> rows) throws IOException {
        byte[] bytes;
        try (SafeExcelWriter writer = new SafeExcelWriter("Sheet1");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Object[] row : rows) {
                writer.writeRow(row);
            }
            writer.writeTo(out);
            bytes = out.toByteArray();
        }
        return new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
    }

    // ====================================================================
    // 攻击侧
    // ====================================================================

    @Test
    @DisplayName("六个危险前导字符全部前置单引号（§7.4 逐字：= + - @ Tab CR）")
    void allSixDangerousPrefixesAreEscaped() {
        assertThat(SafeExcelWriter.escape("=1+1")).isEqualTo("'=1+1");
        assertThat(SafeExcelWriter.escape("+1")).isEqualTo("'+1");
        assertThat(SafeExcelWriter.escape("-1")).isEqualTo("'-1");
        assertThat(SafeExcelWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SafeExcelWriter.escape("\tfoo")).isEqualTo("'\tfoo");
        assertThat(SafeExcelWriter.escape("\rfoo")).isEqualTo("'\rfoo");
    }

    /**
     * 验收标准那一条，且断言的是<b>"它不是公式"</b>而不是"它看起来像文本"。
     *
     * <p>{@code getCellFormula()} 在非公式单元格上抛 {@code IllegalStateException} ——
     * 这比 {@code getCellType() == STRING} 更硬：后者在某些构造下也可能为 STRING
     * 而 Excel 仍按公式解析。
     */
    @Test
    @DisplayName("验收：姓名 =1+1 导出后以单引号开头，且【不是公式】（getCellFormula 抛异常）")
    void formulaLikeNameIsWrittenAsTextNotFormula() throws IOException {
        Sheet sheet = writeAndReopen(List.<Object[]>of(new Object[]{"=1+1"}));
        Cell cell = sheet.getRow(0).getCell(0);

        assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell.getStringCellValue()).startsWith("'");
        assertThat(cell.getStringCellValue()).isEqualTo("'=1+1");
        assertThatThrownBy(cell::getCellFormula)
                .as("能取出公式就说明它真的是公式 —— 那正是要防的事")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("§7.4 原文那条攻击链：姓名列填 =cmd|... 写进失败报告后不执行")
    void theExactAttackFromTheSpecIsNeutralised() throws IOException {
        String payload = "=cmd|'/c calc'!A1";
        Sheet sheet = writeAndReopen(List.<Object[]>of(new Object[]{"李小明", payload}));

        assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("'" + payload);
        assertThatThrownBy(() -> sheet.getRow(0).getCell(1).getCellFormula())
                .isInstanceOf(IllegalStateException.class);
    }

    // ====================================================================
    // 保留侧 —— 没有这一组，「所有单元格一律加单引号」也会全绿
    // ====================================================================

    @Test
    @DisplayName("【保留侧】正常内容一字不改：姓名 / 学号 / 节点名 / 手机号")
    void normalContentSurvivesUntouched() throws IOException {
        Sheet sheet = writeAndReopen(List.<Object[]>of(
                new Object[]{"李小明", "S20260001", "高一(3)班", "13800000001"}));
        Row row = sheet.getRow(0);

        assertThat(row.getCell(0).getStringCellValue()).isEqualTo("李小明");
        assertThat(row.getCell(1).getStringCellValue()).isEqualTo("S20260001");
        assertThat(row.getCell(2).getStringCellValue()).isEqualTo("高一(3)班");
        assertThat(row.getCell(3).getStringCellValue()).isEqualTo("13800000001");
    }

    @Test
    @DisplayName("【保留侧】数值单元格不转义，仍是 NUMERIC —— 否则成绩报表没法求和")
    void numericCellsAreNotEscaped() throws IOException {
        Sheet sheet = writeAndReopen(List.<Object[]>of(new Object[]{"李小明", 95.5, -3, 0}));
        Row row = sheet.getRow(0);

        assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(95.5);
        // -3 是数值，不该因为"以 - 开头"被当字符串加引号
        assertThat(row.getCell(2).getCellType())
                .as("负数被转义成文本的话，03-05 §5.1/§5.2 的数值列全部失效")
                .isEqualTo(CellType.NUMERIC);
        assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(-3);
        assertThat(row.getCell(3).getNumericCellValue()).isZero();
    }

    @Test
    @DisplayName("【保留侧】escape 对普通串返回原对象内容，不做任何包装")
    void escapeIsIdentityForSafeValues() {
        assertThat(SafeExcelWriter.escape("李小明")).isEqualTo("李小明");
        assertThat(SafeExcelWriter.escape("A1")).isEqualTo("A1");
        assertThat(SafeExcelWriter.escape("")).isEmpty();
        assertThat(SafeExcelWriter.escape(null)).isNull();
        // ( 不在六个危险字符里 —— 加进去会让「(备注)」开头的评语全带引号
        assertThat(SafeExcelWriter.escape("(备注) 表现良好")).isEqualTo("(备注) 表现良好");
    }

    @Test
    @DisplayName("表头与多行一起写，行列位置不错乱")
    void headerAndRowsKeepTheirPositions() throws IOException {
        byte[] bytes;
        try (SafeExcelWriter writer = new SafeExcelWriter("学员进度");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writer.writeHeader(List.of("学号", "姓名", "累计观看时长"));
            writer.writeRow("S20260001", "=1+1", 3600);
            writer.writeRow("S20260002", "王小红", 1800);
            writer.writeTo(out);
            bytes = out.toByteArray();
        }

        Sheet sheet = new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
        assertThat(sheet.getSheetName()).isEqualTo("学员进度");
        assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("姓名");
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("'=1+1");
        assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(3600);
        assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("王小红");
    }
}
