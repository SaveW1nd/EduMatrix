#!/usr/bin/env bash
# ============================================================================
# 后端约定检查（05-工程结构.md §A1 的三条硬约束 + 模块 01 的两条自检）
#
# 用法： bash scripts/check_backend_conventions.sh
# 退出码：0 = 全部通过；1 = 有违规
#
# 【为什么需要它】§A1 定案「单模块 Maven，不拆多模块」，代价是没有编译期的分层护栏。
# 那一节写明：代价的处置不是引入构建工具，而是三条可 grep 的硬约束。这个脚本就是它们。
#
# 【④ 为什么在这里】前三条全部依赖 grep 逐行输出，而 grep 遇到含 NUL 的文件会退化成
# 「Binary file ... matches」——不给行号，剔注释也失效。④ 守的就是前三条的【前提】。
#
# 【⚠ 一条通用纪律：基于注解名或类型名的 grep，正则必须一开始就写成全限定形态】
#     @([A-Za-z_][A-Za-z0-9_]*\.)*Name\b
#   此坑已踩过三次，【三次都是变异测试逼出来的，没有一次是读代码看出来的】：
#     · 检查⑥ 原始版只写 @(Insert|Update|Delete)\b，
#       用 @org.apache.ibatis.annotations.Update 变异时检查仍然是绿的；
#     · 检查⑤ 的 @InterceptorIgnore（模块 09）；
#     · 检查⑦ 的 extends BaseMapper（模块 10）。
#   新增任何这类检查时【一开始就按上面的形态写】，不要等变异测试来告诉你。
#
# 【为什么剔除注释行】公共层的类注释里大量引用 "OR tenant_id = 0" 与 "FIND_IN_SET"
# 来解释「为什么不能这么写」——那正是规则本身所在的地方。裸 grep 会把这些解释算成违规，
# 于是检查每次都红，最后没人看。检查的对象是【代码】，不是讲代码的话。
#
# ┌──────────────────────────────────────────────────────────────────────────┐
# │ 【加新检查前必读：全限定名绕过】                                          │
# │                                                                          │
# │ 往本脚本加任何【基于注解名或类型名】的 grep 时，正则必须【一开始就写成    │
# │ 全限定形态】：                                                           │
# │                                                                          │
# │     @([A-Za-z_][A-Za-z0-9_]*\.)*Name\b        ← 注解                     │
# │     extends[[:space:]]+([A-Za-z_][A-Za-z0-9_]*\.)*Name\b   ← 继承        │
# │                                                                          │
# │ 只写 "@Name" 或 "extends Name" 的话，@com.foo.Name / extends com.foo.Name │
# │ 会【原样通过】——而检查仍然是绿的。                                       │
# │                                                                          │
# │ 【此坑已踩过三次，每次都是变异测试逼出来的，没有一次是设计时想到的】：    │
# │   · 检查 ⑥ 原始版        —— @org.apache.ibatis.annotations.Update 绕过    │
# │   · 模块 09 的 @InterceptorIgnore 检查                                    │
# │   · 检查 ⑦ 原始版        —— extends com.baomidou...BaseMapper 绕过        │
# │                                                                          │
# │ 三次的共同形态：写检查的人脑子里是「大家都用短名」，而绕过它不需要任何    │
# │ 恶意——IDE 自动补全、避免 import 冲突都会写出全限定名。                   │
# │ 所以这不是「防坏人」，是【防一次正常的编码习惯把守卫悄悄架空】。         │
# │                                                                          │
# │ 推论：每加一条新检查，都要做一次变异验证，且变异要【故意用全限定写法】。  │
# │ 一条没做过变异验证的检查，与没有这条检查在证据上等价。                    │
# └──────────────────────────────────────────────────────────────────────────┘
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_SRC="$ROOT/backend/src/main/java"
TEST_SRC="$ROOT/backend/src/test/java"
MAPPER_XML="$ROOT/backend/src/main/resources/mapper"
FAIL=0

# 剔除 Javadoc / 行注释 / 块注释行
strip_comments() { grep -vE ':[[:space:]]*(\*|//|/\*)' || true; }

