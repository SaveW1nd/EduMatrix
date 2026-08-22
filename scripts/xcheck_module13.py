# -*- coding: utf-8 -*-
import re, os, sys, subprocess
P='docs/模块13-实施方案-2026-08-22.md'; A='docs/03-API接口文档/03-课程与视频.md'
R='docs/01-PRD-产品需求文档.md'; C='docs/DESIGN-CONTRACT.md'; F='docs/04-实施计划.md'
plan=open(P,encoding='utf-8').read(); bad=[]
ok=lambda t: print(f"  ✅ {t}")
def no(t): print(f"  ❌ {t}"); bad.append(t)
def rows(seg):
    out=[]
    for l in seg.split('\n'):
        if not l.startswith('|'): continue
        c=[x.strip() for x in l.strip().strip('|').split('|')]
        if len(c)>=2 and c[0] not in ('场景','编号','---','用例','#','字段','项','机制','码','触发时机'): out.append(c)
    return out

print("① 变异 ↔ 用例 逐字配对")
cases=rows(plan[plan.index('### 11.1'):plan.index('### 11.2')])
muts=[c for c in rows(plan[plan.index('### 11.2'):plan.index('### 11.3')]) if re.match(r'^M\d+$',c[0])]
names={re.sub(r'\*','',c[0]).strip() for c in cases}
b=[m[0] for m in muts if re.sub(r'\*','',m[2]).strip() not in names]
ok(f"{len(muts)} 条变异全部逐字对上（用例 {len(cases)} 条）") if not b else no(f"对不上：{b}")
ids=[int(m[0][1:]) for m in muts]
ok(f"编号 M{min(ids)}~M{max(ids)} 连续无重") if sorted(ids)==list(range(min(ids),max(ids)+1)) else no("编号断档或重号")
un=sorted(names-{re.sub(r'\*','',m[2]).strip() for m in muts})
exempt={'丢失一条心跳','锁屏虚增 34.8、Δt = 89'}
ok("无变异守护的用例都已在文档里写明理由") if set(un)==exempt else no(f"未守护且未写明：{sorted(set(un)-exempt)}")

print("\n② 五份文档关键事实一致")
for name,pat,files in [
 ('会话级上限 30', r'30\s*条|≤\s*\*{0,2}30', [R,A,F,P]),
 ('间隔闸 8s', r'8s|8 秒', [R,A,F,P]),
 ('异常明细 20 条', r'20\s*条异常|最多写\s*\*{0,2}20', [R,A,F,P]),
 ('trigger 第 9 字段', r'trigger', [C,R,A,F,P]),
 ('WARN 每分钟一条', r'每会话每分钟最多一条|warnOncePerMinute', [A,F,P]),
 ('interval_sec 判别', r'interval_sec\s*[≥>]=?\s*8', [A,F,P])]:
    miss=[os.path.basename(f) for f in files if not re.search(pat, open(f,encoding='utf-8').read())]
    ok(f"{name}（{len(files)} 份）") if not miss else no(f"{name} 缺：{miss}")

print("\n③ 废弃说法 / 悬空引用 / 三道闸")
mutseg=plan[plan.index('### 11.2'):plan.index('### 11.3')]
for w in ['事件心跳不设上限','不做任何间隔限制','配额制']:
    ok(f"「{w}」已清除") if plan.count(w)==0 else no(f"「{w}」残留 {plan.count(w)} 处")
sec=set(re.findall(r'^#{2,4}\s*([0-9]+(?:\.[0-9]+)*)[\.、 ]', plan, re.M)); sec|={x.split('.')[0] for x in sec}
d=[r for r in set(re.findall(r'§\s*([0-9]+(?:\.[0-9]+)*)', plan))
   if r not in sec and not re.search(r'(契约|DESIGN-CONTRACT|PRD|0[1-5]-|模块\s*\d+)\s*$', plan[max(0,plan.index('§'+r)-16):plan.index('§'+r)])]
ok("无悬空小节引用") if not d else no(f"悬空：{d}")
ok("三道闸齐") if all(x in plan for x in ['闸 ①','闸 ②','闸 ③']) else no("三道闸不全")

vague=[m[0] for m in muts if re.search(r"相关|等等|之类", m[2])]
ok("变异表无含糊点名") if not vague else no(f"含糊点名：{vague}")

print("\n④ 九条规则的状态，三处必须一致")
# 为什么要有这一条：F-117 就是这么漏的 —— 需方 2026-08-22 定案废止规则 8，
# 只有本方案 §5 写着「已废止」，PRD / 03-03 / 02 三份【权威文档】里它一直是有效规则。
# 而项目纪律是「权威文档与实施方案冲突时以权威文档为准」，
# 照那条纪律实现就会把一条已经废掉的规则做出来 —— 且全部 21 项一致性检查当时都是绿的：
# C16 只核【编号集合】两侧相等（墓碑行照样占一个编号），核不到「这条到底还算不算数」。
def table_state(text, status_of):
    """表格形态：→ {规则号: 是否已废止}。同号只取第一次出现。"""
    out = {}
    for line in text.split('\n'):
        m = re.match(r'^\|\s*(\d)\s*\|(.*)$', line)
        if not m or int(m.group(1)) in out:
            continue
        cols = [c.strip() for c in m.group(2).split('|')]
        out[int(m.group(1))] = bool(re.search(r'已废止|墓碑', status_of(cols)))
    return out


