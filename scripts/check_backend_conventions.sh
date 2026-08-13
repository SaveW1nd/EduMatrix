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

# --- ④ ignore() 逃生舱可审计（契约 §2.8）-----------------------------------
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