check() {
  local title="$1" count="$2" detail="$3"
  if [ "$count" -eq 0 ]; then
    printf '  ✅ %s\n' "$title"
  else
    printf '  ❌ %s —— 命中 %s 处\n' "$title" "$count"
    printf '%s\n' "$detail" | sed 's/^/       /'
    FAIL=1
  fi
}

echo "后端约定检查（05-工程结构.md §A1 / 04-实施计划.md 模块 01 自检）"
echo

# --- ① 业务包不得手写租户放行条件（契约 §2.9）------------------------------
# 漏一处就是一次零权限或一次越权，且这类漏写不会报错，只会表现为「某个页面按钮没了」。
HITS=$(grep -rn "OR tenant_id = 0" "$JAVA_SRC" --include='*.java' 2>/dev/null | strip_comments)
check "业务代码无手写的 OR tenant_id = 0（放行逻辑只写在 TenantLineHandler 一处）" \
      "$(printf '%s' "$HITS" | grep -c . || true)" "$HITS"

# --- ② Mapper 不得出现 FIND_IN_SET（契约 §2.4 / §7.1）----------------------
# 它是作用在列上的函数，无法走索引；契约 §7.1：出现在慢查询日志中即视为缺陷。
HITS=$(grep -rn "FIND_IN_SET" "$JAVA_SRC" --include='*.java' 2>/dev/null | strip_comments)
if [ -d "$MAPPER_XML" ]; then
  HITS="$HITS$(grep -rn "FIND_IN_SET" "$MAPPER_XML" 2>/dev/null || true)"
fi
check "业务 Mapper 无 FIND_IN_SET（子树查询按契约 §2.4 选路表实现）" \
      "$(printf '%s' "$HITS" | grep -c . || true)" "$HITS"

# --- ③ 领域包不得互相 import（§A1 新增的约定）------------------------------
# 跨领域读取一律走对方领域的 Service，不直接注入对方的 Mapper / 实体。
DOMAINS=(auth system org course vod question homework stat)
CROSS=""
for d in "${DOMAINS[@]}"; do
  [ -d "$JAVA_SRC/com/edumatrix/$d" ] || continue
  OTHERS=$(printf '%s|' "${DOMAINS[@]}" | sed "s/$d|//" | sed 's/|$//')
  FOUND=$(grep -rnE "^import com\.edumatrix\.($OTHERS)\." "$JAVA_SRC/com/edumatrix/$d" 2>/dev/null || true)
  CROSS="$CROSS$FOUND"
done
check "领域包之间无直接 import（跨领域一律走对方的 Service）" \
      "$(printf '%s' "$CROSS" | grep -c . || true)" "$CROSS"

# --- ④ 源码不得含 NUL 字节 ---------------------------------------------------
# 【为什么这也是一条硬检查】值相同、编译照过，但含裸 NUL 的 .java 会被 grep 与 git
# 判成【二进制文件】，于是上面三条 grep 对它只输出一行 "Binary file ... matches"：
#   ① 没有行号，违规在第几行看不出来；
#   ② strip_comments 是按行剔除注释的，拿不到行就整个失效 ——
#      该文件里【仅在注释中提及】FIND_IN_SET / OR tenant_id = 0 也会被算成违规。
# 也就是说，一个裸 NUL 同时制造【假阳性】与【看不见的真阳性】，而检查本身不会报错。
# 实际发生过一次：common/subtree/NodeAncestorCache.java 的 EMPTY_MARKER 哨兵
# 被写成了原始 0x00 字节而不是转义的 "\0"（两者值完全相同）。
NUL_FILES=""
while IFS= read -r f; do
  if LC_ALL=C tr -d '\000' < "$f" | cmp -s - "$f"; then :; else
    NUL_FILES="$NUL_FILES$f: 含 NUL 字节（字符串里的 NUL 请写成转义 \\0）"$'\n'
  fi
done < <(find "$ROOT/backend/src" -type f \( -name '*.java' -o -name '*.xml' -o -name '*.sql' -o -name '*.yml' \) 2>/dev/null)
check "backend/src 下源码不含 NUL 字节（否则 grep 把它当二进制，上面三条检查对它全部失灵）" \
      "$(printf '%s' "$NUL_FILES" | grep -c . || true)" "$NUL_FILES"

