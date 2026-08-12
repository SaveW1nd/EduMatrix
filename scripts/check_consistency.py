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
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, 'docs')
DDL_PATH = os.path.join(DOCS, 'sql', 'edumatrix_ddl.sql')
REGISTRY = os.path.join(DOCS, '03-API接口文档', '00-通用约定.md')

MD_FILES = [
    os.path.join(ROOT, 'README.md'),
    os.path.join(DOCS, 'DESIGN-CONTRACT.md'),
    os.path.join(DOCS, '00-原始需求.md'),
    os.path.join(DOCS, '01-PRD-产品需求文档.md'),
    os.path.join(DOCS, '02-数据库设计.md'),
] + sorted(
    os.path.join(DOCS, '03-API接口文档', f)
    for f in os.listdir(os.path.join(DOCS, '03-API接口文档'))
    if f.endswith('.md')
)

API_FILES = [f for f in MD_FILES if '03-API接口文档' in f]

results = []      # (level, code, file, detail)


def report(level, code, file, detail):
    results.append((level, code, os.path.relpath(file, ROOT) if file else '-', detail))


def read(path):
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
    return tables


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

    for path in API_FILES + [os.path.join(DOCS, '01-PRD-产品需求文档.md')]:
        if path == REGISTRY:
            continue
        text = read(path)
        for i, line in enumerate(text.split('\n'), 1):
            # 号段/预留说明行不是"使用"，跳过
            if re.search(r'预留|号段|保留不复用|~\s*1[0-9]{4}', line):
                continue
            # 必须出现错误码语境词，否则五位数字多半是数量/毫秒/字节等业务数值
            if not re.search(r'返回|错误码|错误|code|拒绝|失败|校验不通过', line):
                continue
            # 语境词之外还要求写法像错误码：`10107` / 返回 10107 / 表格首列 | 10107 |
            for m in re.finditer(r'`([1-4]\d{4})`|(?:返回|错误码|code[=:\s]+)\s*`?([1-4]\d{4})`?'
                                 r'|^\|\s*([1-4]\d{4})\s*\|', line):
                code = next(g for g in m.groups() if g)
                if code not in registered:
                    report('ERROR', 'C3', path, f'第 {i} 行使用了未登记的错误码 {code}')
    # 废弃码不应再被任何分册引用
    for code, meaning in registered.items():
        if '空号' in meaning or '已废弃' in meaning:
            for path in API_FILES:
                if path == REGISTRY:
                    continue
                for i, line in enumerate(read(path).split('\n'), 1):
                    if code in line and '废弃' not in line and '空号' not in line:
                        report('WARN', 'C3', path, f'第 {i} 行引用了已废弃的错误码 {code}')


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
    listed = set(re.findall(r'^\|\s*\*{0,2}`(\w+)`', contract, re.M))
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
    'is_deleted': '软删除已统一为 deleted_at 时间戳',
    'node_type=4': 'node_type 已收敛为 0/1/2/3 四类',
    '机构/管理单元': '已去掉独立于人的组织单元节点类型',
    'org_class': '班级模型已废弃',
    'org_teacher_class': '班级模型已废弃',
    'crs_course_class': '已并入 org_resource_grant',
    'stat_class_daily': '已拆为 stat_teacher_daily + stat_node_daily',
    'DataScope 三档': '数据权限只有"本节点子树"一条规则',
    'V1.0': '文档不保留版本演进叙述',
    'V2.0': '文档不保留版本演进叙述',
}


def check_c8_banned():
    """C8 禁用词扫描：陈旧概念、已废弃表名、版本演进措辞"""
    for path in MD_FILES + [DDL_PATH]:
        if path.endswith('00-原始需求.md'):
            continue     # 需求基线允许出现"不采用班级制"这类对照表述
        for i, line in enumerate(read(path).split('\n'), 1):
            for word, why in BANNED.items():
                if word in line:
                    report('ERROR', 'C8', path, f'第 {i} 行出现 `{word}`（{why}）')


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
    }
    errors = [r for r in results if r[0] == 'ERROR']
    warns = [r for r in results if r[0] == 'WARN']

    print(f'\nDDL 建表 {len(tables)} 张，扫描 {len(MD_FILES)} 份 Markdown\n')
    by_code = defaultdict(list)
    for lv, code, f, d in results:
        by_code[code].append((lv, f, d))
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
    sys.exit(main())
