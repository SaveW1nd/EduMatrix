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


# 少数位置需要"点名旧方案以说明为什么不用它"，逐条豁免而不是整行放行
ALLOW = [
    ('is_deleted', '若用 `is_deleted` 这类 0/1 标志'),      # 契约 §2.2 论证前提
    ('is_deleted', '`is_deleted TINYINT(0/1)`'),            # 02-数据库设计 §7 对照表
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
    """
    for path in API_FILES:
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


# ====== C16 心跳校验规则编号 PRD F2-7 ↔ 03-03 §8.2.1 一一对应

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
    """C16 心跳校验规则的编号，PRD F2-7 与 03-03 §8.2.1 必须一一对应

    真实缺陷：PRD F2-7 曾把"服务端拒绝规则"与"处理流程"混在一个列表里编号，
    与 8.2.1 只有 2/6/7/8 四条碰巧对上。最危险的是 9 号——两边都是拒绝规则、
    都编号 9，一边是"参数越界"一边是"多端会话冲突"，而两者的处置动作完全相反。

    这已经不只是读着别扭：`vod_heartbeat_log.reject_rule` 把规则编号**持久化进了
    审计表**，该表按月分区保留 6 个月、累计留存数年。编号一旦不一致，运维查到
    reject_rule=N 对着 PRD 读就会读成另一条规则，且历史日志无法回溯修正。

    另外校验 reject_rule 注释里枚举的取值都能在 8.2.1 中找到对应的拒绝规则。
    """
    prd_path = os.path.join(DOCS, '01-PRD-产品需求文档.md')
    vol_path = os.path.join(DOCS, '03-API接口文档', '03-课程与视频.md')

    # --- 03-03 §8.2.1 的规则表：| N | **规则名** | ... ---
    vol = read(vol_path)
    m = re.search(r'^#### 8\.2\.1 .*?(?=^#### 8\.2\.2)', vol, re.M | re.S)
    if not m:
        report('ERROR', 'C16', vol_path, '未能定位 §8.2.1 规则表，检查标题格式')
        return
    api_rules = {int(n): name for n, name in
                 re.findall(r'^\|\s*(\d)\s*\|\s*\*\*([^*]+)\*\*', m.group(0), re.M)}

    # --- PRD F2-7 的「服务端校验规则」列表：N. **规则名**：... ---
    prd = read(prd_path)
    s = re.search(r'\*\*服务端校验规则（.*?(?=\*\*心跳处理流程)', prd, re.S)
    if not s:
        report('ERROR', 'C16', prd_path,
               '未能定位 F2-7「服务端校验规则」列表——校验规则与处理流程必须分成两个列表，'
               '混编则编号无法与 §8.2.1 对齐')
        return
    prd_rules = {int(n): name for n, name in
                 re.findall(r'^(\d)\. \*\*([^*]+)\*\*', s.group(0), re.M)}

    if set(api_rules) != set(prd_rules):
        report('ERROR', 'C16', prd_path,
               f'规则编号集合不一致：PRD {sorted(prd_rules)} vs §8.2.1 {sorted(api_rules)}')
        return

    for n in sorted(api_rules):
        kws = RULE_KEYWORDS.get(n, [])
        a_hit = any(k in api_rules[n] for k in kws)
        p_hit = any(k in prd_rules[n] for k in kws)
        if not (a_hit and p_hit):
            report('ERROR', 'C16', prd_path,
                   f'规则 {n} 两侧含义对不上：§8.2.1「{api_rules[n]}」vs PRD「{prd_rules[n]}」'
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
               f'reject_rule 注释声明取值 {n}，但 §8.2.1 无该编号规则')


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


def check_c11_interface_refs():
    """C11 正文里的「接口 N」必须指向本分册目录表中真实存在的编号

    真实缺陷：删除失效的"新建节点""删除节点"两个接口后，02 分册全部 48 个接口
    要重新编号，而正文里有 80 处「接口 N」交叉引用、还有 30 行错误码登记表用
    **不带"接口"前缀的裸编号列**（`9 / 10 / 13 / 14 / 18`）记录触发接口——后者极易
    整块漏掉。编号错了不会有任何症状，只会把读文档的人指到另一个接口上。
    """
    for path in API_FILES:
        text = read(path)
        toc = {int(m.group(1)): m.group(2).strip() for m in _TOC_ROW.finditer(text)}
        if not toc:
            continue                       # 00-通用约定 无接口目录
        if sorted(toc) != list(range(1, len(toc) + 1)):
            report('ERROR', 'C11', path,
                   f'目录表编号不连续：{sorted(toc)[:12]}… 共 {len(toc)} 项')
        for i, line in enumerate(text.split('\n'), 1):
            for m in re.finditer(r'接口\s*(\d+)', line):
                n = int(m.group(1))
                # 跨分册引用形如「03-课程与视频接口 26」，不按本册目录校验
                if re.search(r'0\d-[^\s]{0,10}接口\s*$', line[:m.start() + 2]):
                    continue
                if n not in toc:
                    report('ERROR', 'C11', path,
                           f'第 {i} 行引用「接口 {n}」，本分册目录只有 1~{len(toc)}')
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