# --- ⑥ 日志表的查询 Mapper 必须只读（模块 05 新增）--------------------------
# 【为什么是一条脚本检查而不是一条注释】
#   sys_login_log 与 sys_oper_log 各有【两个】Mapper：写侧在 auth/ 与 common/operlog/，
#   读侧在 system/log/。检查③ 拦的是 import、【拦不住表】——「同一张表两份实现」
#   这件事在本项目没有任何自动守卫，这条补上其中最要紧的一半：
#   往【只读】那一侧加一个 @Insert/@Update/@Delete，等于给「操作日志可被篡改」开了口，
#   而契约 §7.2 第 5 条要求这张表保留 ≥ 6 个月、且它是排查越权时唯一的原始事实。
#
# 【它会不会红】把任意一个 @Update 加进 system/log/mapper/ 下的文件 → 立刻红。
LOG_QUERY_MAPPERS="$JAVA_SRC/com/edumatrix/system/log/mapper"
HITS=""
if [ -d "$LOG_QUERY_MAPPERS" ]; then
  # 允许全限定写法：@org.apache.ibatis.annotations.Update 与 @Update 都要抓到。
  # 【这一段是被自己的变异测试逼出来的】最初只写 "@(Insert|Update|Delete)\b"，
  # 用 @org.apache.ibatis.annotations.Update 做变异时【检查仍然是绿的】——
  # 一条抓不住绕写法的检查，正是本项目说的「绿灯不是证据」。
  HITS=$(grep -rnE "@([A-Za-z_][A-Za-z0-9_]*\\.)*(Insert|Update|Delete)\\b" "$LOG_QUERY_MAPPERS" --include='*.java' 2>/dev/null | strip_comments || true)
  MAPPER_COUNT=$(find "$LOG_QUERY_MAPPERS" -name '*.java' | wc -l | tr -d ' ')
  if [ "$MAPPER_COUNT" -eq 0 ]; then
    # 目录空了 = 检查在空转。宁可报违规，也不要一条永远为绿的检查
    HITS="$LOG_QUERY_MAPPERS: 目录下没有任何 Mapper —— 本检查正在空转，请确认是否被误删"
  fi
fi
check "日志表查询 Mapper 只读（system/log/mapper 下无 @Insert/@Update/@Delete）" \
      "$(printf '%s' "$HITS" | grep -c . || true)" "$HITS"

# --- ⑦ 题目版本表只增不改（模块 10 新增）------------------------------------
# 【为什么是一条脚本检查而不是一条注释】
#   契约 §4 版本规则与 PRD F3-2 规则 3 要求「历史版本不可修改、不可删除」
#   「无任何更新入口（含管理员）」。第一道守卫是编译期的：
#   QbQuestionVersionMapper 【不 extends BaseMapper】，于是 updateById / deleteById
#   这两个方法压根不存在，写出来编译不过。
#   但编译期护栏挡不住「下一个人给它加上 extends BaseMapper」——那一步不会报错，
#   而它一旦发生，「历史版本不可改」就退回成一句注释。这条 grep 守的是那一步。
#
# 【三条各自抓什么】
#   1) extends BaseMapper —— 白送四个写方法，最常见的复发路径；
#      （含全限定写法 extends com.baomidou...BaseMapper，见下方那行注释）
#   2) Update / Delete 注解 —— 在窄 Mapper 上直接开一个写口子。
#      写法照抄检查 ⑥ 被自己的变异测试逼出来的全限定名形态：
#      @org.apache.ibatis.annotations.Update 也要抓到；
#   3) 全 src/main 内的 UPDATE / DELETE ... qb_question_version ——
#      抓的是【在别处另写一份】。检查③ 拦 import、拦不住表，检查⑥ 只看一个目录，
#      这一条是三者里唯一按【表名】兜底的。
#
# 【它会不会红 —— 变异验证见提交记录】
#   A 给 QbQuestionVersionMapper 加 extends BaseMapper<QbQuestionVersion> → 红
#   B 给它加一个 @Update 方法 → 红
#   C 在任意 mapper XML 或 Java 里写 UPDATE qb_question_version → 红
VERSION_MAPPER="$JAVA_SRC/com/edumatrix/question/bank/mapper/QbQuestionVersionMapper.java"
HITS=""
if [ -f "$VERSION_MAPPER" ]; then
  # 全限定写法也要抓到 —— 【这一行是被自己的变异测试逼出来的】：最初写
  # "extends[[:space:]]+BaseMapper"，用 extends com.baomidou.mybatisplus.core.mapper.BaseMapper
  # 做变异时【检查仍然是绿的】，与检查 ⑥ 当年踩的是同一个坑。
  HITS=$(grep -nE "extends[[:space:]]+([A-Za-z_][A-Za-z0-9_]*\.)*BaseMapper\b" "$VERSION_MAPPER" 2>/dev/null | strip_comments || true)
  HITS="$HITS"$'\n'"$(grep -nE "@([A-Za-z_][A-Za-z0-9_]*\.)*(Update|Delete)\b" "$VERSION_MAPPER" 2>/dev/null | strip_comments || true)"
