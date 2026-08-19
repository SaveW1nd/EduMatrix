package com.edumatrix.common.question;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.response.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 答案 JSON 的<b>解析 + 校验 + 规范化</b>，六题型一处（契约 §5「答案 JSON 结构」表）。
 *
 * <h2>一、判断题必须是 JSON 布尔字面量 —— 拦在这一层，不在别处</h2>
 * <p>契约 §5 为此单独写了强调段：{@code true != "true"}，两端不一致会导致
 * <b>全部判断题一律判错</b>，而客观题不开放教师改分（PRD F3-6 规则 3），
 * <b>错了没有救济路径</b>。服务端收到字符串直接 400，<b>不做隐式转换</b>。
 *
 * <p><b>为什么必须在这一层，而不是 DTO 绑定 / 校验器 / Service</b>：
 * Jackson 2.x <b>默认</b>把 {@code "true"} 强制转成 {@code true}
 * （{@code CoercionInputShape.String → Boolean} 的默认动作是 TryConvert），
 * 而 {@code common/config/JacksonConfig} 只配了时间与 ID 字符串化、<b>没有任何
 * coercion 配置</b>。所以只要 DTO 里写一个 {@code Boolean answer} 字段，
 * {@code {"answer":"true"}} 就会<b>静默通过</b> ——
 * 等它到了 Service，那个值<b>已经是 true 了，看不出它曾经是字符串</b>。
 * 本类因此一律用 {@link JsonNode} 显式判类型（{@code isBoolean()}），
 * 而不是让 Jackson 绑定到 POJO。
 * {@code AnswerJsonTest#jacksonDefaultWouldSilentlyCoerceStringTrue} 把这个
 * 「危险默认」钉成了一条可执行的事实。
 *
 * <p><b>不改全局 ObjectMapper 的 CoercionConfig</b>：那会改掉所有模块所有字段的行为，
 * 爆炸半径远超本模块；而模块 15 的学生作答本来就走
 * {@link #readStudent}，收在这一层已经全覆盖。
 *
 * <h2>二、规范化放在【解析出口】，不放比较入口</h2>
 * <p>契约 §5：「比较前统一：多选排序去重、填空按 {@code index} 对齐并去首尾空白」。
 * 本类在<b>解析成功的那一刻</b>就完成规范化，于是：
 * <ul>
 *   <li>落库的标准答案永远是规范形（库里不存在 {@code ["C","A"]}）；
 *   <li>{@link AutoGrader} 的比较处只剩 {@code equals}。
 * </ul>
 * <p>反过来（放在比较入口）要求<b>每一个</b>比较点都记得先规范化，而模块 15
 * 有多个比较点（提交判卷、按新答案重判、错题本）——那是「两份同源实现」的标准长法，
 * 且漏一处的表现是<b>接口 200、字段齐全、判分错</b>。
 *
 * <h2>三、400 与 30006 的分界线</h2>
 * <table border="1">
 *   <tr><th>码</th><th>判据</th><th>例</th></tr>
 *   <tr><td><b>400</b></td><td>值的 <b>JSON 类型</b>与题型要求的类型不符</td>
 *       <td>判断题 {@code "true"}、多选给了字符串、单选给了数组</td></tr>
 *   <tr><td><b>30006</b></td><td>类型对、<b>结构/语义</b>不对</td>
 *       <td>{@code blankCount} 与 {@code blanks} 长度不一致、选项号不在
 *           {@code options} 里、材料题父题却带了答案</td></tr>
 * </table>
 * <p>这条分界线写在这里就是为了让它<b>只有一处</b>；别处不要另判一个码。
 */
public final class AnswerJson {

    /** PRD F3-1：选项 2~8 项。 */
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 8;

    /** PRD F3-1：填空 1~10 空。 */
    public static final int MIN_BLANKS = 1;
    public static final int MAX_BLANKS = 10;

    /** 03-04 §2.2：{@code content.stem} 中以 {@code ____} 标记空位。 */
    public static final String BLANK_MARKER = "____";

    private AnswerJson() {
    }

    // ================================================================ 标准答案

    /**
     * 解析并规范化标准答案。
     *
     * @throws BizException 400（类型不符）或 30006（结构不符）
     */
    public static CorrectAnswer readCorrect(QuestionType type, JsonNode raw) {
        requireType(type);
        if (type == QuestionType.MATERIAL) {
            // 契约 §5 / 03-04 §2.2：父题不存答案。带了答案是【结构】错，不是类型错
            if (present(raw)) {
                throw mismatch("材料题父题不存答案（契约 §5），请把答案放在各子题上");
            }
            return new CorrectAnswer.MaterialParent();
        }
        JsonNode node = requirePresentObject(raw, "correctAnswer");
        return switch (type) {
            case SINGLE -> new CorrectAnswer.SingleChoice(readOptionKey(node.get("answer"), "answer"));
            case MULTI -> new CorrectAnswer.MultiChoice(readOptionKeys(node.get("answer")));
            case TRUE_FALSE -> new CorrectAnswer.TrueFalse(readBoolean(node.get("answer")));
            case BLANK -> new CorrectAnswer.Blanks(readBlankKeys(node.get("blanks")));
            case SUBJECTIVE -> new CorrectAnswer.Reference(readText(node.get("text"), "text"));
            case MATERIAL -> throw new IllegalStateException("unreachable");
        };
    }

    /** 规范形回写 —— 落库走它，于是库里不存在非规范形。 */
    public static JsonNode writeCorrect(CorrectAnswer answer) {
        if (answer instanceof CorrectAnswer.MaterialParent) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (answer instanceof CorrectAnswer.SingleChoice single) {
            node.put("answer", single.key());
        } else if (answer instanceof CorrectAnswer.MultiChoice multi) {
            ArrayNode keys = node.putArray("answer");
            multi.keys().forEach(keys::add);
        } else if (answer instanceof CorrectAnswer.TrueFalse bool) {
            node.put("answer", bool.value());
        } else if (answer instanceof CorrectAnswer.Blanks blanks) {
            ArrayNode array = node.putArray("blanks");
            for (CorrectAnswer.BlankKey blank : blanks.blanks()) {
                ObjectNode one = array.addObject();
                one.put("index", blank.index());
                ArrayNode accepts = one.putArray("accepts");
                blank.accepts().forEach(accepts::add);
            }
        } else if (answer instanceof CorrectAnswer.Reference reference) {
            node.put("text", reference.text());
        }
        return node;
    }

    // ================================================================ 学生作答

    /**
     * 解析并规范化学生作答（模块 15 调）。
     *
     * <p>{@code null} / 空对象 → {@link StudentAnswer.Unanswered}（03-04 §4.4：
     * 未作答的客观题记 0 分，那是<b>一个真实状态</b>，不是错误）。
     */
    public static StudentAnswer readStudent(QuestionType type, JsonNode raw) {
        requireType(type);
        if (type == QuestionType.MATERIAL) {
            // 材料题父题不作答；学生端一律以子题 ID 提交（03-04 §4.3）
            throw mismatch("材料题父题不可作答，请以子题 ID 提交");
        }
        if (!present(raw)) {
            return new StudentAnswer.Unanswered();
        }
        JsonNode node = requirePresentObject(raw, "answer");
        return switch (type) {
            case SINGLE -> node.hasNonNull("answer")
                    ? new StudentAnswer.SingleChoice(readOptionKey(node.get("answer"), "answer"))
                    : new StudentAnswer.Unanswered();
            case MULTI -> node.hasNonNull("answer")
                    ? new StudentAnswer.MultiChoice(readOptionKeys(node.get("answer")))
                    : new StudentAnswer.Unanswered();
            case TRUE_FALSE -> node.hasNonNull("answer")
                    ? new StudentAnswer.TrueFalse(readBoolean(node.get("answer")))
                    : new StudentAnswer.Unanswered();
            case BLANK -> node.hasNonNull("blanks")
                    ? new StudentAnswer.Blanks(readFilledBlanks(node.get("blanks")))
                    : new StudentAnswer.Unanswered();
            case SUBJECTIVE -> node.hasNonNull("text")
                    ? new StudentAnswer.Text(readText(node.get("text"), "text"))
                    : new StudentAnswer.Unanswered();
            case MATERIAL -> throw new IllegalStateException("unreachable");
        };
    }

    // ================================================================ 题干内容

    /**
     * 校验 {@code content} 与题型匹配（30006）。
     *
     * <p>逐型：单选/多选要 {@code stem} + 2~8 个 {@code options}（{@code key} 唯一）；
     * 判断/简答/材料题只要 {@code stem}；填空要 {@code blankCount} 1~10
     * 且<b>等于 {@code stem} 中 {@code ____} 的出现次数</b>（03-04 §2.2）。
     */
    public static void validateContent(QuestionType type, JsonNode content) {
        requireType(type);
        JsonNode node = requirePresentObject(content, "content");
        String stem = readText(node.get("stem"), "content.stem");
        if (stem.isBlank()) {
            throw mismatch("content.stem 不能为空");
        }
        switch (type) {
            case SINGLE, MULTI -> validateOptions(node.get("options"));
            case BLANK -> validateBlankCount(node.get("blankCount"), stem);
            default -> {
                // 判断 / 简答 / 材料题：只有题干
            }
        }
    }

    /** {@code content.options} 里的选项号集合（顺序保持原样，供答案交叉校验）。 */
    public static Set<String> optionKeys(JsonNode content) {
        Set<String> keys = new LinkedHashSet<>();
        JsonNode options = content == null ? null : content.get("options");
        if (options == null || !options.isArray()) {
            return keys;
        }
        for (JsonNode option : options) {
            JsonNode key = option.get("key");
            if (key != null && key.isTextual()) {
                keys.add(key.asText().trim());
            }
        }
        return keys;
    }

    /**
     * 答案与题干的<b>交叉校验</b>（30006）—— 单独一个方法，因为它需要两边。
     *
     * <p>抓的是「类型对、结构也对，但答案指向了不存在的选项 / 空数对不上」这一类：
     * 单选 {@code {"answer":"E"}} 而选项只有 A~D，或填空 {@code blankCount=2}
     * 而 {@code blanks} 只有 1 项 —— 都是 30006 的登记场景（03-04 §0.3）。
     */
    public static void validateAgainstContent(QuestionType type, JsonNode content,
                                              CorrectAnswer answer) {
        requireType(type);
        switch (type) {
            case SINGLE -> assertKnownKeys(content, List.of(((CorrectAnswer.SingleChoice) answer).key()));
            case MULTI -> assertKnownKeys(content, ((CorrectAnswer.MultiChoice) answer).keys());
            case BLANK -> {
                List<CorrectAnswer.BlankKey> blanks = ((CorrectAnswer.Blanks) answer).blanks();
                int declared = content == null || content.get("blankCount") == null
                        ? -1 : content.get("blankCount").asInt(-1);
                if (declared != blanks.size()) {
                    throw mismatch("content.blankCount=" + declared
                            + " 与 correctAnswer.blanks 的 " + blanks.size() + " 项不一致");
                }
                for (int i = 0; i < blanks.size(); i++) {
                    if (blanks.get(i).index() != i + 1) {
                        throw mismatch("correctAnswer.blanks 的 index 必须是 1.." + blanks.size()
                                + " 且不重不缺，实际第 " + (i + 1) + " 项是 " + blanks.get(i).index());
                    }
                }
            }
            default -> {
                // 判断 / 简答 / 材料题：答案与题干之间没有交叉约束
            }
        }
    }

    // ================================================================ 私有

    private static void validateOptions(JsonNode options) {
        if (options == null || !options.isArray()) {
            throw mismatch("选择题的 content.options 必须是数组");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode option : options) {
            JsonNode key = option == null ? null : option.get("key");
            JsonNode text = option == null ? null : option.get("text");
            if (key == null || !key.isTextual() || key.asText().isBlank()) {
                throw mismatch("content.options[].key 必须是非空字符串");
            }
            if (text == null || !text.isTextual()) {
                throw mismatch("content.options[].text 必须是字符串");
            }
            if (!keys.add(key.asText().trim())) {
                throw mismatch("content.options[].key 重复：" + key.asText());
            }
        }
        if (keys.size() < MIN_OPTIONS || keys.size() > MAX_OPTIONS) {
            throw mismatch("选项数必须在 " + MIN_OPTIONS + "~" + MAX_OPTIONS
                    + " 之间（PRD F3-1），实际 " + keys.size());
        }
    }

    private static void validateBlankCount(JsonNode blankCount, String stem) {
        if (blankCount == null || !blankCount.isInt()) {
            throw mismatch("填空题的 content.blankCount 必须是整数");
        }
        int count = blankCount.asInt();
        if (count < MIN_BLANKS || count > MAX_BLANKS) {
            throw mismatch("空数必须在 " + MIN_BLANKS + "~" + MAX_BLANKS
                    + " 之间（PRD F3-1），实际 " + count);
        }
        int markers = countMarkers(stem);
        if (markers != count) {
            throw mismatch("content.stem 里的 " + BLANK_MARKER + " 出现 " + markers
                    + " 次，与 blankCount=" + count + " 不一致（03-04 §2.2）");
        }
    }

    private static int countMarkers(String stem) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = stem.indexOf(BLANK_MARKER, from);
            if (at < 0) {
                return count;
            }
            count++;
            // 连续的下划线只算一个空位：跳过整段下划线
            from = at + BLANK_MARKER.length();
            while (from < stem.length() && stem.charAt(from) == '_') {
                from++;
            }
        }
    }

    private static void assertKnownKeys(JsonNode content, List<String> keys) {
        Set<String> known = optionKeys(content);
        for (String key : keys) {
            if (!known.contains(key)) {
                throw mismatch("答案选项号 " + key + " 不在 content.options 里（可选 " + known + "）");
            }
        }
    }

    private static String readOptionKey(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw typeError(field + " 必须是字符串（选项号）");
        }
        String key = node.asText().trim();
        if (key.isEmpty()) {
            throw mismatch(field + " 不能为空");
        }
        return key;
    }

    /** 多选：<b>排序 + 去重</b>，规范化在这里一次做完。 */
    private static List<String> readOptionKeys(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw typeError("answer 必须是字符串数组（多选）");
        }
        Set<String> sorted = new TreeSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                throw typeError("answer[] 的每一项都必须是字符串（选项号）");
            }
            String key = item.asText().trim();
            if (key.isEmpty()) {
                throw mismatch("answer[] 里出现了空的选项号");
            }
            sorted.add(key);
        }
        if (sorted.isEmpty()) {
            throw mismatch("多选题的 answer 至少要有一个选项号");
        }
        return List.copyOf(sorted);
    }

    /**
     * 判断题：<b>只认 JSON 布尔字面量</b>。
     *
     * <p><b>这三行是本模块最要紧的三行</b> —— 改成 {@code node.asBoolean()}
     * 就会把 {@code "true"} 悄悄接受，而那意味着一旦两端不一致，全部判断题一律判错。
     * 见类注释第一段。
     */
    private static boolean readBoolean(JsonNode node) {
        if (node == null || !node.isBoolean()) {
            throw typeError("判断题的 answer 必须是 JSON 布尔字面量 true / false，"
                    + "不是字符串 \"true\"（契约 §5 强调段）");
        }
        return node.booleanValue();
    }

    private static String readText(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw typeError(field + " 必须是字符串");
        }
        return node.asText();
    }

    /** 填空标准答案：按 {@code index} 升序、{@code accepts} 去首尾空白并去重。 */
    private static List<CorrectAnswer.BlankKey> readBlankKeys(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw typeError("填空题的 blanks 必须是数组");
        }
        List<CorrectAnswer.BlankKey> blanks = new ArrayList<>();
        for (JsonNode item : node) {
            int index = readIndex(item);
            JsonNode accepts = item.get("accepts");
            if (accepts == null || !accepts.isArray()) {
                throw typeError("blanks[].accepts 必须是字符串数组（同义答案集，F-70）");
            }
            Set<String> values = new LinkedHashSet<>();
            for (JsonNode accept : accepts) {
                if (accept == null || !accept.isTextual()) {
                    throw typeError("blanks[].accepts[] 的每一项都必须是字符串");
                }
                String value = accept.asText().strip();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            if (values.isEmpty()) {
                throw mismatch("blanks[index=" + index + "].accepts 至少要有一个非空答案");
            }
            blanks.add(new CorrectAnswer.BlankKey(index, List.copyOf(values)));
        }
        if (blanks.isEmpty()) {
            throw mismatch("填空题的 blanks 不能为空");
        }
        blanks.sort((a, b) -> Integer.compare(a.index(), b.index()));
        assertNoDuplicateIndex(blanks.stream().map(CorrectAnswer.BlankKey::index).toList());
        return List.copyOf(blanks);
    }

    /** 填空学生作答：按 {@code index} 升序、{@code text} 去首尾空白。 */
    private static List<StudentAnswer.FilledBlank> readFilledBlanks(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw typeError("填空题作答的 blanks 必须是数组");
        }
        List<StudentAnswer.FilledBlank> blanks = new ArrayList<>();
        for (JsonNode item : node) {
            int index = readIndex(item);
            JsonNode text = item.get("text");
            if (text != null && !text.isNull() && !text.isTextual()) {
                throw typeError("blanks[].text 必须是字符串");
            }
            String value = text == null || text.isNull() ? "" : text.asText().strip();
            blanks.add(new StudentAnswer.FilledBlank(index, value));
        }
        blanks.sort((a, b) -> Integer.compare(a.index(), b.index()));
        assertNoDuplicateIndex(blanks.stream().map(StudentAnswer.FilledBlank::index).toList());
        return List.copyOf(blanks);
    }

    private static int readIndex(JsonNode item) {
        JsonNode index = item == null ? null : item.get("index");
        if (index == null || !index.isInt()) {
            throw typeError("blanks[].index 必须是整数");
        }
        int value = index.asInt();
        if (value < 1) {
            throw mismatch("blanks[].index 从 1 起，实际 " + value);
        }
        return value;
    }

    private static void assertNoDuplicateIndex(List<Integer> indexes) {
        Set<Integer> seen = new LinkedHashSet<>();
        for (Integer index : indexes) {
            if (!seen.add(index)) {
                throw mismatch("blanks[].index 重复：" + index);
            }
        }
    }

    private static JsonNode requirePresentObject(JsonNode node, String field) {
        if (!present(node)) {
            throw mismatch(field + " 不能为空");
        }
        if (!node.isObject()) {
            throw typeError(field + " 必须是 JSON 对象");
        }
        return node;
    }

    /** 空对象 {@code {}} 与 {@code null} / 缺省一律算「没给」——学生未作答就是这个形态。 */
    private static boolean present(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        return !(node.isObject() && node.isEmpty());
    }

    private static void requireType(QuestionType type) {
        if (type == null) {
            throw typeError("questionType 不能为空");
        }
    }

    /** <b>类型</b>不符 → 400（框架层参数校验失败）。 */
    private static BizException typeError(String detail) {
        return new BizException(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.getMsg() + "：" + detail);
    }

    /** <b>结构/语义</b>不符 → 30006。 */
    private static BizException mismatch(String detail) {
        return new BizException(ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH,
                ErrorCode.QUESTION_CONTENT_TYPE_MISMATCH.getMsg() + "：" + detail);
    }
}
