package com.edumatrix.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.edumatrix.support.IntegrationTest;

/**
 * 模块 13 的三列迁移（{@code V202608230000} / {@code V202608230100}）。
 *
 * <p><b>为什么这三条不是「测试框架自己」</b>：三列各自的 DDL 注释里都写了一句
 * 「这样定不行，会……」的警告，而<b>写下警告不等于守住了它</b>（F-115 逐字）。
 * 三条断言分别钉住那三句警告，改错任何一处都会红。
 */
@IntegrationTest
class VodProgressSchemaIT {

    private static final String SCHEMA = "edumatrix";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("last_position 与 max_position 类型完全相同（精度不同会让位置比较永远为假且不报错）")
    void lastPositionHasSameTypeAsMaxPosition() {
        Map<String, Object> last = column("vod_watch_progress", "last_position");
        Map<String, Object> max = column("vod_watch_progress", "max_position");

        assertThat(last)
                .as("迁移注释逐字：两列都表示「视频里的某个位置」，"
                        + "精度不同会让「上次离开的位置是不是就是最远位置」这类判断【永远为假】—— "
                        + "而这不报错，只是静默失灵。所以类型必须逐项相同，不只是「都是数字」")
                .isNotEmpty();
        assertThat(last.get("COLUMN_TYPE")).isEqualTo(max.get("COLUMN_TYPE"));
        assertThat(last.get("IS_NULLABLE")).isEqualTo(max.get("IS_NULLABLE"));
    }

    @Test
    @DisplayName("play_rate 保留两位小数（一位会把 0.75 / 1.25 静默舍成 0.8 / 1.3）")
    void playRateKeepsTwoDecimals() {
        Map<String, Object> col = column("vod_heartbeat_log", "play_rate");

        assertThat(col)
                .as("F-116 / 03-03 规则 4：playRate 仅落日志供行为分析。"
                        + "播放器常用倍速含 0.75 与 1.25 —— DECIMAL(x,1) 会把它们【静默舍掉一位】，"
                        + "而本列的用途正是「回放当时到底发生了什么」。两位小数是下限，不是余量")
                .isNotEmpty();
        assertThat(((Number) col.get("NUMERIC_SCALE")).intValue()).isEqualTo(2);
        // 总长 4 位（±99.99）而不是刚好够用的 3 位：规则 5 判 playRate 越界时【明细仍记真值】，
        // 存不下会让 INSERT 直接失败 —— 那是一条非法心跳把整个请求打挂
        assertThat(((Number) col.get("NUMERIC_PRECISION")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat(col.get("IS_NULLABLE")).isEqualTo("YES");
    }

    @Test
    @DisplayName("trigger_type 存在且默认 0（0 = 缺失或非法，不是默认成 tick）")
    void triggerTypeExistsWithZeroDefault() {
        Map<String, Object> col = column("vod_heartbeat_log", "trigger_type");

        assertThat(col)
                .as("F-116「取枚举不取布尔」的【唯一理由】是「它会落进 vod_heartbeat_log，"
                        + "顺带说明每条心跳是为什么来的」—— 而这张表原来没有这一列。"
                        + "缺列不报错：接口照收 trigger、照它分流，只是事后一条都查不到，"
                        + "与「枚举退化成布尔」在库里完全不可区分")
                .isNotEmpty();
        assertThat(col.get("COLUMN_DEFAULT"))
                .as("默认 0 = 缺失或取值非法（按规则 5 丢弃）。"
                        + "【不是默认成 tick】—— 那会让事件心跳重新被卡在 8 秒间隔闸上，"
                        + "等于 F-116 这条定案没做，而且不报错")
                .isEqualTo("0");
    }

    private Map<String, Object> column(String table, String column) {
        var rows = jdbcTemplate.queryForList(
                "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, NUMERIC_PRECISION, NUMERIC_SCALE "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                SCHEMA, table, column);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