plan5 = plan[plan.index('## 5. 九条规则的当前状态'):plan.index('## 6. 限流与分类')]
api831 = open(A, encoding='utf-8').read()
api831 = api831[api831.index('#### 8.3.1'):api831.index('#### 8.3.2')]
prd = open(R, encoding='utf-8').read()
prdseg = prd[prd.index('### F2-7'):prd.index('### F2-8')]


def prd_state(text):
    """PRD 是编号列表不是表格。只认【加粗标题】那一段 ——
    整行会把「删去"未通过规则 3"（规则 3 已废止）」这类【提到别条规则】的话吃进来。
    同号只取第一次：F2-7 后半还有一个 1~6 的「处理流程」编号列表，不先到先得会被它顶掉。"""
    out = {}
    for m in re.finditer(r'^(\d)\. (\*\*[^：]*)', text, re.M):
        n = int(m.group(1))
        if n not in out:
            out[n] = bool(re.search(r'已废止|墓碑', m.group(2)))
    return out


states = {
    # 方案 §5 是 | # | 规则 | 状态与处置 |，状态在第三列的开头
    '方案 §5': table_state(plan5, lambda c: c[0] + c[1][:40] if len(c) > 1 else c[0]),
    # 03-03 §8.3.1 是 | # | 规则 | 判定与处理 |，墓碑写在【规则名】里；
    # 第三列不能要 —— 规则 5 那格写着「删去「未通过规则 3」（已废止）」，说的是别条规则
    '03-03 §8.3.1': table_state(api831, lambda c: c[0]),
    'PRD F2-7': prd_state(prdseg),
}
parsed = True
for label, st in states.items():
    if sorted(st) != list(range(1, 10)):
        no(f"{label} 解析到的规则号是 {sorted(st)}，不是 1~9（锚点或表格形态变了）")
        parsed = False
base_label, base = '方案 §5', states['方案 §5']
diff = []
for label, st in states.items():
    if label == base_label:
        continue
    for n in sorted(set(base) & set(st)):
        if base[n] != st[n]:
            diff.append(f"规则 {n}：{base_label}={'废止' if base[n] else '有效'}"
                        f" vs {label}={'废止' if st[n] else '有效'}")
if not parsed:
    pass
elif diff:
    no("规则状态三处不一致 —— " + "；".join(diff))
else:
    dead = sorted(n for n, v in base.items() if v)
    ok(f"1~9 号状态三处一致（墓碑 {dead}，其余有效）")

print("\n⑤ 接口定义完整性")
for iface,need in [('接口 30',['请求体','响应体','错误码','触发时机','处理流程']),
                   ('接口 31',['权限','错误码']),('接口 32',['权限','错误码'])]:
    head={'接口 30':'## 3. 接口 30','接口 31':'## 9. 接口 31','接口 32':'## 10. 接口 32'}[iface]
    i=plan.index(head)
    m=re.search(r'^## \d+\. ', plan[i+len(head):], re.M)
    seg=plan[i:i+len(head)+m.start()] if m else plan[i:]
    miss=[n for n in need if n not in seg]
    ok(f"{iface} 定义齐（{'/'.join(need)}）") if not miss else no(f"{iface} 缺：{miss}")

print("\n⑥ 工作区 == 提交")
d=subprocess.run(['git','status','--porcelain'],capture_output=True,text=True).stdout.strip()
ok("干净") if not d else print(f"  ⚠ 有未提交改动（本轮编辑，稍后提交）：{len(d.splitlines())} 个文件")

print("\n" + ("✅ 交叉核对全部通过" if not bad else f"❌ {len(bad)} 项待处理"))
# 退出码必须跟着 bad 走。原来这里【只打印不退出】—— 实测：造一条对不上的变异点名，
# 脚本照打「❌ 3 项待处理」，`echo $?` 仍然是 0。
# 需方给的开工命令是 `check_consistency && check_backend_conventions && xcheck_module13`，
# 本脚本排在最后，**它红不红对那条链的返回值毫无影响**，任何按退出码判定的地方都看不见它。
# 「一条不会让流程红的检查等于没有检查」—— 另外两个脚本（check_consistency.py 的
# sys.exit(main())、check_backend_conventions.sh 的 exit "$FAIL"）本来就是对的，只有这个漏了。
sys.exit(1 if bad else 0)
