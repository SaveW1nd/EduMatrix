#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EduMatrix 设计文档一致性检查

用法：
    python3 scripts/check_consistency.py            # 全量检查
    python3 scripts/check_consistency.py --only C3  # 只跑某一项

设计意图
--------
这套文档有 1.7 万行、分布在 10 个文件里，同一件事往往在契约 / PRD / DDL / API 分册
各写一遍。人肉交叉核对既慢又不可靠——本项目实际发生过的问题里，至少一半属于
"改了上游没传导到下游"或"看起来改对了但没验证"，这类错误恰恰是机械检查最擅长的。

每一项检查都对应一个真实发生过的缺陷，不是假想的规范。

退出码：0 = 全部通过；1 = 存在 ERROR；0 = 仅有 WARN（不阻塞，但应看）
"""

import json
import glob
import os
import re
import sys
import traceback
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, 'docs')
# DDL 已于模块 01 迁入工程作为 Flyway 初始基线（原 docs/sql/edumatrix_ddl.sql，
# 见 05-工程结构.md §B）。C1 / C6 / C10 / C14 / C18 五条检查全部经本常量读 DDL，
# 路径失效则这五条同时失效——read() 对缺文件报 ERROR，不会静默通过。
DDL_PATH = os.path.join(ROOT, 'backend', 'src', 'main', 'resources',
                        'db', 'migration', 'V202608120000__baseline.sql')
REGISTRY = os.path.join(DOCS, '03-API接口文档', '00-通用约定.md')

# 扫描范围。注意 scripts/README.md 故意不在列内：它的失败模式表大量点名已废弃的
# 写法（"把教师写成 node_type=1"、"退回旧枚举 1机构 2管理员 3教师 4学生"等）作为
# 反例，纳入后 C8/C9 会把这些引用整片报成误报——而那些引用正是该文件的内容本身。
MD_FILES = [
    os.path.join(ROOT, 'README.md'),
    os.path.join(ROOT, 'references', 'README.md'),
    os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
    os.path.join(DOCS, '00-原始需求.md'),
    os.path.join(DOCS, '01-PRD-产品需求文档.md'),
    os.path.join(DOCS, '02-数据库设计.md'),
    os.path.join(DOCS, '04-实施计划.md'),
    os.path.join(DOCS, '05-工程结构.md'),
] + sorted(
    os.path.join(DOCS, '03-API接口文档', f)
    for f in os.listdir(os.path.join(DOCS, '03-API接口文档'))
    if f.endswith('.md')
)

API_FILES = [f for f in MD_FILES if '03-API接口文档' in f]

results = []      # (level, code, file, detail)


def report(level, code, file, detail):
    results.append((level, code, os.path.relpath(file, ROOT) if file else '-', detail))


_MISSING = set()          # 已报过缺失的路径，避免同一个文件被十几条规则各报一次


def read(path):
    """读文件；**文件不存在时不抛异常，改为报一条 ERROR 并返回空串**。

    为什么不让它直接崩：`MD_FILES` / `API_FILES` 是写死的路径清单，文档改名或删除
    后忘了同步清单，这里就会 `FileNotFoundError`。崩溃的退出码确实是 1（CI 不会
    误判成通过），但**整个脚本在第一个缺失文件处就中止，其余十几项检查一条都跑不到**
    ——本该一次跑完的 18 项只剩下"在某处炸了"，真正的问题（比如同一批改动里还有
    错误码没登记）被这次崩溃掩盖，要修完路径才看得见。

    为什么也不静默返回空串：那是把崩溃换成**假通过**——所有针对该文件的检查都对着
    空串跑，全绿，而文件根本没被读到。这正是本仓库反复踩的那一类（见 README
    「扫描范围 ≠ 覆盖」）。所以必须留下一条 ERROR。
    """
    if not os.path.exists(path):
        if path not in _MISSING:
            _MISSING.add(path)
            report('ERROR', 'C0', path,
                   '扫描清单中登记的文件不存在——多半是文档改名或删除后没有同步 '
                   '`MD_FILES` / `API_FILES`。本文件相关的检查已全部跳过，'
                   '结果不完整，不要按"其余项全绿"判断通过')
        return ''
    with open(path, encoding='utf-8') as f:
        return f.read()


# ---------------------------------------------------------------- DDL 解析

def parse_ddl():
    """把 DDL 拆成 {表名: {'cols': [...], 'lines': [(行号, 原文)], 'body': str}}"""
    text = read(DDL_PATH)
    tables = {}
    cur, buf, start = None, [], 0
    for i, line in enumerate(text.split('\n'), 1):
        m = re.match(r'CREATE TABLE `(\w+)`', line)
        if m:
            cur, buf, start = m.group(1), [], i
            continue
        if cur and line.startswith(')'):
            body = '\n'.join(l for _, l in buf)
            cols = re.findall(r'^\s+`(\w+)`\s+[A-Z]', body, re.M)
            tables[cur] = {'cols': cols, 'lines': buf, 'body': body, 'start': start}
            cur = None
            continue
        if cur:
            buf.append((i, line))

    _apply_incremental_alters(tables)
    return tables


# 【为什么要这一段】DDL_PATH 只是【基线】。基线是冻结内容（契约 §7.3），
# 后续的表结构变更一律走增量迁移 —— 于是「DDL = 基线」这个前提在第一个
# ALTER TABLE ADD COLUMN 出现的那天就不再成立，而 C14（DDL 列集合 = 文档字段表）
# 会开始报一条【假红】：文档写了新列、基线里没有。
#
# V202608210300（F-114 给 vod_video 加 template_group_id）是全库第一个改表结构的
# 增量迁移。不补这一段的话，唯一的出路是「不把新列写进文档」——那等于让文档
# 与真实库结构分叉，而 C14 存在的理由恰恰是防这件事。
#
# 【只认 ADD COLUMN，不认 DROP / MODIFY】：那两种在本项目里应当极少发生，
# 真发生时宁可让这里报错、逼人来看一眼，也不要悄悄跟着改 —— 静默跟随会让
# 「删了一列」这种事在检查里查无实据。
_ALTER_ADD = re.compile(
    r'ALTER\s+TABLE\s+`(\w+)`\s+ADD\s+COLUMN\s+`(\w+)`', re.I | re.S)


def _apply_incremental_alters(tables):
    mig_dir = os.path.dirname(DDL_PATH)
    for name in sorted(os.listdir(mig_dir)):
        if not name.endswith('.sql') or name == os.path.basename(DDL_PATH):
            continue
        for tname, col in _ALTER_ADD.findall(read(os.path.join(mig_dir, name))):
            t = tables.get(tname)
            if t is None:
                report('ERROR', 'C14', os.path.join(mig_dir, name),
                       f'{name} 给不存在于基线的表 `{tname}` 加列 `{col}`')
                continue
            if col not in t['cols']:
                t['cols'].append(col)


# ============================================================ C1 DDL 结构

def check_c1_ddl_structure(tables):
    """
    C1 DDL 结构合法性

    真实缺陷：用脚本替换整行时漏掉行尾逗号，连续发生三次，每次都靠起 MySQL 容器
    报语法错才发现；以及按索引名批量替换时误伤同名索引（sys_user / qb_question
    都有 idx_tenant_type_status，但列分别是 user_type / question_type / node_type），
    替换后索引引用了本表不存在的列。
    """
    for tname, t in tables.items():
        defs = [(n, l) for n, l in t['lines']
                if re.match(r'\s+(`\w+`|KEY|UNIQUE KEY|PRIMARY KEY)', l)]
        # 1) 逗号
        for idx, (n, l) in enumerate(defs):
            is_last = idx == len(defs) - 1
            ends_comma = l.rstrip().endswith(',')
            if is_last and ends_comma:
                report('ERROR', 'C1', DDL_PATH, f'{tname} 第 {n} 行是最后一个定义却以逗号结尾')
            if not is_last and not ends_comma:
                report('ERROR', 'C1', DDL_PATH, f'{tname} 第 {n} 行缺少行尾逗号：{l.strip()[:60]}')
        # 2) 索引引用的列必须存在于本表
        for n, l in defs:
            if re.match(r'\s+(KEY|UNIQUE KEY|PRIMARY KEY)', l):
                head = l.split('COMMENT')[0]
                paren = re.search(r'\((.*?)\)', head)
                if not paren:
                    continue
                for col in re.findall(r'`(\w+)`', paren.group(1)):
                    if col not in t['cols']:
                        report('ERROR', 'C1', DDL_PATH,
                               f'{tname} 第 {n} 行索引引用了本表不存在的列 `{col}`')
        # 3) 唯一索引末列应为 deleted_at（全局软删除约定）
        for n, l in defs:
            if l.strip().startswith('UNIQUE KEY'):
                paren = re.search(r'\((.*?)\)', l.split('COMMENT')[0])
                if paren:
                    cols = re.findall(r'`(\w+)`', paren.group(1))
                    if cols and cols[-1] != 'deleted_at':
                        report('WARN', 'C1', DDL_PATH,
                               f'{tname} 第 {n} 行唯一索引末列不是 deleted_at（软删除约定）：{cols}')


# ======================================================== C2 枚举一致性

ENUM_PAT = re.compile(r'([a-z_]{3,30})\s*[：:（(]?\s*((?:\d\s*[\u4e00-\u9fa5A-Za-z/]{1,12}\s*){2,})')


def check_c2_enums():
    """
    C2 枚举取值一致性

    真实缺陷：org_perm_template.status 在 DDL 写「0停用 1启用」、在 API 分册写
    「0启用 1停用」，方向完全相反且 DDL 默认值随之取反——按 DDL 落库后，新建模板
    在 API 语义下即为"停用"，status=0 过滤"启用模板"一条查不出。
    """
    # status / biz_type 这类通用列名天然按表而异（媒资状态 vs 作业状态），不是冲突；
    # utf 之类来自 utf8mb4 的解析噪声一并排除
    GENERIC = {'status', 'biz_type', 'utf', 'type', 'change_type', 'export_type',
               'target_type', 'menu_type', 'storage', 'provider'}
    seen = defaultdict(set)          # 枚举名 -> {规范化取值串}
    where = defaultdict(list)
    for path in MD_FILES + [DDL_PATH]:
        for line in read(path).split('\n'):
            for m in ENUM_PAT.finditer(line):
                name, body = m.group(1), m.group(2)
                if name in GENERIC:
                    continue
                pairs = re.findall(r'(\d)\s*([\u4e00-\u9fa5A-Za-z]{1,12})', body)
                if len(pairs) < 2:
                    continue
                norm = ' '.join(f'{k}{v}' for k, v in sorted(pairs))
                seen[name].add(norm)
                where[name].append((os.path.relpath(path, ROOT), norm))
    for name, variants in seen.items():
        if len(variants) > 1:
            # 仅当同名枚举出现在 2 个以上文件时才认为是跨文档冲突
            files = {f for f, _ in where[name]}
            if len(files) > 1:
                detail = f'枚举 `{name}` 有 {len(variants)} 种取值：' + \
                         ' ｜ '.join(sorted(variants)[:3])
                report('WARN', 'C2', None, detail)


# ==================================================== C3 错误码登记与同义

def check_c3_error_codes():
    """
    C3 错误码必须在 00-通用约定 §9 登记册中存在，且全局同码同义

    真实缺陷：10010 与 10207 语义重复；10003 在 02 分册被改用为"手机号已占用"
    而登记册里是"用户名或密码错误"；PRD 引用了预留号段 20012。
    """
    reg = read(REGISTRY)
    registered = {}
    for m in re.finditer(r'^\|\s*(\d{5})\s*\|\s*([^|]+?)\s*\|', reg, re.M):
        registered[m.group(1)] = m.group(2).strip()
    if not registered:
        report('ERROR', 'C3', REGISTRY, '未能从登记册解析出任何错误码，检查表格格式')
        return

    # 从 API_FILES + [PRD] 扩到全部 MD_FILES：此前 README / 00-原始需求 /
    # 02-数据库设计 / 04-实施计划 都不在 C3 视野内——04 加进 MD_FILES 后
    # C3 依然一个错误码都没校验，因为它压根不读这张表。
    for path in MD_FILES:
        if path == REGISTRY:
            continue
        text = read(path)
        for i, line in enumerate(text.split('\n'), 1):
            # 号段/预留说明行不是"使用"，跳过
            if re.search(r'预留|号段|保留不复用|~\s*1[0-9]{4}', line):
                continue
            # 必须出现错误码语境词，否则五位数字多半是数量/毫秒/字节等业务数值。
            # `→` 与「不可/不允许」也算语境词：PRD 写「valid_start >= valid_end → 20012」
            # 一个传统语境词都没有，整行被跳过，预留码就这样绿灯放行
            if not re.search(r'返回|错误码|错误|code|拒绝|失败|校验不通过|→|不可|不允许', line):
                continue
            # 语境词之外还要求写法像错误码：`10107` / 返回 10107 / 表格首列 | 10107 |
            # / 括号内的码列表（10010 / 10207）——最后一种曾整类漏检
            # / **无括号的码枚举**（10008 / 10009 / 10012）——括号列表分支当年只按
            #   「（10010 / 10207）」这一种形态写，同类的无括号写法没进视野，一直漏着：
            #   03-课程与视频 38 处、04-题库与作业 17 处，与 04-实施计划无关。
            #   **必须至少两个五位码用分隔符相连**才触发，否则「6 万行 → 40007」这类
            #   单个业务数值会被拖进来；语境词那道门仍然先过，本分支不绕过它。
            for m in re.finditer(r'`([1-4]\d{4})`'
                                 r'|(?:返回|错误码|拒绝|失败|抛|报|→)\s*`?([1-4]\d{4})`?'
                                 r'|^\|\s*([1-4]\d{4})\s*\|'
                                 r'|[（(]\s*([1-4]\d{4})(?:\s*[/、,，]\s*[1-4]\d{4})*\s*[）)]'
                                 r'|([1-4]\d{4})(?=\s*[/、,，]\s*[1-4]\d{4})'
                                 r'|(?<=[1-4]\d{4})(?:\s*[/、,，]\s*)([1-4]\d{4})', line):
                code = next(g for g in m.groups() if g)
                if code not in registered:
                    report('ERROR', 'C3', path, f'第 {i} 行使用了未登记的错误码 {code}')
    # 预留号段：登记册声明"预留"的码一律不得被使用。
    # 这类码天生不在登记册里，只查"是否登记"永远抓不到它们——必须单独解析预留声明。
    reserved = set()
    for m in re.finditer(r'预留\s*((?:\d{5}(?:~\d{5})?[、,，]?\s*)+)', reg):
        for part in re.split(r'[、,，]', m.group(1)):
            part = part.strip()
            if re.fullmatch(r'\d{5}~\d{5}', part):
                lo, hi = (int(x) for x in part.split('~'))
                reserved.update(range(lo, hi + 1))
            elif re.fullmatch(r'\d{5}', part):
                reserved.add(int(part))
    if reserved:
        # 同上，扩到 MD_FILES：C3 的三个子检查各有各的 for 循环与入口条件，
        # 上一轮只改了第一个的文件列表，这两个继续对 04 假通过。
        # 实测扩范围后新纳入的 6 份文档零命中，无副作用。
        for path in MD_FILES:
            if path == REGISTRY:
                continue
            for i, line in enumerate(read(path).split('\n'), 1):
                # 跳过预留声明、以及「10301~10399」这类**段位范围**标题——
                # 范围端点天然落在预留集合里，但那是在划分号段而非使用错误码
                if re.search(r'预留|号段|保留不复用', line):
                    continue
                if re.search(r'\d{5}\s*~\s*\d{5}', line):
                    continue
                for m in re.finditer(r'(?<![\d.~])([1-4]\d{4})(?![\d.~])', line):
                    code = int(m.group(1))
                    if code in reserved:
                        report('ERROR', 'C3', path,
                               f'第 {i} 行使用了**预留号段**内的错误码 {code}——'
                               f'预留码不在登记册中，"是否已登记"这条检查永远抓不到它')

    # 废弃码不应再被任何分册引用。
    # 这是 ERROR 不是 WARN：废弃码保留号位正是为了让历史日志里的旧值不产生歧义，
    # 一旦被重新引用，同一个码就又有了两种含义——与"一码两义"是同一类缺陷。
    # 真实案例：20017（回调签名校验失败）随转码回调改为消息队列消费而退役。
    # 解释性引用豁免：契约 §2.8 论证「为什么改用 SMQ 拉取而不是 HTTP 回调」时，
    # 必须原样写出当年那个会被判成投递成功的响应体 `{"code":20017}`——**删了论证就断了**，
    # 与 00-通用约定 §10 那笔「160 − 1 + 1 = 159」的接口数注记同性质：
    # 它记录的是一次改造的理由，不是待清理的残留。后人不要顺手清掉。
    # 锚点用「投递可靠性」而不是码本身——用码当锚点会把该文件所有引用一起豁免。
    _DEPRECATED_ALLOW = ('投递可靠性', '会被判成投递成功')
    for code, meaning in registered.items():
        if '空号' in meaning or '已废弃' in meaning:
            for path in MD_FILES:
                if path == REGISTRY:
                    continue
                for i, line in enumerate(read(path).split('\n'), 1):
                    if any(a in line for a in _DEPRECATED_ALLOW):
                        continue
                    if code in line and '废弃' not in line and '空号' not in line:
                        report('ERROR', 'C3', path,
                               f'第 {i} 行引用了已废弃的错误码 {code}（登记册：{meaning[:30]}）')


# ================================================ C4 字段长度 API vs DDL

def check_c4_field_length(tables):
    """
    C4 API 声明的字段长度不得宽于 DDL

    真实缺陷：crs_course.description 接口写"最长 2000 字符"、DDL 是 VARCHAR(1000)。
    前端按 2000 校验，用户填 1500 字保存直接 500。
    """
    ddl_len = {}
    for tname, t in tables.items():
        for m in re.finditer(r'`(\w+)`\s+VARCHAR\((\d+)\)', t['body']):
            ddl_len.setdefault(m.group(1), []).append((tname, int(m.group(2))))

    # API 表格行形如：| description | string | 否 | 课程简介，最长 2000 字符 |
    row = re.compile(r'^\|\s*(\w+)\s*\|[^|]*\|[^|]*\|([^|]*?)\|', re.M)
    for path in API_FILES:
        text = read(path)
        for m in row.finditer(text):
            field, desc = m.group(1), m.group(2)
            snake = re.sub(r'(?<!^)(?=[A-Z])', '_', field).lower()
            if snake not in ddl_len:
                continue
            lm = re.search(r'最长\s*(\d+)\s*(?:字符|字)', desc) or \
                 re.search(r'(\d+)\s*~\s*(\d+)\s*(?:字符|位)', desc)
            if not lm:
                continue
            declared = int(lm.groups()[-1])
            # 同名列可能分布在多张表，取最宽者比对：只有比所有候选都宽才是真冲突
            tname, dlen = max(ddl_len[snake], key=lambda x: x[1])
            if declared > dlen:
                line_no = text[:m.start()].count('\n') + 1
                report('ERROR', 'C4', path,
                       f'第 {line_no} 行 `{field}` 声明最长 {declared}，'
                       f'但 DDL 中最宽的 {tname}.{snake} 只有 VARCHAR({dlen})')


# ==================================================== C5 接口数量一致性

def check_c5_interface_count():
    """
    C5 目录表接口数、正文接口数、README 声明数三者一致

    真实缺陷：README 写 244 个接口，实际 159 个——原数字是 grep 全文唯一
    method+path 得来的，把正文里提及的路径也算了进去。
    """
    total_toc = 0
    for path in API_FILES:
        if path == REGISTRY:
            continue
        text = read(path)
        toc = len(re.findall(r'^\|\s*[0-9]+(?:\.[0-9]+)?\s*\|.*\|\s*(?:GET|POST|PUT|DELETE)\s*\|',
                             text, re.M))
        body = len(re.findall(r'^\s*-\s*\*\*方法(?:\+|与)路径\*\*', text, re.M))
        if toc and body and toc != body:
            report('WARN', 'C5', path, f'目录表 {toc} 个接口，正文 {body} 个接口定义')
        total_toc += toc

    readme = read(os.path.join(ROOT, 'README.md'))
    m = re.search(r'\*\*(\d+)\s*个接口\*\*', readme)
    if m and int(m.group(1)) != total_toc:
        report('ERROR', 'C5', os.path.join(ROOT, 'README.md'),
               f'README 声明 {m.group(1)} 个接口，各分册目录表合计 {total_toc} 个')


# =============================================== C6 表数与契约表清单一致

def check_c6_table_count(tables):
    """C6 DDL 建表数 = 契约第 4 节表清单 = 契约声明的总数 = README 声明"""
    contract = read(os.path.join(DOCS, 'DESIGN-CONTRACT.md'))
    # 只取第 4 节表清单，不扫全文——否则契约里任何 `snake_case` 反引号
    # （如 §7.1 的监控指标名 vod_callback_orphan_total）都会被当成表名
    m = re.search(r'^## 4\..*?(?=^## 5\.)', contract, re.M | re.S)
    if not m:
        report('ERROR', 'C6', os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
               '未能定位第 4 节表清单，检查标题格式')
        return
    listed = set(re.findall(r'^\|\s*\*{0,2}`(\w+)`', m.group(0), re.M))
    listed = {t for t in listed if re.match(r'(sys|org|crs|vod|qb|hw|stat)_', t)}
    ddl_set = set(tables)

    for t in listed - ddl_set:
        report('ERROR', 'C6', DDL_PATH, f'契约列出但 DDL 未建表：{t}')
    for t in ddl_set - listed:
        report('ERROR', 'C6', os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
               f'DDL 建了但契约表清单未列：{t}')

    for path in [os.path.join(DOCS, 'DESIGN-CONTRACT.md'), os.path.join(ROOT, 'README.md')]:
        for m in re.finditer(r'(\d+)\s*张表?', read(path)):
            n = int(m.group(1))
            if 20 < n < 200 and n != len(ddl_set):
                report('WARN', 'C6', path, f'文中出现"{n} 张"，而 DDL 实际 {len(ddl_set)} 张')


# ================================================ C7 Markdown 基础健康度

def check_c7_markdown():
    """C7 JSON 块可解析、表格列数齐整、代码围栏配平"""
    for path in MD_FILES:
        text = read(path)
        if text.count('```') % 2:
            report('ERROR', 'C7', path, '代码围栏数为奇数，存在未闭合的代码块')
        for m in re.finditer(r'```json\n(.*?)```', text, re.S):
            body = '\n'.join(l for l in m.group(1).split('\n')
                             if not re.match(r'^\s*(GET|POST|PUT|DELETE)\s', l))
            try:
                json.loads(body)
            except Exception as e:
                line_no = text[:m.start()].count('\n') + 1
                report('ERROR', 'C7', path, f'第 {line_no} 行起的 JSON 块无法解析：{str(e)[:50]}')
        for blk in re.findall(r'(?:^\|.*\n)+', text, re.M):
            rows = [r for r in blk.strip().split('\n') if r.startswith('|')]
            if len(rows) >= 2 and re.match(r'^\|[\s:\-|]+\|$', rows[1]):
                n = rows[0].count('|')
                for r in rows:
                    if r.count('|') != n:
                        report('ERROR', 'C7', path, f'表格列数不齐：{r.strip()[:60]}')
                        break


# ============================================== C8 禁用词 / 陈旧内容残留

BANNED = {
    'node_type=4': 'node_type 已收敛为 0/1/2/3 四类',
    'nodeType=4': 'node_type 已收敛为 0/1/2/3 四类',
    '机构/管理单元': '已去掉独立于人的组织单元节点类型',
    '管理单元': '已去掉独立于人的组织单元节点类型，组织层级由管理员节点的嵌套表达',
    '纯组织容器': '树上不存在不绑账号的节点',
    'creator_id': '专用创建人列已废除，署名统一用公共字段 create_by',
    'deleted_at=1': 'deleted_at 是毫秒时间戳，不是 0/1 标志',
    'deleted_at = 1': 'deleted_at 是毫秒时间戳，不是 0/1 标志',
    '机构或管理员节点': '机构根节点本身就是管理员节点，不是并列的两类',
    '机构 / 管理员节点': '机构根节点本身就是管理员节点，不是并列的两类',
    'org_class': '班级模型已废弃',
    'org_teacher_class': '班级模型已废弃',
    'crs_course_class': '已并入 org_resource_grant',
    'stat_class_daily': '已拆为 stat_teacher_daily + stat_node_daily',
    'DataScope 三档': '数据权限只有"本节点子树"一条规则',
    'V1.0': '文档不保留版本演进叙述',
    'V2.0': '文档不保留版本演进叙述',
}


# 已废弃概念的"裸词"登记。
#
# 与 BANNED 的区别：BANNED 收的是**当年那次缺陷的具体写法**（org_class 是表名残留、
# DataScope 三档 是那句话的原文），换个写法就穿过去了——references/README.md:13 的
# 「数据权限（DataScope），对应"教师仅看自己班级"」在 BANNED 表下活了很久，正因为
# 它写的是裸词而非登记的那两种形态。这里收概念本身。
#
# 代价是文档里有大量"点名它以说明不用它"的否定式表述（全库 13 行，01-认证与系统.md
# 一个文件就占 7 行），逐条豁免要写 13 条且任一处改字就失效。改判上下文：**紧邻在前
# 的否定词**放行。这样否定式一次性全过，而将来有人新写一句肯定式的"支持 DataScope
# 分档"会被立刻抓到——这才是这条规则要防的东西。
DEPRECATED_CONCEPT = {
    r'[Dd]ata[_ ]?[Ss]cope': '数据权限不设分档，全系统只有"你能看到的数据 = 你所在节点的子树"一条规则（契约 §2.4 / §3）',
    r'班级': '教学的最小单元是"导师-学员"关系，系统没有班级概念（契约 §2.4、00-原始需求 §1）',
}
# 否定词须落在命中词前 10 个字符内（足够跨过 ** 与反引号，跨不过一个从句）
_NEG = re.compile(
    r'(?:不设|不再|不预设|不采用|不存在|不含|不返回|不出现|不接受|不叫|没有|无|'
    r'已删除|已移除|已废弃|禁止|本质区别)[^一-龥]{0,10}$')


# 少数位置需要"点名旧方案以说明为什么不用它"，逐条豁免而不是整行放行
ALLOW = [
    ('is_deleted', '若用 `is_deleted` 这类 0/1 标志'),      # 契约 §2.2 论证前提
    ('is_deleted', '`is_deleted TINYINT(0/1)`'),            # 02-数据库设计 §7 对照表
    ('is_deleted', '| 逻辑删除 | `is_deleted` 0/1 |'),        # 契约 §2.2「同源原则」对照表
    ('creator_id', '不设 `creator_id` 这类专用创建人列'),   # 契约 §2.2 禁用声明
    ('creator_id', 'creatorId'),                            # DTO 字段名说明中提及列名
]


def check_c8_banned():
    """C8 禁用词扫描：陈旧概念、已废弃表名、版本演进措辞"""
    banned = dict(BANNED)
    banned['is_deleted'] = '软删除已统一为 deleted_at 时间戳'
    for path in MD_FILES + [DDL_PATH]:
        if path.endswith('00-原始需求.md'):
            continue     # 需求基线允许出现"不采用班级制"这类对照表述
        for i, line in enumerate(read(path).split('\n'), 1):
            for word, why in banned.items():
                if word not in line:
                    continue
                if any(w == word and ctx in line for w, ctx in ALLOW):
                    continue
                report('ERROR', 'C8', path, f'第 {i} 行出现 `{word}`（{why}）')
            for pat, why in DEPRECATED_CONCEPT.items():
                for m in re.finditer(pat, line):
                    if _NEG.search(line[:m.start()]):
                        continue     # "不设 DataScope 分档"这类否定式表述
                    report('ERROR', 'C8', path,
                           f'第 {i} 行出现已废弃概念 `{m.group(0)}`（{why}）')


# ====================================== C9 概念 ↔ 编号绑定（node_type / user_type）

# 权威映射取自契约第 5 节；同义词一并登记，避免"导师"写成 3 之类的漏网
TYPE_MAP = {
    '平台超管': 0, '超管': 0, '平台超级管理员': 0,
    '管理员': 1, '机构管理员': 1, '下级管理员': 1, '机构最高管理员': 1,
    '教师': 2, '导师': 2,
    '学生': 3, '学员': 3,
}
_FIELD = r'(?:node_?[Tt]ype|user_?[Tt]ype)'
# ① 概念紧跟编号：「教师（node_type=2）」——先剥掉 markdown 的 ** 与反引号再匹配
_PAT_NAME_FIRST = re.compile(r'([一-龥]{2,6})[（(](?:→[^（）]*，)?' + _FIELD + r'\s*[=＝]\s*(\d)')
# ② 编号紧跟概念：「node_type=2（教师）」
_PAT_NUM_FIRST = re.compile(_FIELD + r'\s*[=＝]\s*(\d)\s*[（(]([一-龥]{2,6})[）)]')
# ③ 整串枚举：「node_type：0平台超管 1管理员 2教师 3学生」/「(1机构 2管理员 3教师 4学生)」
_PAT_ENUM_HEAD = re.compile(_FIELD + r'[^\n]{0,24}?[（(：:]')
_PAT_ENUM_PAIR = re.compile(r'(\d)\s*([一-龥]{2,5})')
# ④ DDL 列注释：「导师节点ID（→org_node.id，node_type=2）」——概念在注释开头
_PAT_DDL_COMMENT = re.compile(r"COMMENT\s+'([一-龥]{2,6})节点ID[^']*?" + _FIELD + r'\s*[=＝]\s*(\d)')


def _lookup(name):
    """名字可能带前缀（如「作答时刻导师」），取最长后缀匹配"""
    for key in sorted(TYPE_MAP, key=len, reverse=True):
        if name.endswith(key):
            return key, TYPE_MAP[key]
    return None, None


def check_c9_type_binding():
    """C9 中文概念与 node_type / user_type 数值的绑定必须处处一致

    真实缺陷：node_type 从 {1机构,2管理员,3教师,4学生} 收敛为 {0超管,1管理员,2教师,3学生}
    后，契约停用语义表把教师写成 1（与管理员撞号）、学生写成 2；DDL 有 7 处
    teacher_node_id 注释仍写 node_type=3（新编号里 3 是学生）；PRD F1-3 整列是旧编号；
    契约 §4 表清单整条枚举没改。这类错误不改变任何标识符名称，全文搜索 node_type
    能找到全部位置，但每一处该改成什么必须逐个读语义——机械替换必然出错，而改错了
    不会报编译错误，只会在运行时表现为"教师被当成学生"。

    四种句式全查：概念在前、编号在前、整串枚举、DDL 列注释。
    """
    for path in MD_FILES + [DDL_PATH]:
        for i, raw in enumerate(read(path).split('\n'), 1):
            line = raw.replace('**', '').replace('`', '')

            for m in _PAT_NAME_FIRST.finditer(line):
                # 只认完整概念词。「某位导师的名下学员（node_type = 2）」里的注解属于
                # 导师而非学员，后缀匹配会误判——这类归属歧义机械上无法消解，宁可漏
                want = TYPE_MAP.get(m.group(1))
                key = m.group(1) if want is not None else None
                if key and want != int(m.group(2)):
                    report('ERROR', 'C9', path,
                           f'第 {i} 行「{m.group(1)}」写作 {m.group(0).split("(")[-1]}，应为 {want}')

            for m in _PAT_NUM_FIRST.finditer(line):
                want = TYPE_MAP.get(m.group(2))
                key = m.group(2) if want is not None else None
                if key and want != int(m.group(1)):
                    report('ERROR', 'C9', path,
                           f'第 {i} 行 node_type={m.group(1)} 标注为「{m.group(2)}」，应为 {want}')

            # 整串枚举：从 node_type 后的第一个括号/冒号起，取连续的「数字+概念」对
            for h in _PAT_ENUM_HEAD.finditer(line):
                seg = line[h.end():h.end() + 60]
                pairs = _PAT_ENUM_PAIR.findall(seg)
                if len(pairs) < 3:
                    continue                      # 少于 3 对不视为枚举串，避免误报
                for num, name in pairs:
                    key, want = _lookup(name)
                    if key and want != int(num):
                        report('ERROR', 'C9', path,
                               f'第 {i} 行枚举串把 {num} 标为「{name}」，应为 {want}')

            # ⑤ 箭头映射：「node_type 由 userType 推导：1→2、2→3、3→4」
            # 两者取值恒等，任何"左右不等"的箭头对都是旧编号残留。
            # 这种句式不含概念词也不构成枚举串，前四种模式全都捕不到——
            # 真实缺陷 01:514 就是这样活过了两轮人工排查与一轮 C9。
            if re.search(r'node_?[Tt]ype', line) and re.search(r'user_?[Tt]ype', line):
                for am in re.finditer(r'(?<![\d])(\d)\s*(?:→|->|=>)\s*(\d)(?![\d])', line):
                    if am.group(1) != am.group(2):
                        report('ERROR', 'C9', path,
                               f'第 {i} 行出现 node_type↔user_type 的映射 {am.group(0)}，'
                               f'而两者取值恒等（契约 §5）；此类映射一律是旧编号残留')

            for m in _PAT_DDL_COMMENT.finditer(raw):
                key, want = _lookup(m.group(1))
                if key and want != int(m.group(2)):
                    report('ERROR', 'C9', path,
                           f'第 {i} 行注释「{m.group(1)}节点ID」却写 node_type={m.group(2)}，'
                           f'应为 {want}')


# ============ C12 承重论证的锚句必须存在（防"全局替换把前提也替换了"）

# 这些句子是某个设计决策的**理由**所在。理由被改坏时文档读起来依然通顺，
# 机械检查也查不出矛盾——只能反过来断言"这句话必须在"。
ANCHORS = [
    (os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
     '若用 `is_deleted` 这类 0/1 标志',
     '软删除用时间戳的论证前提。曾被全局替换写成「若用 `deleted_at`」，'
     '变成先说本方案有此缺陷、再说本方案解决了它，整段论证自毁'),
    (os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
     "`P = (ancestors = '' ? CAST(id AS CHAR) : CONCAT(ancestors,',',id))`",
     '前缀 LIKE 的空串分支。省掉它超管取全平台会静默返回空集'),
    (os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
     '`ref_user_id` **全部节点非空**',
     '「每个节点都是一个人」的落库形式，删掉它组织单元节点就会复活'),
    (os.path.join(DOCS, '02-数据库设计.md'),
     '绝不可写成',
     '移动节点维护 student_count 时的反面写法警示，防后人改回 FIND_IN_SET(列, 常量串)'),
]


def check_c12_anchors():
    """C12 承重论证的锚句必须逐字存在"""
    for path, anchor, why in ANCHORS:
        if anchor not in read(path):
            report('ERROR', 'C12', path, f'缺失承重锚句 `{anchor[:40]}`——{why}')


# ============ C13 承载平台级行（tenant_id=0）的表必须在契约 §2.9 登记

def check_c13_platform_rows(tables):
    """C13 凡 `tenant_id` 带 DEFAULT 0 的表，必须在契约 §2.9 的清单里逐表定案

    真实缺陷：sys_role 与 sys_role_menu 的内置角色行 tenant_id=0，而租户插件按
    `AND tenant_id = ?` 注入 → 租户用户加载 roles/perms 命中 0 行 → 全员零权限、
    系统开箱不可用。这是**不报错的故障**：接口返回 200、字段齐全，只是数组为空。

    检查两件事：
      1. DDL 里 `tenant_id ... DEFAULT 0` 的表，都在契约 §2.9 表格中出现；
      2. §2.9 登记的表，都确实存在于 DDL（防清单腐烂）。
    新增这类表时若忘了定案，本项会拦下来。
    """
    contract_path = os.path.join(DOCS, 'DESIGN-CONTRACT.md')
    contract = read(contract_path)
    m = re.search(r'^### 2\.9 .*?(?=^## 3\.)', contract, re.M | re.S)
    if not m:
        report('ERROR', 'C13', contract_path, '未能定位 §2.9 平台级行小节，检查标题格式')
        return
    section = m.group(0)
    registered = set(re.findall(r'^\|\s*`(\w+)`\s*\|', section, re.M))

    defaulted = set()
    for tname, t in tables.items():
        for _, line in t['lines']:
            if re.match(r"\s+`tenant_id`\s+BIGINT.*DEFAULT 0", line):
                defaulted.add(tname)
    for t in sorted(defaulted - registered):
        report('ERROR', 'C13', contract_path,
               f'`{t}.tenant_id` 有 DEFAULT 0（可承载平台级行），但未在 §2.9 清单中定案——'
               f'漏定案的后果是运行期静默零权限或静默越权，不会报错')
    for t in sorted(registered - set(tables)):
        report('ERROR', 'C13', contract_path, f'§2.9 登记了 `{t}`，但 DDL 中无此表')


# ====== C14 DDL 列集合 = 02-数据库设计逐表字段表（逐表逐列比对）

def check_c14_column_sets(tables):
    """C14 每张表的列集合，DDL 与 02-数据库设计的字段表必须完全一致

    真实缺陷：给 hw_answer_detail 补冗余列、给 vod_play_auth_log 换审计主体、
    给 vod_heartbeat_log 补 seeked 时，DDL 改了而文档字段表漏改（或反之）——
    C10 只看类型与注释语义，看不出"少了一整列"。而 02-数据库设计是实现方最常
    照着建表的文档，漏一列就是漏一个字段。

    只比对列名集合；类型与注释的语义一致性由 C10 负责。
    """
    doc_path = os.path.join(DOCS, '02-数据库设计.md')
    doc = read(doc_path)
    # 小节标题形如「#### 4.3.8 hw_answer_detail 逐题作答明细表」
    secs = list(re.finditer(r'^#### [\d.]+ (\w+) [^\n]*\n(.*?)(?=^#### |\Z)', doc, re.M | re.S))
    documented = {}
    for m in secs:
        name = m.group(1)
        if name not in tables:
            continue
        documented[name] = [c for c in re.findall(r'^\| (\w+) \| [A-Z]', m.group(2), re.M)]

    # 下限断言：小节标题或字段表格式一改，documented 会整体塌成空——那时本项只剩
    # 一片 WARN（不影响退出码），看起来像"文档没写全"而不是"检查器瞎了"。
    if len(documented) < len(tables) * 0.8:
        report('ERROR', 'C14', doc_path,
               f'仅解析出 {len(documented)} 张表的字段表（DDL 有 {len(tables)} 张），'
               f'小节标题或字段表格式可能已变，本项大面积失效')
        return

    for name in sorted(set(tables) - set(documented)):
        report('WARN', 'C14', doc_path, f'DDL 有表 `{name}`，02-数据库设计中未找到其字段表小节')

    for name, doc_cols in sorted(documented.items()):
        ddl_cols = tables[name]['cols']
        only_ddl = [c for c in ddl_cols if c not in doc_cols]
        only_doc = [c for c in doc_cols if c not in ddl_cols]
        if only_ddl:
            report('ERROR', 'C14', doc_path,
                   f'`{name}`：DDL 有而文档字段表缺 {only_ddl}')
        if only_doc:
            report('ERROR', 'C14', DDL_PATH,
                   f'`{name}`：文档字段表有而 DDL 缺 {only_doc}')


# ============ C17 JSON 示例内部自洽（nodeType / userType / childCount）

def _walk_json(obj):
    """深度优先产出所有 dict 对象"""
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from _walk_json(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _walk_json(v)


def check_c17_json_examples():
    """C17 响应示例里的 nodeType 必须自洽

    真实缺陷：node_type 重编号后，01 分册四处教师示例（userType=2）仍写 nodeType=3、
    02 分册示例树把管理员写成 2、教师写成 3、学生写成 4，且一个 nodeType=3 的节点
    带着 childCount=32。示例是实现方交叉验证时最信任的证据——十余处互相印证的旧编号，
    会让人越看越确信旧编号才是对的。

    三条断言（都只在字段同时出现时才生效，避免误报）：
      1. 同一对象内 userType 与 nodeType 并存 → 必须相等（契约 §5 恒等）
      2. nodeType == 3（学生）→ childCount 必须为 0（学生是叶子）
      3. nodeType >= 4 → 一律错误（现编号只到 3）

    扫描范围原为 `API_FILES`，于是 `04-实施计划.md` 与 `05-工程结构.md` 进了
    `MD_FILES` 之后本项对它们**结构上仍然看不见**——与 C3 当年那次是同一物种
    （见 README「扫描范围 ≠ 覆盖」）。改为 `MD_FILES` 后全库 0 条新增报错，
    零代价；注入 `userType=2` 配 `nodeType=3` 可正常报出。
    """
    for path in MD_FILES:
        text = read(path)
        for m in re.finditer(r'```json\n(.*?)```', text, re.S):
            body = '\n'.join(l for l in m.group(1).split('\n')
                              if not re.match(r'^\s*(GET|POST|PUT|DELETE)\s', l))
            try:
                data = json.loads(body)
            except Exception:
                continue                      # 解析失败由 C7 负责报
            line_no = text[:m.start()].count('\n') + 1
            for o in _walk_json(data):
                nt = o.get('nodeType')
                if not isinstance(nt, int):
                    continue
                name = o.get('nodeName') or o.get('realName') or o.get('username') or '?'
                ut = o.get('userType')
                if isinstance(ut, int) and ut != nt:
                    report('ERROR', 'C17', path,
                           f'第 {line_no} 行起的示例中「{name}」userType={ut} 但 nodeType={nt}，'
                           f'契约 §5 规定二者恒等')
                cc = o.get('childCount')
                if nt == 3 and isinstance(cc, int) and cc != 0:
                    report('ERROR', 'C17', path,
                           f'第 {line_no} 行起的示例中「{name}」nodeType=3（学生）却有 '
                           f'childCount={cc}——学生必须是叶子，该示例是一棵非法树')
                if nt >= 4:
                    report('ERROR', 'C17', path,
                           f'第 {line_no} 行起的示例中「{name}」nodeType={nt}，'
                           f'现编号只到 3（0超管 1管理员 2教师 3学生）')


# ============ C18 端点路径存在性（正文引用 ↔ 接口目录）

# 历史陈述：必须点名已删除的端点才能说清"这个定时任务替换了什么"
C18_ALLOW = [
    ('/api/v1/vod/callback/{provider}', '取代了原先的'),   # 03-03 §7.2 改造理由
    # `vod_video.decrypt_key_uri` 的列注释里指着已退役的 /api/v1/vod/decrypt-key
    #（接口 29，见 _RETIRED_INTERFACES）。**这一条不能靠改文字消掉**：那句话在
    # V202608120000__baseline.sql 里，而基线是【冻结内容】（契约 §7.3：已发布脚本永不修改）——
    # 改它会变 Flyway 校验和，结果是启动失败，不是一条干净的红。
    # 上下文键刻意取列名而不是「已删除」这类措辞：列名只出现在【正在描述这一列】的行上，
    # 别处再写这个死端点照样报错，C18 的牙齿只在这一列上收起来。
    ('/api/v1/vod/decrypt-key', 'decrypt_key_uri'),
]

_DIR_ROW = re.compile(
    r'^\|\s*[\d.]+\s*\|[^|]*\|\s*(GET|POST|PUT|DELETE|PATCH)\s*\|\s*`([^`]+)`')
_PATH_REF = re.compile(r'(GET|POST|PUT|DELETE|PATCH)\s+(/api/v1/[^\s`"\'）)\],；。]*)')
# 不带方法的裸路径引用。DDL 列注释、契约路由表、章节标题都是这个形态
_BARE_PATH = re.compile(r'(?<![A-Za-z/])(/api/v1/[^\s`"\'）)\],；。]*)')

# DDL 不进 MD_FILES（那会让 C7 markdown 健康度、C8 禁用词等一堆规则对着 SQL 空转），
# 但它是 Flyway 基线、列注释里指着真实端点（decrypt_key_uri 指向 /vod/decrypt-key），
# 注释写错了将来是要被人当依据的——单独作为 C18 的扫描源。
C18_EXTRA_SOURCES = [DDL_PATH]


def check_c18_endpoint_paths():
    """C18 正文里写出的每个 `/api/v1/...` 都必须在某分册的接口目录中存在

    这是第一条跨"文档 ↔ 接口清单"的**存在性**检查。C5 只数总量、C11 只校验编号
    引用，路径没人管——references/README.md 曾同时写着一个已删除的端点
    (`POST /api/v1/vod/callback/{provider}`) 和一个少了 `/videos` 段的错路径，
    两个都是人眼发现的。

    实现要点：目录里是**路径模板**（`/org/nodes/{id}`），正文示例里是**具体值**
    （`/org/nodes/1960000000000000010`）。整串集合比对会把 87 条合法请求示例判成
    死端点——第一次跑就喊 88 次狼来了，接着必然被人加一条"跳过整个 API 目录"的
    豁免，规则就废了。改为**逐段对齐**：先按段数分组，再逐段判断，目录侧是 `{xxx}`
    则该段任意匹配。这样雪花 ID、`tenant-configs/{configKey}` 这类字面量参数值
    一并消掉，零豁免、零启发式，只留真命中。

    段数参与匹配是这条规则的牙齿：漏写 `/videos` 段会改变段数，直接落选。

    **裸路径分支**：只认 `METHOD /path` 会漏掉不写方法的引用——DDL 的
    `decrypt_key_uri` 列注释、契约 §6.2 路由表、各分册章节标题都是这个形态。
    裸引用不校验方法，只校验路径存在。放过的是**路由前缀**，判据不是"段数 ≤ 3"
    （`## 8. 日志（/api/v1/system/logs）` 有 4 段，真端点是 `/logs/login`），而是
    **它是某个已登记路径的段级前缀**——命名了一棵含真实端点的子树，就是分组不是
    死端点。顺序上必须先判命中、不中再判前缀：`/org/nodes` 本身是端点、同时又是
    `/org/nodes/{id}` 的前缀，反过来会把它短路进前缀桶而不再校验。

    **已知漏报，不要"修"**：固定段打错、且错值恰好落在另一条同段数登记路径的占位符
    位置时不报（`/org/nodes/treee` 被 `/org/nodes/{id}` 接住）。要抓它必须判断"这个值
    像不像 ID"——几位数字算 ID？带字母算不算？`complete_rate_threshold` 这种字面量
    参数值怎么办？——正是删掉这类启发式才换来零豁免零误报。暴露面算得清：159 条登记
    端点里只有 2 条有此风险（`org/nodes/tree`、`org/students/tags`）。详见 README
    「已知漏报」一节。
    """
    registry, reg_by_len = set(), {}
    for path in MD_FILES:
        for line in read(path).split('\n'):
            m = _DIR_ROW.match(line)
            if m:
                registry.add((m.group(1), m.group(2).split('?')[0].rstrip('/')))
    for meth, p in registry:
        reg_by_len.setdefault((meth, p.count('/')), []).append(p.split('/'))

    if len(registry) < 100:      # 目录解析失效时不要静默放行
        report('ERROR', 'C18', REGISTRY,
               f'仅解析出 {len(registry)} 条目录路径，接口目录表格式可能已变，本项失效')
        return

    all_paths = [p.split('/') for p in {p for _, p in registry}]

    def aligns(cand, segs):
        return all(c.startswith('{') or c == s for c, s in zip(cand, segs))

    def known(segs, meth=None):
        if meth:
            return any(aligns(c, segs) for c in reg_by_len.get((meth, len(segs) - 1), []))
        return any(len(c) == len(segs) and aligns(c, segs) for c in all_paths)

    def is_route_prefix(raw, segs):
        if raw.endswith('/') or '*' in raw:
            return True
        return any(len(c) > len(segs) and aligns(c, segs) for c in all_paths)

    for path in MD_FILES + C18_EXTRA_SOURCES:
        for i, line in enumerate(read(path).split('\n'), 1):
            spans = []
            for m in _PATH_REF.finditer(line):
                spans.append(m.span(2))
                meth, p = m.group(1), m.group(2).split('?')[0].rstrip('/')
                if known(p.split('/'), meth):
                    continue
                if any(ref in line and ctx in line for ref, ctx in C18_ALLOW):
                    continue
                report('ERROR', 'C18', path,
                       f'第 {i} 行引用了接口目录中不存在的端点：{meth} {p}')
            for m in _BARE_PATH.finditer(line):
                if any(a <= m.start(1) < b for a, b in spans):
                    continue                 # 已由带方法分支处理
                raw = m.group(1)
                segs = raw.split('?')[0].rstrip('/').split('/')
                if known(segs) or is_route_prefix(raw, segs):
                    continue
                if any(ref in line and ctx in line for ref, ctx in C18_ALLOW):
                    continue
                report('ERROR', 'C18', path,
                       f'第 {i} 行引用了接口目录中不存在的路径：{"/".join(segs)}')


# ================================ C19 F 清单编号唯一 + 状态唯一

# 允许出现在 F 行编号后面的状态词。**穷举**——写了别的词会被当成"没有状态"，
# 而那正是要抓的一种写法退化（下一个人拿"待研究""搁置"当状态，工具看不见）。
F_STATUS_WORDS = ('未定案', '已定案', '已关闭', '已修正', '原则已定案')

def check_c19_f_registry():
    """
    C19 `04-实施计划.md` §E 的 F 清单：同一编号只允许一行、只允许一个状态

    【为什么需要这条 —— 它补的是 C1~C18 的一个完整盲区】
    十八条检查覆盖 DDL / 枚举 / 错误码 / 字段长度 / 接口计数 / 表清单 /
    Markdown 健康度 / 禁用词 / 类型绑定 / 锚句 / 平台级行 / 列集合 / 心跳 /
    端点路径，**没有任何一条管 F 清单**。而 F 清单恰恰是模块 08 / 09 / 11
    将来要查"还有什么没决"的那份东西。

    【真实缺陷】模块 05 交付时，F-25 在清单里出现了两行且状态互相矛盾：
    行 1650「**未定案** …切面是模块 05 的交付物，本轮模块 05 被跳过」，
    行 1658「**✅ 已关闭（模块 05 落地）**」。**未定案那行还排在前面** ——
    下一个人翻清单找未决项，先读到的是"切面还没做"。
    而这个矛盾**对所有工具都是隐形的**：18 条检查一条都不看 F 清单，
    Markdown 也照样渲染。这正是本项目反复点名的「不报错的故障」。

    【三条判定，前两条是 ERROR、第三条只 WARN】
      ① 同一个 F 编号只允许出现一行；
      ② 同一个 F 编号只允许有一个状态（`**F-25**<br>**未定案**` 里的"未定案"）；
      ③ 编号连续性只告警 —— **F-8 是历史缺口，不是错误**（04 §E 已登记），
         把它判成 ERROR 会让这条检查从第一天起就红，最后没人看。

    【它会不会红 —— 已做变异验证，下面是实测输出】
      A 复制一行 `F-30`（同号两行、状态相同）：
        ❌ 1 错误 —— "F-30 在 F 清单里出现 2 行"
      B 复制 `F-30` 并把状态改成「未定案」（同号两状态，即 F-25 那次的真实形态）：
        ❌ 2 错误 —— 出现 2 行 + "同时标着 2 个状态：已定案、未定案"
      C 删掉 `F-30` 制造缺号：
        ⚠️  0 错误 / 1 警告 —— 缺号只告警，不把 F-8 那类历史缺口判成错误
      恢复后：✅ 0 错 0 警。
    """
    path = os.path.join(DOCS, '04-实施计划.md')
    text = read(path)

    # F 行形如：| **F-25**<br>**✅ 已关闭（模块 05 落地）** | …
    #          | **F-1** | …                （早期条目没有状态段）
    rows = re.findall(r'^\|\s*\*\*(F-\d+)\*\*([^|]*)\|', text, re.M)
    if not rows:
        report('ERROR', 'C19', path,
               'F 清单一行都没扫到 —— 本检查正在空转，请确认 §E 的表格格式是否变了')
        return

    seen = {}
    for num, tail in rows:
        status = None
        for word in F_STATUS_WORDS:
            if word in tail:
                # 取最长匹配（"原则已定案"含"已定案"，不取长的会判成两个状态）
                if status is None or len(word) > len(status):
                    status = word
        seen.setdefault(num, []).append(status)

    for num in sorted(seen, key=lambda n: int(n.split('-')[1])):
        statuses = seen[num]
        if len(statuses) > 1:
            report('ERROR', 'C19', path,
                   f'{num} 在 F 清单里出现 {len(statuses)} 行 —— '
                   f'同一编号只允许一行（读清单的人会先读到排在前面的那行）')
        distinct = {s for s in statuses if s is not None}
        if len(distinct) > 1:
            report('ERROR', 'C19', path,
                   f'{num} 同时标着 {len(distinct)} 个状态：{"、".join(sorted(distinct))} '
                   f'—— 同一编号只允许一个状态')

    # ③ 连续性只告警：F-8 是历史缺口（04 §E 已登记），不是错误。
    #    【曾经这里还有一个 RESERVED_GAPS 常量，值是 49~69】—— 模块 09/10 并行开发时
    #    编号分段必然留空号，而 21 条 WARN 会让「19 项 0 错 0 警」这条基线当场失效。
    #    它被刻意写成一个【会过期的常量】而不是永久豁免：永久豁免等于此后真在 49~69 之间
    #    漏登记一条 F，C19 也不会喊。两个模块合并后已按 F-74 的到期动作删除，
    #    49~69 重新进入覆盖范围。下次再有并行开发照这个形态做：会过期 + 两处各写一句到期条件。
    numbers = sorted(int(n.split('-')[1]) for n in seen)
    exempt = {8}
    gaps = [n for n in range(1, numbers[-1] + 1) if n not in numbers and n not in exempt]
    if gaps:
        report('WARN', 'C19', path,
               f'F 编号缺号：{", ".join("F-%d" % g for g in gaps)}'
               f'（F-8 是已登记的历史缺口，F-49~F-69 是并行分段保留区，均不计入）')



# ============ C21 实施方案文档内部的小节引用不得悬空

def check_c21_plan_section_refs():
    """C21 `docs/模块*-实施方案-*.md` 里【本文件内部】的小节引用不得悬空

    【为什么需要这条 —— 它补的是一个真实吃过的亏】
    2026-08-22 写模块 13 方案时，一次编辑被外部还原、而实施方没有复核就提交，
    结果是：§7 里写着「见 §6.6 第 6 条」，而 §6 只到 6.5 ——
    那个引用指向一个**不存在的章节**，而它本该说明的内容（会话级上限怎么定义、
    Redis 挂了怎么办、撞上限返回什么码）**一个字都没写**。
    是需方读文档时发现的，检查器当时一条都没报。

    【怎么区分内部引用与跨文档引用】
    带出处的（`契约 §6.4`、`03-03 §8.3.1`、`PRD F2-7`）跳过，只校验**裸写**的 `§X.Y`。
    为此把方案里 3 处裸写的跨文档引用补上了出处 —— 那本来也是可读性 bug：
    模块 13 方案里裸写 `§8.1`，读者会去本文件找，而本文件的 §8.1 是「Redis」。

    【为什么只扫实施方案，不扫全部文档】
    契约 / PRD / API 分册之间大量互相引用，同文件内解析必然误报，
    而**一条会误报的检查等于没有检查**。实施方案是自包含的，范围收窄换来零误报。
    """
    prefix = re.compile(r'(契约|DESIGN-CONTRACT|PRD|0[1-5]-|模块\s*\d+|分册)\s*$')
    for path in sorted(glob.glob(os.path.join(DOCS, '模块*-实施方案-*.md'))):
        text = read(path)
        have = set(re.findall(r'^#{2,4}\s*([0-9]+(?:\.[0-9]+)*)[\.、 ]', text, re.M))
        have |= {h.split('.')[0] for h in have}
        for m in re.finditer(r'§\s*([0-9]+(?:\.[0-9]+)*)', text):
            if prefix.search(text[max(0, m.start() - 16):m.start()]):
                continue                      # 带出处 = 跨文档引用，不校验
            ref = m.group(1)
            if ref not in have:
                line = text[:m.start()].count('\n') + 1
                report('ERROR', 'C21', path,
                       f'第 {line} 行写「§{ref}」，但本文件里没有这个小节'
                       f'（现有：{sorted(have)}）')


# ============ C20 §A 核对行的逐模块数 = §B 该模块「涉及接口」表的行数

def check_c20_module_interface_counts():
    """C20 `04-实施计划.md` §A 核对行里每个模块的数字，必须 == §B 该模块表的行数

    【它补的是 §A 那一行自己的盲区，而那个盲区已经漏掉过一个接口】
    §A 的核对行自称「验证没有接口无人认领、也没有模块认领了不存在的接口」，
    但它只校验【求和】：36 + 52 + 34 + 31 + 8 = 161 恒成立，
    与每个模块的数字对不对【无关】。

    模块 11 落地时发现：原文写 `07:21 + 11:6`，而 §B 模块 07 的表只有 20 行、
    模块 11 的表只有 6 行 —— 03-02 §6.12（接口 52 归属变更影响面预检）逐字写着
    「签名在模块 07 敲定，实现落在模块 11」，于是 52 号【被计数认领、却不在任何
    模块的实现清单里】，全库无人实现它。而这件事对所有工具都是隐形的：
    C5 只比对目录/正文/README 三处计数，19 条检查一条都不看 §A 的逐模块拆分。

    【判定】对 §A 里形如 `06:6` 的每一项，找到 §B 中 `### 06 …` 那一节的
    「涉及接口（分册，N 个）」标题与其下的表格行数，三者必须一致：
      · 标题里的 N == 表格行数（写了 N 却少列一行）
      · §A 的数字 == N（§A 与 §B 各说各的）

    【空转守卫】一个模块都没扫到就报违规。§A 那一行或 §B 的标题格式一变，
    本条会静默变成永远为绿 —— 而它守的东西恰恰是「没人注意到的不一致」。

    【它会不会红 —— 已做变异验证，下面是实测输出】
      把 §A 的 `11:7` 改成 `11:6`：
        ❌ 1 错误 —— "§A 核对行说模块 11 有 6 个接口，而 §B 该模块表列了 7 行"
      把 §B 模块 11 表里删掉一行（标题仍写 7 个）：
        ❌ 2 错误 —— §B 标题与表行数不符 + §A 与 §B 不符
      恢复后：✅ 0 错 0 警。
    """
    path = os.path.join(DOCS, '04-实施计划.md')
    text = read(path)

    # §A 核对行：抓 `06:6`、`07:20` 这类
    m = re.search(r'^\*\*接口分配核对\*\*：(.+?)。', text, re.M)
    if not m:
        report('ERROR', 'C20', path, '未定位到 §A「接口分配核对」那一行，本项失效')
        return
    declared = {mod: int(n) for mod, n in re.findall(r'(\d{2}):(\d+)', m.group(1))}
    if not declared:
        report('ERROR', 'C20', path, '§A 核对行里一个 `模块:数量` 都没解析出来，本项失效')
        return

    # §B 每个模块的「涉及接口（…，N 个）」标题 + 其下表格行数
    actual = {}
    for section in re.finditer(r'^### (\d{2}) .*?(?=^### \d{2} |\Z)', text, re.S | re.M):
        mod = section.group(1)
        body = section.group(0)
        head = re.search(r'\*\*涉及接口（[^）]*?，(\d+) 个）\*\*', body)
        if not head:
            continue
        # 表体行数：各模块的引用写法不一样（有的是「03-02 接口 37 …」，
        # 有的是「§7.1 | 上传文件 | …」），所以【不匹配引用文本】，
        # 只数标题之后第一张表的表体行 —— 跳过表头与 |---| 分隔行，遇到非表格行即止。
        # 绑格式的判据在这里会漏数成 0，而 0 恰好长得像「这个模块没有接口」
        rows = 0
        seen_header = False
        for line in body[head.end():].split('\n'):
            stripped = line.strip()
            if not stripped.startswith('|'):
                if seen_header:
                    break        # 表结束
                continue         # 标题与表之间的空行
            if set(stripped) <= set('|- :'):
                seen_header = True   # |---|---| 分隔行
                continue
            if not seen_header:
                continue         # 表头行
            rows += 1
        actual.setdefault(mod, []).append((int(head.group(1)), rows))

    if not actual:
        report('ERROR', 'C20', path,
               '§B 里一个「涉及接口（…，N 个）」标题都没扫到 —— 本检查正在空转')
        return

    for mod, entries in sorted(actual.items()):
        head_total = sum(head for head, _ in entries)
        row_total = sum(rows for _, rows in entries)
        if head_total != row_total:
            report('ERROR', 'C20', path,
                   f'§B 模块 {mod} 的标题写着 {head_total} 个接口，而表格只有 {row_total} 行')
        if mod in declared and declared[mod] != row_total:
            report('ERROR', 'C20', path,
                   f'§A 核对行说模块 {mod} 有 {declared[mod]} 个接口，'
                   f'而 §B 该模块表列了 {row_total} 行 —— '
                   f'差额那几个接口【被计数认领、却不在任何实现清单里】，'
                   f'而 §A 那一行自称就是防这件事的')


# ============ C15 心跳请求体签名三处一致（契约 §6.4 / PRD F2-7 / 03-03）

def check_c15_heartbeat_signature():
    """C15 心跳请求体的字段集与字段序，三处必须逐字一致

    契约把心跳称作"最高频接口，签名固定"，正因为固定，改动只在实现前便宜——
    而它已经改过两次（补 seeked 解 seek 复看被误判、补 sessionId 解多端并发永久归零）。
    每改一次就要同步三个文件，漏一处就是前后端对不上，且这类不一致直到联调才暴露。

    契约与 PRD 里是一行 `Body: {...}` 字面量，03-03 里是参数表，形态不同故分别解析。
    """
    got = {}

    for label, path in [('契约 §6.4', os.path.join(DOCS, 'DESIGN-CONTRACT.md')),
                        ('PRD F2-7', os.path.join(DOCS, '01-PRD-产品需求文档.md'))]:
        for line in read(path).split('\n'):
            if line.startswith('Body: {"lessonId"'):
                got[label] = re.findall(r'"(\w+)":', line)
                break

    vol = os.path.join(DOCS, '03-API接口文档', '03-课程与视频.md')
    text = read(vol)
    anchor = '**请求参数（Body，契约 6.4 固定签名）**'
    if anchor in text:
        seg = text[text.index(anchor):][:2500]
        got['03-03 参数表'] = re.findall(r'^\| (\w+) \| \w+ \| 是 \|', seg, re.M)

    if len(got) < 3:
        report('ERROR', 'C15', os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
               f'只解析到 {sorted(got)} 三处中的 {len(got)} 处心跳签名，检查锚点格式')
        return

    base_label, base = next(iter(got.items()))
    for label, fields in got.items():
        if fields == base:
            continue
        missing = [f for f in base if f not in fields]
        extra = [f for f in fields if f not in base]
        if missing or extra:
            report('ERROR', 'C15', os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
                   f'心跳签名字段集不一致：{label} vs {base_label}，'
                   f'缺 {missing}、多 {extra}')
        else:
            report('WARN', 'C15', os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
                   f'心跳签名字段序不一致：{label} 为 {fields}，{base_label} 为 {base}')


# ====== C16 心跳校验规则编号 PRD F2-7 ↔ 03-03 §8.3.1 一一对应

# 同号规则在两份文档里措辞可以不同，但必须谈的是同一件事。
# 这里给每条规则登记若干关键词，同号两侧只要各命中其一即认为对齐。
RULE_KEYWORDS = {
    1: ['凭证'],
    2: ['间隔'],
    3: ['拖拽'],
    4: ['倍速'],
    5: ['异常', '非法', '丢弃'],
    6: ['推进', '一致性'],
    7: ['并发', '课时数'],
    8: ['封顶', '时长上限'],
    9: ['会话'],
}


def check_c16_heartbeat_rule_numbers():
    """C16 心跳校验规则的编号，PRD F2-7 与 03-03 §8.3.1 必须一一对应

    真实缺陷：PRD F2-7 曾把"服务端拒绝规则"与"处理流程"混在一个列表里编号，
    与 8.3.1 只有 2/6/7/8 四条碰巧对上。最危险的是 9 号——两边都是拒绝规则、
    都编号 9，一边是"参数越界"一边是"多端会话冲突"，而两者的处置动作完全相反。

    这已经不只是读着别扭：`vod_heartbeat_log.reject_rule` 把规则编号**持久化进了
    审计表**，该表按月分区保留 6 个月、累计留存数年。编号一旦不一致，运维查到
    reject_rule=N 对着 PRD 读就会读成另一条规则，且历史日志无法回溯修正。

    另外校验 reject_rule 注释里枚举的取值都能在 8.3.1 中找到对应的拒绝规则。
    """
    prd_path = os.path.join(DOCS, '01-PRD-产品需求文档.md')
    vol_path = os.path.join(DOCS, '03-API接口文档', '03-课程与视频.md')

    # --- 03-03 §8.3.1 的规则表：| N | **规则名** | ... ---
    vol = read(vol_path)
    m = re.search(r'^#### 8\.3\.1 .*?(?=^#### 8\.3\.2)', vol, re.M | re.S)
    if not m:
        report('ERROR', 'C16', vol_path, '未能定位 §8.3.1 规则表，检查标题格式')
        return
    api_rules = {int(n): name for n, name in
                 re.findall(r'^\|\s*(\d)\s*\|\s*\*\*([^*]+)\*\*', m.group(0), re.M)}

    # --- PRD F2-7 的「服务端校验规则」列表：N. **规则名**：... ---
    prd = read(prd_path)
    s = re.search(r'\*\*服务端校验规则（.*?(?=\*\*心跳处理流程)', prd, re.S)
    if not s:
        report('ERROR', 'C16', prd_path,
               '未能定位 F2-7「服务端校验规则」列表——校验规则与处理流程必须分成两个列表，'
               '混编则编号无法与 §8.3.1 对齐')
        return
    prd_rules = {int(n): name for n, name in
                 re.findall(r'^(\d)\. \*\*([^*]+)\*\*', s.group(0), re.M)}

    if set(api_rules) != set(prd_rules):
        report('ERROR', 'C16', prd_path,
               f'规则编号集合不一致：PRD {sorted(prd_rules)} vs §8.3.1 {sorted(api_rules)}')
        return

    for n in sorted(api_rules):
        kws = RULE_KEYWORDS.get(n, [])
        a_hit = any(k in api_rules[n] for k in kws)
        p_hit = any(k in prd_rules[n] for k in kws)
        if not (a_hit and p_hit):
            report('ERROR', 'C16', prd_path,
                   f'规则 {n} 两侧含义对不上：§8.3.1「{api_rules[n]}」vs PRD「{prd_rules[n]}」'
                   f'（期望关键词之一：{kws}）')

    # --- reject_rule 注释枚举的取值必须都是 8.2.1 里真实存在的规则号 ---
    ddl = read(DDL_PATH)
    c = re.search(r"`reject_rule`[^\n]*COMMENT\s+'([^']*)'", ddl)
    if not c:
        report('ERROR', 'C16', DDL_PATH, '未找到 reject_rule 列注释')
        return
    declared = {int(x) for x in re.findall(r'(?<![\d.])([1-9])(?=[/=、])', c.group(1))}
    for n in sorted(declared - set(api_rules)):
        report('ERROR', 'C16', DDL_PATH,
               f'reject_rule 注释声明取值 {n}，但 §8.3.1 无该编号规则')


# ============================== C10 列类型与注释语义一致（DDL）

_COL_DEF = re.compile(
    r"^\s+`(?P<col>\w+)`\s+(?P<type>[A-Z]+(?:\(\d+(?:,\d+)?\))?)"
    r"(?P<rest>.*?)(?:COMMENT\s+'(?P<comment>(?:[^']|'')*)')?\s*,?\s*$")

BOOL_WORDS = ('0否 1是', '0否1是', '0=否', '0 否 1 是')


def check_c10_type_semantics(tables):
    """C10 DDL 列的数据类型必须与其注释描述的语义相符

    真实缺陷：deleted_at 从 TINYINT(0/1) 改为 BIGINT 毫秒时间戳后，22 张表的列注释
    仍写「0否 1是」，另外 23 张写了新语义——几乎对半开。按字面注释实现就是写 1 而
    不是时间戳，整个改造的收益（同一业务键容纳多条已删除行）完全落空，还退化成一个
    更难读的 0/1。类型改了、注释没跟上，是重构里最不容易被发现的一类残留。
    """
    for tname, t in tables.items():
        seen_deleted_at = False
        for n, line in t['lines']:
            m = _COL_DEF.match(line)
            if not m:
                continue
            col, typ = m.group('col'), m.group('type').upper()
            comment = m.group('comment') or ''
            if typ.startswith('BIGINT') and any(w in comment for w in BOOL_WORDS):
                report('ERROR', 'C10', DDL_PATH,
                       f'{tname}.{col} 第 {n} 行类型 {typ}，注释却是布尔语义「{comment[:26]}」')
            if typ.startswith('TINYINT') and ('毫秒时间戳' in comment):
                report('ERROR', 'C10', DDL_PATH,
                       f'{tname}.{col} 第 {n} 行类型 {typ}，注释却称其为毫秒时间戳')
            if col == 'deleted_at':
                seen_deleted_at = True
                if not typ.startswith('BIGINT'):
                    report('ERROR', 'C10', DDL_PATH,
                           f'{tname}.deleted_at 第 {n} 行类型为 {typ}，'
                           f'契约 §2.2 要求 BIGINT 毫秒时间戳')
                elif '时间戳' not in comment:
                    report('WARN', 'C10', DDL_PATH,
                           f'{tname}.deleted_at 第 {n} 行注释未说明其为时间戳：「{comment[:26]}」')
        del seen_deleted_at

    # DDL 之外，02-数据库设计的逐表字段表也逐列复述了类型，同样会脱节
    # （实际发生过：DDL 全部改为 BIGINT 时间戳后，字段表 40 行仍写 TINYINT + 0否 1是）
    db_doc = os.path.join(DOCS, '02-数据库设计.md')
    for i, line in enumerate(read(db_doc).split('\n'), 1):
        m = re.match(r'\|\s*(\w+)\s*\|\s*([A-Z]+(?:\(\d+(?:,\d+)?\))?)\s*\|'
                     r'\s*[NY]\s*\|[^|]*\|([^|]*)\|', line)
        if not m:
            continue
        col, typ, desc = m.group(1), m.group(2).upper(), m.group(3)
        if typ.startswith('BIGINT') and any(w in desc for w in BOOL_WORDS):
            report('ERROR', 'C10', db_doc,
                   f'第 {i} 行 {col} 类型 {typ}，说明却是布尔语义「{desc.strip()[:26]}」')
        if col == 'deleted_at' and not typ.startswith('BIGINT'):
            report('ERROR', 'C10', db_doc,
                   f'第 {i} 行 deleted_at 写作 {typ}，DDL 中为 BIGINT 毫秒时间戳')


# ================== C11 接口编号交叉引用完整性（分册内 + 跨分册）

_TOC_ROW = re.compile(r'^\| (\d+) \| ([^|]+?) \| (GET|POST|PUT|DELETE|PATCH) \| `([^`]+)`', re.M)


# 已退役的接口编号：登记在册的号【可以在目录里缺席，也可以在正文里被继续引用】。
#
# 【这不是把连续性判据放宽】——目录表缺号仍然是 ERROR，只是登记过的那几个号例外。
# C11 当初抓到的那类事故（插入一个接口后 §2.3 整段编号错位一位，字面读出来是
# 「教师可调转交管理员」）里【每个号都移动了、一个都不在清单里】，本判据照样响。
#
# 【每一条必须写明为什么退役】——没有解释的豁免会稀释信号，
# 与 F-87 登记为「已撤回」而不是留白是同一条纪律。
#
# 【为什么退役而不是重编号】：backend 的 Java 注释里有近 600 处「接口 N」，
# 而一致性脚本只扫 Markdown、约定脚本一处不管 ——【没有任何检查在核对它们】。
# 「改漏了会报错」对文档那几十处成立，对代码那几十处不成立；而漏改的后果是
# 注释写着「接口 42」、42 号仍然存在仍是真接口、只是变成了另一个 ——
# 不报错、不断链，只是安静地把人指错地方（本项目命名过的失效模式⑦）。
#
# 【它会不会红 —— 已做变异验证，下面是实测输出】
#   M31 手工从 02 分册目录里再删一个【未登记】的号（45 新建权限模板）：
#     ❌ 3 错误 —— "目录表编号不连续：… 共 50 项（已登记退役：[40]）"
#                + 04-实施计划.md 两处跨册引用「接口 45」失效
#     也就是说豁免【只对登记在册的那一个号生效】，别的缺口照常报。
#   M32 把 40 从本清单里拿掉：
#     ❌ 11 错误 —— 连续性 + 本册 3 处「接口 40」引用 + 跨册 4 处
#     也就是说这个清单是【真的在被读】，不是写了个没人用的常量。
#   两者恢复后：✅ 0 错 0 警。
_RETIRED_INTERFACES = {
    # 02-组织机构
    '02-组织机构': {
        40: '修改授权有效期 —— 需方 2026-08-21 定案「授权一律永久有效」，'
            '接口随之删除、§9.4 保留为墓碑小节（04-实施计划.md F-103）',
    },
    # 03-课程与视频
    '03-课程与视频': {
        29: '获取解密密钥 —— 加密路线由「HLS 标准加密 + 自建密钥接口」改为'
            '「阿里云私有加密 + VidAuth」，解密由播放器 SDK 完成、密钥由点播服务托管，'
            '「学生端向我们取密钥」这个动作不再存在；接口随之删除、'
            '§8.2 保留为墓碑小节（04-实施计划.md F-112）',
    },
}


def _retired_of(vol):
    """该分册已登记退役的接口号集合。"""
    return set(_RETIRED_INTERFACES.get(vol, {}))


def check_c11_interface_refs():
    """C11 正文里的「接口 N」必须指向本分册目录表中真实存在的编号

    真实缺陷：删除失效的"新建节点""删除节点"两个接口后，02 分册全部 48 个接口
    要重新编号，而正文里有 80 处「接口 N」交叉引用、还有 30 行错误码登记表用
    **不带"接口"前缀的裸编号列**（`9 / 10 / 13 / 14 / 18`）记录触发接口——后者极易
    整块漏掉。编号错了不会有任何症状，只会把读文档的人指到另一个接口上。
    """
    # 先建"分册名 → 目录"总表，供跨册引用校验。
    # 此前 C11 对没有接口目录的文件（00-通用约定）整份 continue 跳过，
    # 于是它里面的跨册引用**从未被校验过**——白名单那行指向"03-课程与视频接口 30"，
    # 而 30 是播放心跳上报、29 才是解密密钥。同类问题还有 01 分册指向 02 §7.3 的编号。
    volume_tocs = {}
    for path in API_FILES:
        m = re.match(r'(0\d-[^.]+)\.md', os.path.basename(path))
        toc_ = {int(x.group(1)): x.group(2).strip() for x in _TOC_ROW.finditer(read(path))}
        if m and toc_:
            volume_tocs[m.group(1)] = toc_

    # 下限断言：目录解析不出来时，下面的跨册校验会走 `vol not in volume_tocs: continue`
    # 静默放行——检查器失效而不自知。凡"先解析出一个集合再比对"的检查都该声明
    # "我现在看不见了"（同 C18 的 len(registry) < 100）。
    #
    # 期望值不能从 _TOC_ROW 反推——那是用被守护的解析器去推导守护条件，格式一坏
    # 两边同时归零、断言恒真（写这条时先踩了一次）。也不写死"应为 5"：01 与 05
    # 分册用的是小节式编号（`| 1.1 |`、`| 4.1 |`），_XREF 的「接口 N」形态本就不
    # 适用于它们，全库指向这两册的此类引用为 0。写死的是**分册名**——这是关于当前
    # 文档集的事实。新增整数编号的分册时要往这里加一行；某册改用小节式编号时这条
    # 会响，那正是该由人来确认的时刻。
    _INT_NUMBERED = {'02-组织机构', '03-课程与视频', '04-题库与作业'}
    if _INT_NUMBERED - set(volume_tocs):
        report('ERROR', 'C11', REGISTRY,
               f'以下分册应有整数编号目录却未解析出，跨册引用校验对其失效：'
               f'{"、".join(sorted(_INT_NUMBERED - set(volume_tocs)))}')

    # 数字册号 → 分册名。文档里长期并存两种指代分册的写法：分册名（「02-组织机构」）与
    # 数字册号（「03-02」，其中 `03-` 是 docs/03-API接口文档/ 这一层的目录号、后两位才是
    # 分册号），而 volume_tocs 的键只来自文件名即分册名。只认其中一种就会让另一种整类
    # 静默失校——04-实施计划.md 全篇 13 处跨册引用用的都是数字式，它加进 MD_FILES 后
    # C11 依旧一处都没看到，报出来的 0 错 0 警是空的。新增分册时只改这张表，
    # 不要在下面写死 if 分支。
    _VOL_ALIAS = {
        '03-01': '01-认证与系统',
        '03-02': '02-组织机构',
        '03-03': '03-课程与视频',
        '03-04': '04-题库与作业',
        '03-05': '05-数据中心',
    }

    # 跨册引用：「02-组织机构接口 39」「02-组织机构分册接口 8」「02-组织机构 §7.3（接口 29）」
    # 以及数字式的「03-02 接口 37」。两种形态命中后一律归一为分册名，
    # 后续的范围校验 / 接口名比对 / §小节号比对三段逻辑完全复用。
    _XREF = re.compile(r'(0\d-(?:[\u4e00-\u9fa5]{2,8}|0\d))\s*(?:分册)?[^\n]{0,14}?接口\s*(\d+)')
    for path in MD_FILES:            # 含 PRD 与 00-通用约定，不限于有目录的分册
        for i, line in enumerate(read(path).split('\n'), 1):
            for m in _XREF.finditer(line):
                vol, n = _VOL_ALIAS.get(m.group(1), m.group(1)), int(m.group(2))
                if vol not in volume_tocs:
                    continue
                if os.path.basename(path).startswith(vol):
                    continue         # 本册内引用交给下面的逐册校验
                tgt = volume_tocs[vol]
                if n in _retired_of(vol):
                    continue         # 指向已登记退役的号：墓碑引用，放行
                if n not in tgt:
                    report('ERROR', 'C11', path,
                           f'第 {i} 行跨册引用「{vol} 接口 {n}」，该分册目录只有 1~{len(tgt)}')
                    continue
                # 「在范围内但指错」范围检查抓不到——只有带名称/小节注解时才可判。
                # 真实缺陷：白名单指向「03-课程与视频接口 30」（实为播放心跳，29 才是解密密钥）；
                # 01 分册指向「02-组织机构 §7.3（接口 30）」（实为标签分页，29 才是导入任务查询）。
                # 因此**跨册引用一律要求带接口名或小节号**，否则只报 WARN 提示补注解。
                tail = line[m.end():m.end() + 24]
                label = re.match(r'\s*([\u4e00-\u9fa5]{2,12})', tail)
                sect = re.search(r'§(\d+\.\d+)', line[:m.start() + len(m.group(0))])
                if label:
                    a_, b_ = label.group(1).replace(' ', ''), tgt[n].replace(' ', '')
                    if a_ not in b_ and b_ not in a_:
                        report('ERROR', 'C11', path,
                               f'第 {i} 行跨册引用「{vol} 接口 {n}（{label.group(1)}）」'
                               f'与该分册目录名「{tgt[n]}」不符')
                elif sect:
                    head = re.search(r'^### %s (.+)$' % re.escape(sect.group(1)),
                                     read(os.path.join(DOCS, '03-API接口文档', vol + '.md')), re.M)
                    if head and head.group(1).strip().replace(' ', '') != tgt[n].replace(' ', ''):
                        report('ERROR', 'C11', path,
                               f'第 {i} 行跨册引用「{vol} §{sect.group(1)}（接口 {n}）」不自洽：'
                               f'该分册 §{sect.group(1)} 是「{head.group(1).strip()}」，而接口 {n} 是「{tgt[n]}」')
                else:
                    report('WARN', 'C11', path,
                           f'第 {i} 行跨册引用「{vol} 接口 {n}」未带接口名——'
                           f'跨册编号变动时无从校验，建议写成「接口 {n} {tgt[n]}」')

    for path in API_FILES:
        text = read(path)
        toc = {int(m.group(1)): m.group(2).strip() for m in _TOC_ROW.finditer(text)}
        if not toc:
            continue                       # 00-通用约定 无接口目录
        vol_name = re.match(r'(0\d-[^.]+)\.md', os.path.basename(path))
        retired = _retired_of(vol_name.group(1)) if vol_name else set()
        # 期望编号 = 1..(在册数 + 已登记退役数)，扣掉登记在册的那几个号。
        # 【未登记的缺口照常 ERROR】—— 豁免只对人工登记且写明理由的单个号生效
        expected = [i for i in range(1, len(toc) + len(retired) + 1) if i not in retired]
        if sorted(toc) != expected:
            report('ERROR', 'C11', path,
                   f'目录表编号不连续：{sorted(toc)[:12]}… 共 {len(toc)} 项'
                   + (f'（已登记退役：{sorted(retired)}）' if retired else ''))
        in_trigger_table = False
        for i, line in enumerate(text.split('\n'), 1):
            for m in re.finditer(r'接口\s*(\d+)', line):
                n = int(m.group(1))
                # 跨分册引用形如「03-课程与视频接口 26」，不按本册目录校验
                if re.search(r'0\d-[^\s]{0,10}接口\s*$', line[:m.start() + 2]):
                    continue
                if n in retired:
                    continue         # 墓碑小节与变更注记要能写出自己的号
                if n not in toc:
                    report('ERROR', 'C11', path,
                           f'第 {i} 行引用「接口 {n}」，本分册目录只有 1~{len(toc)}')
            # 裸编号：§11 各表的「触发接口」列与 §2.3 权限总览的括号编号，
            # 都不带"接口"前缀。P1-7 正是从这个洞漏出去的——插入一个接口后
            # §2.3 整行编号错位一位，字面读出来是"教师可调转交管理员"。
            if re.match(r'^\|[^|]*\|[^|]*\|\s*触发接口\s*\|', line):
                in_trigger_table = True          # 只有带「触发接口」列的表才解析第 3 列
            elif not line.startswith('|'):
                in_trigger_table = False
            bare = ''
            if in_trigger_table:
                m11 = re.match(r'^\| \d{5} \| [^|]* \| ([^|]*) \|', line)
                if m11:
                    bare = m11.group(1)
            elif re.match(r'^\| [^|]* \| `(teacher|student|org_admin)` \|', line):
                bare = line                      # §2.3 权限总览行
            for bm in re.finditer(r'(?<![\d.])(\d{1,2})(?![\d.%s])' % '', bare):
                n = int(bm.group(1))
                if n and n not in toc and n not in retired:
                    report('ERROR', 'C11', path,
                           f'第 {i} 行的裸编号 {n} 超出本分册目录范围 1~{len(toc)}')

            # 带名称注解的引用，顺带核对名称是否对得上
            for m in re.finditer(r'接口\s*(\d+)（([^）]{2,20})）', line):
                n, label = int(m.group(1)), m.group(2)
                if n in toc:
                    a, b = label.replace(' ', ''), toc[n].replace(' ', '')
                    if a not in b and b not in a:
                        report('WARN', 'C11', path,
                               f'第 {i} 行「接口 {n}（{label}）」与目录名「{toc[n]}」不符')


# ==================================================================== main

def main():
    only = None
    if '--only' in sys.argv:
        only = sys.argv[sys.argv.index('--only') + 1].upper()

    tables = parse_ddl()
    checks = [
        ('C1', lambda: check_c1_ddl_structure(tables)),
        ('C2', check_c2_enums),
        ('C3', check_c3_error_codes),
        ('C4', lambda: check_c4_field_length(tables)),
        ('C5', check_c5_interface_count),
        ('C6', lambda: check_c6_table_count(tables)),
        ('C7', check_c7_markdown),
        ('C8', check_c8_banned),
        ('C9', check_c9_type_binding),
        ('C10', lambda: check_c10_type_semantics(tables)),
        ('C11', check_c11_interface_refs),
        ('C12', check_c12_anchors),
        ('C13', lambda: check_c13_platform_rows(tables)),
        ('C14', lambda: check_c14_column_sets(tables)),
        ('C15', check_c15_heartbeat_signature),
        ('C16', check_c16_heartbeat_rule_numbers),
        ('C17', check_c17_json_examples),
        ('C18', check_c18_endpoint_paths),
        ('C19', check_c19_f_registry),
        ('C20', check_c20_module_interface_counts),
        ('C21', check_c21_plan_section_refs),
    ]
    for code, fn in checks:
        if only and code != only:
            continue
        fn()

    names = {
        'C1': 'DDL 结构（逗号 / 索引列存在性 / 软删除约定）',
        'C2': '枚举取值跨文档一致',
        'C3': '错误码登记与同义',
        'C4': '字段长度 API ≤ DDL',
        'C5': '接口数量一致',
        'C6': '表清单 DDL = 契约',
        'C7': 'Markdown 健康度（JSON / 表格 / 围栏）',
        'C8': '禁用词与陈旧内容',
        'C9': '概念 ↔ node_type/user_type 编号绑定',
        'C10': 'DDL 列类型与注释语义相符',
        'C11': '接口编号交叉引用完整性',
        'C12': '承重论证锚句存在性',
        'C13': '平台级行（tenant_id=0）已逐表定案',
        'C14': 'DDL 列集合 = 02-数据库设计字段表',
        'C15': '心跳签名三处一致',
        'C16': '心跳校验规则编号 PRD ↔ API 对应',
        'C17': 'JSON 示例内部自洽（nodeType/userType/childCount）',
        'C18': '端点路径存在性（正文引用 ↔ 接口目录）',
        'C19': 'F 清单编号唯一 + 状态唯一',
        'C20': '§A 逐模块接口数 = §B 该模块表行数',
        'C21': '实施方案内部小节引用不悬空',
    }
    errors = [r for r in results if r[0] == 'ERROR']
    warns = [r for r in results if r[0] == 'WARN']

    print(f'\nDDL 建表 {len(tables)} 张，扫描 {len(MD_FILES)} 份 Markdown\n')
    by_code = defaultdict(list)
    for lv, code, f, d in results:
        by_code[code].append((lv, f, d))

    # C0 = 扫描清单本身出了问题（文件缺失）。它不属于任何一条检查项，因此**不在
    # `checks` 里**——若不在这里单独打印，它会被计入总数却永远不显示，正是本仓库
    # 反复踩的"报了但看不见"。且必须无视 `--only`：清单缺文件时，任何单跑的结果
    # 都是不完整的。
    if by_code.get('C0'):
        print('  ‼️  C0 扫描清单不完整 — 本次结果不可信')
        for lv, f, d in by_code['C0']:
            print(f'        [{lv}] {f}: {d}')
        print()

    for code, _ in checks:
        if only and code != only:
            continue
        items = by_code.get(code, [])
        if not items:
            print(f'  ✅ {code} {names[code]}')
        else:
            n_err = sum(1 for lv, _, _ in items if lv == 'ERROR')
            mark = '❌' if n_err else '⚠️ '
            print(f'  {mark} {code} {names[code]} — {n_err} 错误 / {len(items) - n_err} 警告')
            for lv, f, d in items[:12]:
                print(f'        [{lv}] {f}: {d}')
            if len(items) > 12:
                print(f'        … 另有 {len(items) - 12} 条')

    print(f'\n合计：{len(errors)} 个错误，{len(warns)} 个警告')
    return 1 if errors else 0


if __name__ == '__main__':
    # 退出码是 CI 唯一的判据：0 = 检查通过。它**不表示脚本跑完了**——这两件事必须
    # 分开看。Python 对未捕获异常本就以非 0 退出（实测崩溃时 EXIT=1，CI 不会误放行），
    # 本层不是在修一个"崩溃却返回 0"的缺陷，而是把这条不变量**显式写死**：
    # 将来任何人加一个兜底的 `except:`、或在 finally 里 `sys.exit(0)`，
    # 都会在这里露出来，而不是悄悄把崩溃变成绿灯。
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except BaseException:
        traceback.print_exc()
        print('\n检查器自身异常中止——本次结果不完整，一律按失败处理（退出码 1）')
        sys.exit(1)
