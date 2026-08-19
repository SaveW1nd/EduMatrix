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
# 【为什么剔除注释行】公共层的类注释里大量引用 "OR tenant_id = 0" 与 "FIND_IN_SET"
# 来解释「为什么不能这么写」——那正是规则本身所在的地方。裸 grep 会把这些解释算成违规，
# 于是检查每次都红，最后没人看。检查的对象是【代码】，不是讲代码的话。
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_SRC="$ROOT/backend/src/main/java"
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