else
  # 目录/文件没了 = 检查在空转。宁可报违规，也不要一条永远为绿的检查（同检查 ⑥）
  HITS="$VERSION_MAPPER: 文件不存在 —— 本检查正在空转，请确认是否被误删或改名"
fi
# 全库按表名兜底：谁在哪里写 UPDATE / DELETE 这张表都算违规
WRITE_SQL=$(grep -rnEi "(UPDATE[[:space:]]+\`?qb_question_version\`?|DELETE[[:space:]]+FROM[[:space:]]+\`?qb_question_version\`?)" \
            "$JAVA_SRC" --include='*.java' 2>/dev/null | strip_comments || true)
if [ -d "$MAPPER_XML" ]; then
  WRITE_SQL="$WRITE_SQL"$'\n'"$(grep -rnEi "(UPDATE[[:space:]]+\`?qb_question_version\`?|DELETE[[:space:]]+FROM[[:space:]]+\`?qb_question_version\`?)" "$MAPPER_XML" 2>/dev/null || true)"
fi
HITS="$HITS"$'\n'"$WRITE_SQL"
check "题目版本表只增不改（窄 Mapper 不继承 BaseMapper、无写注解、全库无 UPDATE/DELETE qb_question_version）" \
      "$(printf '%s' "$HITS" | grep -c . || true)" "$HITS"

# --- ⑧ 测试夹具往共享表插固定主键：派生规则必须逐条登记 ----------------------
# 【它守的是一次真实发生过的偶发失败】TenantConfigIT 曾报
#   Duplicate entry '1960000000000001110' for key 'sys_user_role.PRIMARY'
# 全库有【五个】夹具往同一张 sys_user_role 插固定主键，用了【三套】互不相同的派生规则
# （+1000L / +500000L / +7L）。五个值域【今天】两两不相交（十对求交全为 ∅）：
#   AuthFixtures(+1000)      [1960000000000001110 .. 1960000000000001116]
#   OrgFixtures(+7)          [1962000000000100008 .. 1962000000000100064]
#   MemberFixtures(+500000)  [1967000000000600001 .. 1967000000000600119]
#   CourseFixtures(+500000)  [1968000000000600001 .. 1968000000000600021]
#   QuestionFixtures(+500000)[1969000000000600001 .. 1969000000000600021]（模块 10）
# 靠的【不是】偏移量互不相同 —— Course / Member / Question 三家用的都是 +500000L，
# 靠的是【租户前缀互不相同】（1960 / 1962 / 1967 / 1968 / 1969，两两相距 ≥ 1e15），
# 而最大偏移量只有 6e5，跨不过去。也就是说：偏移量撞车无所谓，【租户前缀撞车才致命】。
# 这一点【纯属各模块各挑一个前缀的巧合，没有任何东西在保证它】——
# 第六个夹具挑了一个已被占用的前缀就会撞上，而后果是「只在特定执行顺序下才出现」的
# 偶发失败，单跑复现不了。
#
# 【本条上线当天就逮到了真的，不是变异】模块 10 的 QuestionFixtures 是第五个夹具，
# rebase 到含模块 10 的 main 之后本条立刻红，逼着算了一次值域（1969 前缀，不相交）
# 才登记进去。它要的就是这个动作。
#
# 【本检查不做值域求交】那需要把每个夹具的节点 ID 常量也解析出来，shell 做不可靠。
# 它做的是【钉住清单】：出现第五套派生规则（或第五个夹具文件）就红，
# 逼新增夹具的人现场登记一次 —— 那一刻正是他该去挑一个不相交号段的时候。
# 【新增第五个夹具的人怎么知道该用哪个号段】：不是"看注释"，是【脚本会红】，
# 而红的时候上面这份清单里逐行写着已被占用的值域。
#
# 【空转守卫】扫不到任何夹具就报违规 —— 目录改名 / SQL 写法改变时，
# 这条检查会静默变成永远为绿的空转（检查⑥ 与一致性检查 C14 都有同样的下限断言）。
FIXTURE_SQL='INSERT INTO sys_user_role'
# 已登记的派生规则：<夹具文件>|<主键偏移量>
EXPECTED_FIXTURE_KEYS='auth/support/AuthFixtures.java|1000L
course/catalog/support/CourseFixtures.java|500000L
org/member/support/MemberFixtures.java|500000L
org/support/OrgFixtures.java|7L
question/support/QuestionFixtures.java|500000L'
ACTUAL_FIXTURE_KEYS=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  REL=${f#"$TEST_SRC"/com/edumatrix/}
  # 主键表达式在 SQL 字面量【之后】的参数行上，形如 `userId + 1000L,` / `userIdOf(nodeId) + 500000L,`
  OFF=$(grep -A 4 "$FIXTURE_SQL" "$f" | grep -oE '\+ [0-9]+L' | head -1 | tr -d ' +')
  ACTUAL_FIXTURE_KEYS="$ACTUAL_FIXTURE_KEYS$REL|${OFF:-未能解析出偏移量}"$'\n'
done < <(grep -rl "$FIXTURE_SQL" "$TEST_SRC" 2>/dev/null | sort)
ACTUAL_FIXTURE_KEYS=$(printf '%s' "$ACTUAL_FIXTURE_KEYS" | sed '/^$/d')
FIXTURE_COUNT=$(printf '%s' "$ACTUAL_FIXTURE_KEYS" | grep -c . || true)
if [ "$FIXTURE_COUNT" -eq 0 ]; then
  check "共享表 sys_user_role 的夹具主键派生规则已登记" 1 \
        "$TEST_SRC: 一个往 sys_user_role 插固定主键的夹具都没扫到 —— 本检查正在空转"
elif [ "$ACTUAL_FIXTURE_KEYS" != "$EXPECTED_FIXTURE_KEYS" ]; then
  check "共享表 sys_user_role 的夹具主键派生规则已登记" 1 \
        "夹具清单与登记不符。新增/修改往 sys_user_role 插固定主键的夹具时：
① 挑一个与上方注释里【全部】已占用值域不相交的偏移量；
② 回来更新本脚本的 EXPECTED_FIXTURE_KEYS 与那段值域注释；
③ 在夹具类注释里写清它的值域。
漏了 ① 的后果是一次【只在特定执行顺序下出现】的 Duplicate entry，单跑复现不了。
--- 已登记 ---
$EXPECTED_FIXTURE_KEYS
--- 实际扫到 ---
$ACTUAL_FIXTURE_KEYS"
else
  check "共享表 sys_user_role 的夹具主键派生规则已登记（$FIXTURE_COUNT 个夹具）" 0 ""
fi

# --- ⑤ ignore() 逃生舱可审计（契约 §2.8）-----------------------------------
# 不是违规检查，是清单：每一处都必须能说清「为什么这个查询非跨租户不可」。
IGNORES=$(grep -rn "TenantHelper\.ignore(" "$JAVA_SRC" --include='*.java' 2>/dev/null | strip_comments || true)
IGNORE_COUNT=$(printf '%s' "$IGNORES" | grep -c . || true)
printf '  ℹ️  TenantHelper.ignore() 调用点：%s 处（每一处都要说清为什么非跨租户不可）\n' "$IGNORE_COUNT"
[ "$IGNORE_COUNT" -gt 0 ] && printf '%s\n' "$IGNORES" | sed 's/^/       /'

echo
if [ "$FAIL" -eq 0 ]; then
  echo "全部通过。"
else
  echo "存在违规，见上。"
fi
exit "$FAIL"
