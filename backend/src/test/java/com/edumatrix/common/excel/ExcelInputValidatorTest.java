package com.edumatrix.common.excel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Excel 导入字符白名单 —— {@code 00-通用约定} §7.4 的读入侧。
 *
 * <h2>保留侧在这里比攻击侧更要紧</h2>
 * <p>读入侧<b>误拒是"响"的、放过是"哑"的</b>：误拒一个真实姓名，
 * 用户当场投诉；放过一个脏值，没人会知道，直到某份导出报表被打开。
 * 所以本类的保留侧用例（{@link #realWorldNamesAndNodeNamesArePreserved}、
 * {@link #rareHanCharactersInNamesAreAccepted}）不是补充，是主体 ——
 * 把校验写成「一律拒绝」时，只有它们会红。
 */
class ExcelInputValidatorTest {

    // ====================================================================
    // 攻击侧
    // ====================================================================

    @Test
    @DisplayName("四个危险前导字符一律拒绝（§7.4 读入侧逐字：= + - @）")
    void dangerousPrefixesAreRejected() {
        assertThat(ExcelInputValidator.isValid("=cmd|'/c calc'!A1")).isFalse();
        assertThat(ExcelInputValidator.isValid("+1")).isFalse();
        assertThat(ExcelInputValidator.isValid("-1")).isFalse();
        assertThat(ExcelInputValidator.isValid("@SUM(A1)")).isFalse();
    }

    @Test
    @DisplayName("拒绝原因可直接进失败报告（模块 17 要把它写进 fail_report）")
    void rejectionReasonIsHumanReadable() {
        assertThat(ExcelInputValidator.validate("姓名", "=1+1"))
                .contains("姓名不能以 = 开头");
        assertThat(ExcelInputValidator.validate("姓名", "李<script>"))
                .get().asString().startsWith("姓名含不允许的字符");
    }

    @Test
    @DisplayName("XSS / 注入相关字符不在白名单（姓名会被渲染进管理端页面与跑马灯水印）")
    void xssRelatedCharactersAreRejected() {
        for (String value : new String[]{"李<b>", "王\"三", "张'四", "赵&五", "钱/六", "孙\\七", "周;八", "吴%九"}) {
            assertThat(ExcelInputValidator.isValid(value)).as(value).isFalse();
        }
    }

    // ====================================================================
    // 保留侧 —— 主体
    // ====================================================================

    @Test
    @DisplayName("【保留侧】真实姓名与节点名一律通过（写成「一律拒绝」时只有这一组会红）")
    void realWorldNamesAndNodeNamesArePreserved() {
        String[] valid = {
                "李小明",                 // 最常见
                "王·阿依古丽",            // 少数民族姓名，间隔号
                "Mary Smith",             // 外籍学员，含空格（F-35：空格是按「宁宽勿严」补的）
                "高一(3)班",              // 半角括号
                "高一（3）班",            // 全角括号
                "2026级-A班",             // 连字符
                "S20260001_01",           // 下划线
                "张三.李四",              // 点
                "初二年级 3 班",           // 中文 + 空格 + 数字
        };
        for (String value : valid) {
            assertThat(ExcelInputValidator.isValid(value))
                    .as("误拒真实数据是「响」的错误，用户当场投诉：%s", value)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("【保留侧】生僻姓氏（CJK 扩展区）必须通过 —— 用 \\u4E00-\\u9FFF 判会漏")
    void rareHanCharactersInNamesAreAccepted() {
        // 扩展 A 区（U+3400–U+4DBF）
        assertThat(ExcelInputValidator.isValid("㐁明")).isTrue();
        // 扩展 B 区（U+20000 起，需要代理对）—— 区间判定法在这里必然漏
        String extB = new String(Character.toChars(0x20000));
        assertThat(ExcelInputValidator.isValid(extB + "小明"))
                .as("一个用了三十年的姓名被判成非法字符，是很难向用户解释的")
                .isTrue();
    }

    @Test
    @DisplayName("【保留侧】空值与 null 不拦（空单元格不是攻击面，拦了会让整行白白失败）")
    void blankValuesArePassedThrough() {
        assertThat(ExcelInputValidator.isValid(null)).isTrue();
        assertThat(ExcelInputValidator.isValid("")).isTrue();
    }

    @Test
    @DisplayName("危险字符只在【开头】才拒：中间出现的减号是合法的（2026级-A班）")
    void dangerousCharactersAreOnlyRejectedAsPrefix() {
        assertThat(ExcelInputValidator.isValid("2026级-A班")).isTrue();
        assertThat(ExcelInputValidator.isValid("-2026级A班")).isFalse();
    }

    /**
     * 读入侧四个、写出侧六个 —— <b>这个差异是分册原文</b>，不是遗漏。
     *
     * <p>把它钉成一条用例，是因为「为什么两边不一样」一定会被下一个人问，
     * 而"顺手统一一下"会让读入侧多拒两个不可见字符（用户手滑的前导 Tab/CR），
     * 或让写出侧少防两个。
     */
    @Test
    @DisplayName("读入侧四个 vs 写出侧六个：Tab / CR 只在写出侧拦（§7.4 两行原文不同）")
    void inputAndOutputPrefixSetsDifferOnPurpose() {
        // 写出侧：Tab / CR 要转义
        assertThat(SafeExcelWriter.escape("\tfoo")).isEqualTo("'\tfoo");
        assertThat(SafeExcelWriter.escape("\rfoo")).isEqualTo("'\rfoo");
        // 读入侧：Tab / CR 不在四个危险前缀里 —— 但它们也不在字符白名单里，
        // 所以仍然会被拒，只是理由不同（"含不允许的字符"而非"不能以 X 开头"）
        assertThat(ExcelInputValidator.validate("姓名", "\tfoo"))
                .get().asString().contains("含不允许的字符");
    }
}
