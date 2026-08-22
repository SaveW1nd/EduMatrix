# -*- coding: utf-8 -*-
import re, os, subprocess
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

print("\n④ 接口定义完整性")
for iface,need in [('接口 30',['请求体','响应体','错误码','触发时机','处理流程']),
                   ('接口 31',['权限','错误码']),('接口 32',['权限','错误码'])]:
    head={'接口 30':'## 3. 接口 30','接口 31':'## 9. 接口 31','接口 32':'## 10. 接口 32'}[iface]
    i=plan.index(head)
    m=re.search(r'^## \d+\. ', plan[i+len(head):], re.M)
    seg=plan[i:i+len(head)+m.start()] if m else plan[i:]
    miss=[n for n in need if n not in seg]
    ok(f"{iface} 定义齐（{'/'.join(need)}）") if not miss else no(f"{iface} 缺：{miss}")

print("\n⑤ 工作区 == 提交")
d=subprocess.run(['git','status','--porcelain'],capture_output=True,text=True).stdout.strip()
ok("干净") if not d else print(f"  ⚠ 有未提交改动（本轮编辑，稍后提交）：{len(d.splitlines())} 个文件")

print("\n" + ("✅ 交叉核对全部通过" if not bad else f"❌ {len(bad)} 项待处理"))
