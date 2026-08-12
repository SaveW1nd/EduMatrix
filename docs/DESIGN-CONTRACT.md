# EduMatrix 设计契约（Design Contract）

> **本文档是 PRD、数据库设计、API 文档三份交付物的统一基准。**
> 所有文档中出现的实体名、表名、字段名、枚举值、接口路径必须与本契约一致。
> 若设计过程中需要偏离本契约，必须先修改本契约再修改下游文档。
>
> **架构主线**：统一组织树（行政管辖）+ 师生直连（教学服务）+ 资源逐级下发的私域督学模型。

---

## 1. 技术栈基线（参考仓库对齐）

| 层 | 选型 | 对齐仓库 |
| --- | --- | --- |
| 后端框架 | Java 17 + Spring Boot 3.x + MyBatis-Plus | RuoYi-Vue-Plus |
| 权限 | Sa-Token（JWT 风格 Token）+ RBAC（操作权限）+ 树形子树（数据权限） | RuoYi-Vue-Plus |
| 数据库 | MySQL 8.0（utf8mb4 / InnoDB） | — |
| 缓存/队列 | Redis 7（心跳缓冲、验证码、Token）+ 定时任务落盘 | RuoYi-Vue-Plus |
| 前端 | Vue 3 + Element Plus（PC 管理端）、H5 自适应（学生端） | roncoo-education |
| 播放器 | DPlayer（二开：禁拖拽、跑马灯水印、心跳） | DPlayer |
| 视频云 | 腾讯云 VOD（首选，阿里云 VOD 兼容适配层） | — |
| ID 生成 | 雪花算法（bigint，Java 侧生成），前端以字符串传输防精度丢失 | xzs |

## 2. 全局约定

### 2.1 多租户与树根

- **平台根节点**：`org_node` 中存在唯一一行 `id = 0` 的虚拟平台根（`node_type=1`、`tenant_id=0`、`parent_id=-1`、`ancestors=''`）。平台超管的 `sys_user.node_id = 0`，因此"子树"规则对超管同样成立（其子树 = 全平台），**全系统无需为超管写特例分支**。
- **超管的直接子节点 = 一个机构 = 一个租户**，其 `tenant_id` 即该节点自身 id；机构以下的所有节点继承同一 `tenant_id`。
- **`sys_tenant.root_node_id` 必须允许 NULL**：开通租户时存在循环依赖（根节点的 `tenant_id` 来自租户行 id，租户行的 `root_node_id` 来自根节点 id），落库顺序固定为「插入租户行（root_node_id 暂空）→ 插入机构根节点 → 回写 root_node_id」，三步同一事务。
- 除 `sys_menu` 等平台级表外，所有业务表带 `tenant_id`（bigint）。MyBatis-Plus 租户插件自动注入过滤条件；跨租户访问一律返回 404（不暴露存在性）。
- 租户隔离是**硬边界**，与树的子树权限是两道独立防线，不得互相替代。

### 2.2 通用字段（所有业务表必备，DDL 中统一出现在表尾）
```
tenant_id     BIGINT       NOT NULL  租户（机构）ID
create_by     BIGINT       NULL      创建人 user_id
create_time   DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP
update_by     BIGINT       NULL      更新人 user_id
update_time   DATETIME     NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
deleted_at    BIGINT       NOT NULL  DEFAULT 0  逻辑删除：0=未删除，删除时写毫秒时间戳
remark        VARCHAR(500) NULL      备注
```
- 主键统一 `id BIGINT`（雪花，非自增）。
- 第 4 节表清单的"关键字段"列为**要点摘录，非穷举**；字段全集以 DDL 为准，DDL 可增列但不得与契约已列字段冲突。
- **所有 `creator_id` / `*_by` 类人员字段一律指向 `sys_user.id`**，不指向 `org_teacher.id`——因为管理员与教师都可能是创建者，而管理员没有 `org_teacher` 档案行。
- 核心业务数据（课程/题目/作业/答卷）**禁止物理删除**，一律写 `deleted_at = 当前毫秒时间戳`。
- **软删除用时间戳而非 0/1 标志**：唯一索引末尾统一追加 `deleted_at`。若用 `deleted_at`，同一业务键**最多只能容纳一条已删除行**——"打标 → 去标 → 再打标 → 再去标"到第二次去标就撞唯一键，而这类反复增删在 `org_student_tag`、`sys_user_role`、`hw_answer_detail` 上都是常规操作。时间戳方案下每次删除值不同，可容纳任意多条，且白得一个删除时间用于审计。MyBatis-Plus 用 `@TableLogic(value="0", delval="UNIX_TIMESTAMP(NOW(3))*1000")` 原生支持。
- 日志/心跳明细表可例外（允许归档清理；可不带 `update_by` / `remark`，登录与操作日志另可省略 `create_by` / `create_time`，改由 `login_time` / `oper_time` 承担业务时间）。


### 2.3 统一组织树

**系统中的每一个组织单元与人员都是同一棵树上的节点**，从平台超管一路到学生，一棵树到底。表：`org_node`。

| node_type | 节点类型 | 可承载的子节点 | 说明 |
| --- | --- | --- | --- |
| 1 | 机构 / 管理单元 | 机构、管理员、教师、学生 | 租户根节点、校区、部门等，可无限嵌套 |
| 2 | 管理员 | 机构、管理员、教师、学生 | 管理岗人员；可创建下级管理员、教师、学生 |
| 3 | 教师 | **仅学生** | 导师节点；其直接子节点即"名下学员" |
| 4 | 学生 | **不可有子节点** | 叶子 |

**结构约束（实现必须强制校验，违反即拒绝）**：

1. **教师节点下只能挂学生**；学生节点必须是叶子。教师尚无学员时其自身即为叶子。
2. **树不允许成环**。移动节点时，目标父节点不得是自身或自身的任何后代：
   `targetParentId != movingNodeId AND FIND_IN_SET(#{movingNodeId}, targetParent.ancestors) = 0`
3. `org_node.ancestors` 冗余祖级路径（逗号串，如 `0,100,101,205`），子树查询据此判定，**不走递归 CTE**。注意 `FIND_IN_SET` 是**语义定义而非执行写法**——它是列上的函数，无法走索引，实际实现见 §2.4 的三条路径。
4. **移动节点后必须递归重算整棵子树的 `ancestors`**（含被移动节点自身），且该操作与移动本身在同一事务内。
5. 树深度**不设上限**。

**学员归属与分配**：
- 学生由管理员创建时，默认挂在**创建它的管理员节点**下 → 表示"已归属该管理员，尚未分配导师"。
- 分配给导师 = **把学生节点移到该导师节点下**；导师必须在执行分配者的子树内。
- 转给其他管理员 = 把学生节点移到目标管理员下（同一动作，同一接口）。
- 教师带着学员整体调岗 = 移动教师节点，其学员子树跟随。

### 2.4 数据权限：一条规则

> **你能看到的数据 = 你所在节点的子树。**

| 角色 | 看到的范围 | 判定 |
| --- | --- | --- |
| 平台超管 | 全平台 | 跨租户 |
| 机构管理员 / 下级管理员 | 其子树全部（下级管理员、教师、学生） | 子树判定（语义式 `node.id = #{myNodeId} OR FIND_IN_SET(#{myNodeId}, node.ancestors)`） |
| 教师 | 其子树 = 名下学员 | 同上；因教师节点下只能挂学生，其子树**恰好等于直接子节点** |
| 学生 | 仅自身 | 同上（学生无子节点，子树即自身） |

**上表的 `FIND_IN_SET` 是语义定义，不是执行写法。** 它是作用在列上的函数，**无法走索引，直接内联会导致每次带数据权限的查询都全表扫描 `org_node`**——而这是全系统执行频率最高的条件。实现必须按下表选路（索引已在 DDL 中就位）：

| 角色 / 场景 | 执行写法 | 命中索引 |
| --- | --- | --- |
| **教师（最高频）** | 子树 ≡ 直接子节点，退化为 `WHERE parent_id = #{myNodeId} AND node_type = 4` | `idx_parent_type` |
| **管理员：取整棵子树** | 先用前缀 LIKE 解析出子树节点 ID 集合（`ancestors = P OR ancestors LIKE CONCAT(P,',%')`，`P = (ancestors = '' ? CAST(id AS CHAR) : CONCAT(ancestors,',',id))`——**空串分支不可省**：平台根 `ancestors=''`、`id=0`，若直接 CONCAT 得 `',0'`，而机构节点 `ancestors='0'` 既不等于 `',0'` 也不 LIKE `',0,%'`，超管取全平台会静默返回空集），再对业务表 `WHERE node_id IN (...)`；结果集可缓存至 Redis，节点移动时失效 | `idx_ancestors(255)` |
| **管理员：逐层浏览** | 按 `parent_id` 逐层展开（树懒加载、面包屑） | `idx_tenant_parent_sort` |
| **离线巡检 / 已被 tenant_id 收敛的小结果集** | 可直接用 `FIND_IN_SET` | 无（可接受） |

> 详细推导与 SQL 模板见 02-数据库设计 §3.1.2「子树查询的三条路径」。**分册文档中出现的 `FIND_IN_SET` 一律理解为语义表达**，实现方不得逐字照抄进高频查询。

- **全系统只有这一条数据权限规则**，所有角色适用，不存在第二套过滤逻辑。
- 学员被移走后，原上级**立即失去对其全部数据（含历史明细）**的访问权；历史统计归属另见 2.6。
- **数据范围由树决定，操作权限由角色决定**（见第 3 节）。父节点决定子节点"能看到哪些数据"，不决定"能执行哪些操作"。

**越界拒绝的响应约定（403 / 404 / 10107 的分工，全系统统一）**：

| 场景 | 返回 | 理由 |
| --- | --- | --- |
| 访问**路径上的资源**（`GET/PUT/DELETE /xxx/{id}`）而该资源不在我的子树内 | **404** | 不暴露存在性，与跨租户一致 |
| 无该功能的操作权限（角色/菜单权限不足） | **403** | 与数据无关，是功能级拒绝 |
| **请求参数/请求体中显式指定的目标对象**越界（如移动节点的 `targetParentId`、授权的 `targetNodeIds`、发布作业的 `studentIds`） | **`10107`**（业务码，HTTP 200） | 用户主动选了越界对象，需明确提示"请重新选择"，而非静默 404 |

> 判别口径：**"我要操作的东西"越界 → 404；"我选的目标"越界 → 10107；"我没资格做这件事" → 403。**

### 2.5 资源逐级下发

**受管资源**：课程（`crs_course`）、题目（`qb_question`）、视频（`vod_video`）。每个资源有 `owner_node_id`（归属节点，创建时写入创建者所在节点）。

授权表：`org_resource_grant`（资源 → 目标节点）。

**授权规则（逐级收缩，无继承）**：

1. **只能授权自己拥有的资源**：授权人必须已拥有该资源（自己是 `owner_node_id`，或该资源已显式授权给自己所在节点且在有效期内）。
2. **只能授权给自己子树内的节点**：目标节点必须满足 2.4 的子树条件。
3. **不向下继承，每一层都必须显式授权**。上级拥有 ≠ 下级自动拥有：
   - 机构管理员拿到 100 门课 → 下级管理员**必须被显式授权**才能拿到其中的 30 门
   - 下级管理员 → 教师同样必须显式授权
   - 教师 → 学生同样必须显式授权（精准到人）
   任一节点实际可用的资源 = 其父级显式授予它的那一份，天然逐级收缩，不存在"跨级穿透"。
4. **查询语义**：判定"某节点能否使用某资源"只需一条 `org_resource_grant` 命中（`target_node_id = 我 AND resource_id = X AND 有效期内`），**不回溯祖先链**——比继承模型更快，且不存在祖先链断裂导致的判定歧义。
5. **级联回收（必须实现）**：撤销对某节点的资源授权时，**必须级联撤销该资源在目标节点整个子树内的全部授权**。否则会出现"父级已无权、子级仍持有"的悬挂授权，逐级收缩被破坏。
   - 已产生的学习记录（`vod_watch_progress`、`hw_answer_sheet`、`hw_wrong_book`）**一律保留不删**，仅失去继续访问权。
6. **一致性巡检**：定时任务扫描授权异常，结果**必须分两类计数**：
   - `danglingCount` **真悬挂**（级联回收失效导致）→ 指标目标值 **0**
   - `crossScopeCount` **跨管辖**（节点移动导致，合法保留且已降级只读）→ 仅作待办提示，**不计入一致性指标**
   若合并计数，则任何一次教师调岗或学员转交都会使指标永久非 0，持续产生假警报，最终结果是运维关掉告警、真悬挂也没人看。

7. **有效期不得超过上级（防时间维度悬挂）**：下级授权的 `valid_end` **自动截断为不晚于授权人自身对该资源的 `valid_end`**。若不约束，会出现"上级授权已到期、下级仍有效"的悬挂授权，而级联回收只在显式撤销时触发、管不到时间维度。授权人自身为 `owner_node_id` 时不受此限。

8. **被授权方只能用、不能改**：被授权的资源对目标节点是**只读**的——可用于备课/组卷/再下发/学习，但不可编辑、删除、上下架。写操作一律要求 `owner_node_id = 我的节点`，否则 403。

9. **节点移动与已有授权的关系（正交，互不自动联动）**：学员/教师被移动到其他上级下时，其名下已持有的 `org_resource_grant` **默认既不自动撤销、也不自动新增**——否则每次转移都会静默中断学员正在学的课程。
   - 但移动接口**必须在响应中返回受影响的授权清单**（由原上级授予、现已跨出其管辖范围的授权），并支持可选参数 `revokeOutOfScopeGrants`（默认 `false`）由操作者决定是否一并回收。
   - 该类授权在一致性巡检中标记为"跨管辖授权"，只告警不自动处理。
   - **跨管辖授权降级为只读**：目标节点被移出授权人子树后，其持有的该授权**仅保留"使用"能力（学习、备课、组卷），丧失"再下发"能力**。
     不加这条会形成资产穿透：教师 T 持有校区 A 的课程 K1~K10，调岗到校区 B 后仍"拥有"这些课程，可以合法地授给 B 的新学员——只要促成一次调岗，A 的课程资产就进入 B 的分支并可无限复制。
     判定：授权行的 `target_node_id` 当前祖先链**不再包含**该资源 `owner_node_id` 或其有效授权链时，该行只读。

11. **受管资源的授权目标类型限制**：`resource_type` 为 2（题目）或 3（视频）时**不得授权给学生节点**（`node_type=4`）。
    学生侧没有题目/视频的直接使用入口——作答走 `hw_homework_target` + 固化版本，播放走课程授权，错题本走 `question_version` 快照，三条路径都与题目/视频授权解耦。授给学生的行永远不会被任何鉴权路径读到，只会放大授权表规模并制造"悬挂授权"误报。

10. **撤销授权与已分发作业解耦**：撤销课程授权**不影响已分发的作业**（作业是已下达的任务，不是资源）。学员仍可作答、教师仍可批改、成绩仍计入统计；仅失去课程内容的继续访问权。否则会出现作业中途消失、成绩缺失的严重业务事故。

**权限模板（解决逐级显式授权的操作成本）**：

逐级显式授权的代价是运维负担（新招一名教师要逐个勾选几百个资源），用模板抵消：

- `org_perm_template` 定义一组资源清单（如"高三数学包" = 20 课程 + 500 题目 + 80 视频），`org_perm_template_item` 存明细。
- **适用于创建任意下级节点**：新建下级管理员、教师、学生时均可套用模板一键授权，生成的授权行 `grant_source=5`（按模板）；也可对已存在的节点追加套用。
- **模板只管资源，不含功能权限**：模板内容仅为课程/题目/视频清单，不包含角色、菜单、按钮等操作权限——操作权限由角色决定（见第 3 节），与模板无关。
- **模板不绕过收缩规则**：应用模板时，实际授权 = 模板资源清单 **∩ 授权人当前拥有的资源**。模板中授权人已无权的部分自动跳过并在响应中列出，**绝不放大权限**。
- **模板可见范围是铁律 2 的唯一显式例外**（仅作用于模板对象本身，不影响任何业务数据范围）：
  `可见模板 = 我自己建的 ∪ 我的祖先节点建的 ∪ 我子树内节点建的`（即整条根到叶路径 ∪ 子树）。
  纳入"祖先建的"是必要的——否则机构统一制定的模板下级根本看不到，模板就失去了抵消操作成本的意义。
  **该例外安全的原因**：模板只是一份资源清单，套用时取交集（模板 ∩ 授权人已有资源），**永远不可能授出授权人本就没有的资源**。同一个"高三数学包"，机构管理员用能授出 20 门课，只拿到 5 门的下级管理员用就只授出 5 门。

### 2.6 归属与结算规则（跨模块统一口径）

**原则：历史归原导师，以自然日为结算单位。**

| 数据 | 归属锚点 | 规则 |
| --- | --- | --- |
| 日学习汇总 | `stat_student_daily.teacher_node_id`（结算时刻快照） | 每日凌晨结算时写入当时的父节点（若父为教师）；**归属快照（teacher_node_id/node_id）永不重算；指标列可由补数任务重跑覆盖** |
| 答卷 | `hw_answer_sheet.teacher_node_id`（导师节点快照） | **发布时为每个目标学生预建 status=0 答卷并写入初值** → 提交时最终固化 → 逾期未交在截止置 status=4 时固化，此后永不回改。<br>**必须发布时预建、而非首次进入时创建**：否则从未打开作业的学生根本没有答卷行，「截止时把 0/1 置 4」无行可置，「逾期未交」整个统计口径落空 |
| 转导师当日 | 归**新导师** | 当日尚未结算，凌晨按结算时刻的归属计算（与"分母对齐"一致：学员已不在原导师名下，数据留在原导师会造成有数据无学员的错配） |

**两个口径必须分开**：
- **学员档案**（看学生）：新导师可查看该学员**转入前的完整学习历史**，否则无法督学。
- **导师看板/业绩**（看导师）：只统计该导师名下期间产生的数据，按结算快照上卷。
- "今日实时"按当前归属计算，"历史趋势"按结算快照读取；换绑当日两者短暂不一致，属预期行为，文档须写明。

**§2.4（学员移走后原上级立即失去访问权）与本节（业绩按快照上卷）的边界裁决**：

| 场景 | 规则 |
| --- | --- |
| **聚合数值**（看板的平均完播率、提交率、趋势折线等） | 走结算快照，**包含已转出学员在其名下期间产生的数据**——否则导师历史业绩会随学员流动而回溯变化。但响应中**不得返回任何已转出学员的身份信息**（姓名/ID/明细行）。 |
| **个体明细下钻**（学员列表、单个学员档案、答卷明细、错题明细） | 在快照基础上**叠加当前子树过滤**：已转出的学员不出现在任何明细列表中，其详情页返回 404。 |

> 一句话：**数能算进去，人看不见。**

**未分配导师的学员（`teacher_node_id` 为空）**：不计入任何导师看板（无导师可归），但**照常计入其所在节点的子树聚合**（`stat_node_daily`）与机构大屏——否则公海学员会从机构整体数据中凭空消失。管理员可在节点大屏看到"未分配学员数"作为待办提示。

### 2.7 学生端可见性

私域模式下**学生之间互不可见**：
- 学生端不展示同学名单、群体平均分、排行榜、他人任何数据。
- 学生只能看到：本人课程与进度、本人作业与成绩、本人错题本、本人学习档案。
- 排行类功能仅存在于管理端（按节点/导师维度）。

## 3. 角色与操作权限

**操作权限（能执行什么）由固定角色决定，与树的位置无关**；树只决定数据范围。

| 角色 | role_key | user_type | 绑定节点类型 | 可执行的关键操作 |
| --- | --- | --- | --- | --- |
| 平台超管 | `super_admin` | 0 | 平台（树根） | 租户开通、平台配置 |
| 管理员 | `org_admin` | 1 | 机构 / 管理员节点 | 建下级管理员、建教师、建学生、分配学员、资源下发、全模块管理 |
| 教师 / 导师 | `teacher` | 2 | 教师节点 | 备课、组卷、给名下学员发课程与作业、批改、看名下看板 |
| 学生 | `student` | 3 | 学生节点 | 学习、作答、看本人档案 |

- 机构管理员与下级管理员**角色相同**，差别仅在树的位置（子树范围不同）。不再需要单独的"节点管理员"角色。
- 角色对应的菜单/按钮权限沿用 RBAC（`sys_role_menu`）。

## 4. 表清单（权威命名，共 41 张）

前缀：`sys_` 系统基座 / `org_` 组织与人员 / `crs_` 课程 / `vod_` 视频与进度 / `qb_` 题库 / `hw_` 作业 / `stat_` 数据中心

### sys_（10）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| `sys_tenant` | 机构（租户） | root_node_id(机构根节点), name, contact_name, contact_phone, expire_time, status(0正常1停用), max_student_count |
| `sys_user` | 统一账号（全角色共用登录体系） | username, password(BCrypt), user_type(0-3), real_name, phone, avatar, **node_id**(所在节点), status(0正常1停用), pwd_reset_flag(0否1需强制改密), last_login_time |
| `sys_role` | 角色 | role_name, role_key, status |
| `sys_user_role` | 用户-角色 | user_id, role_id |
| `sys_menu` | 菜单/按钮权限（平台级，无 tenant_id） | parent_id, menu_name, menu_type(M目录C菜单F按钮), perms, path, visible |
| `sys_role_menu` | 角色-菜单 | role_id, menu_id |
| `sys_file` | 文件 | file_name, file_url, file_size, file_type, storage(1本地2OSS), biz_type |
| `sys_login_log` | 登录日志 | user_id, ip, user_agent, status, login_time |
| `sys_oper_log` | 操作日志 | user_id, module, action, method, params, ip, cost_ms, oper_time |
| `sys_tenant_config` | 租户配置（键白名单见第 5 节末） | config_key, config_value；UK(tenant_id,config_key) |

### org_（10）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| **`org_node`** | **统一组织树（所有节点：机构/管理员/教师/学生）** | parent_id(根为0), **ancestors**(逗号祖级串), node_name, **node_type**(1机构2管理员3教师4学生), **ref_user_id**(管理员/教师/学生节点指向 sys_user，机构节点为空), sort, status(0正常1停用), child_count/student_count(冗余) |
| `org_teacher` | 教师档案（1:1 节点） | node_id UK, user_id UK, teacher_no, subject, title, entry_date, student_count(冗余) |
| `org_student` | 学生档案（1:1 节点） | node_id UK, user_id UK, student_no, guardian_name, guardian_phone, **status**(0在读1已退课2毕业归档), **quit_time**, **quit_reason**, archive_time |
| **`org_node_change_log`** | 节点异动轨迹（移动/分配/转交/归档） | node_id, change_type(1建档2分配导师3转交管理员4教师调岗5毕业归档6归档恢复7退课**8节点移动**), from_parent_id, to_parent_id, change_time, operator_id, reason |
| **`org_resource_grant`** | **资源逐级下发授权（无继承，每级显式）** | **resource_type**(1课程2题目3视频), resource_id, **target_node_id**, valid_start, valid_end(可空=永久), **grant_source**(1手动选择2按节点批量3按标签批量4按名下全体5按模板), source_ref_id(可空,模板ID等), grant_by, grant_time；UK(resource_type, resource_id, target_node_id, deleted_at) |
| **`org_perm_template`** | **权限模板**（抵消逐级显式授权的操作成本） | template_name, owner_node_id(归属节点,可见范围=其子树), description, item_count(冗余), status(**0启用 1停用**，方向与 sys_user/sys_tenant/org_node 一致) |
| **`org_perm_template_item`** | 模板资源明细 | template_id, resource_type(1课程2题目3视频), resource_id；UK(template_id,resource_type,resource_id) |
| **`org_tag`** | 标签定义（**仅用于筛选与批量操作，不参与任何权限判断**） | tag_name, tag_group, color, sort |
| **`org_student_tag`** | 学生-标签 M:N | student_id, tag_id；UK(student_id,tag_id) |
| `org_import_task` | Excel 导入任务 | biz_type(1学生导入), file_id, total_count, success_count, fail_count, status(0处理中1成功2部分失败3失败), fail_report_file_id |

### crs_（4）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| `crs_course` | 课程 | course_name, **owner_node_id**(归属节点，即"谁的课"), cover_file_id, subject, description, status(0草稿1已上架2已下架), lesson_count(冗余), total_duration(冗余,秒) |

> **资源归属唯一化**：`crs_course` / `qb_question` / `vod_video` 一律以 `owner_node_id` 表示归属，**不再保留独立的 teacher_id / creator 归属字段**。展示"这是谁的课"取 owner 节点的 `node_name`；需要作者署名时用通用字段 `create_by`。避免归属有两个真相源。
| `crs_chapter` | 章/节（两级树） | course_id, parent_id(0=章,否则为节), chapter_name, sort |
| `crs_lesson` | 课时 | course_id(冗余), chapter_id, lesson_name, lesson_type(1视频2图文), video_id(可空), content_id(可空), duration(秒,冗余), sort, is_free_preview, status(0隐藏1可见) |
| `crs_material` | 图文资料内容 | title, content(LONGTEXT 富文本), attachment_file_ids(JSON) |

> 课程对学生/节点的授权统一走 `org_resource_grant`（resource_type=1），不再有独立的课程授权表。

### vod_（4）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| `vod_video` | 云端媒资 | **owner_node_id**(归属节点), provider(1腾讯2阿里), vod_file_id(可空), video_name, duration, cover_url, size_bytes, status(0上传中1转码中2正常3转码失败9禁用), hls_url, upload_user_id |
| `vod_play_auth_log` | 播放凭证发放记录（审计） | student_id, lesson_id, video_id, auth_token, expire_time, client_ip |
| `vod_watch_progress` | 学习进度（学生×课时，聚合态） | student_id, lesson_id, course_id(冗余), watched_duration(墙钟有效累计秒,复看计入,单次封顶15s), max_position, watch_status(0未开始1学习中2已完成), complete_time, last_heartbeat_time；UK(student_id,lesson_id) |
| `vod_heartbeat_log` | 心跳明细（按月分区，可归档） | student_id, lesson_id, video_id, current_time_sec, interval_sec, client_ip, device, created_time |

### qb_（3）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| `qb_category` | 题库分类树（科目/知识点） | parent_id, category_name, sort |
| `qb_question` | 题目主表（物理 ID 恒定） | id(雪花,永不复用), **owner_node_id**(归属节点), category_id, question_type(1单选2多选3判断4填空5简答6材料题), parent_id(子题→父题id), difficulty(1-5), current_version, stem_preview, creator_id, status(0草稿1启用2停用) |
| `qb_question_version` | 题目版本快照（不可变） | question_id, version, content(JSON), correct_answer(JSON), analysis, score_default, created_by, created_time；UK(question_id,version) |

**版本规则**：编辑题目 = 写入新 `qb_question_version`（version+1）并更新 `current_version`；历史版本不可修改、不可删除。

### hw_（6）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| `hw_homework` | 作业/试卷定义（**注：作业不是"受管资源"**，不可经 `org_resource_grant` 下发；`owner_node_id` 仅作归属锚点与数据权限依据） | homework_name, homework_type(1作业2考试), **owner_node_id**, course_id(可空), creator_id, total_score, question_count(冗余), deadline, publish_time, allow_late_submit, answer_visible_type(1提交后2截止后), status(0草稿1已发布2已截止3已撤回) |
| `hw_homework_question` | 作业-题目（**发布时固化版本**） | homework_id, question_id, question_version(锁定), score, sort；UK(homework_id,question_id) |
| **`hw_homework_target`** | **分发对象（全量精确到学生）** | homework_id, **student_id**（原 target_type/target_id 废弃）, **grant_source**(1手动选择2按节点批量3按标签批量4按名下全体), source_ref_id(可空)；UK(homework_id,student_id) |
| **`hw_answer_sheet`** | 学生答卷 | homework_id, student_id, **teacher_node_id**(作答时导师节点快照，取代 class_id), status(0未开始1作答中2已提交待批改3已批改4逾期未交), objective_score, subjective_score, total_score, submit_time, is_late, grade_teacher_id, grade_time；UK(homework_id,student_id) |
| `hw_answer_detail` | 逐题作答明细 | answer_sheet_id, question_id, question_version(快照), student_answer(JSON), is_correct(0错1对2半对,待批改NULL), score, auto_graded, comment, grade_time |
| `hw_wrong_book` | 动态错题本 | student_id, question_id, question_version(**首次做错时刻的版本**), source_homework_id, source_answer_detail_id, wrong_count, first_wrong_time, last_wrong_time, master_status, master_time；UK(student_id,question_id) |

### stat_（4）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| **`stat_student_daily`** | **学生日学习汇总（唯一事实表）** | student_id, **teacher_node_id**(结算时刻导师节点快照,可空——未分配导师时为空), **node_id**(结算时刻该学生节点的**直属父节点**快照，**NOT NULL**：学生节点恒有父节点), stat_date, watch_seconds, **finished_lesson_count / should_lesson_count**(完播率分子分母), **ontime_submit_count / late_submit_count / should_submit_count**(提交率分子分母), is_active(0否1是)；UK(student_id,stat_date) |
| **`stat_teacher_daily`** | **导师日汇总** | teacher_node_id, stat_date, student_count, **finished_lesson_count / should_lesson_count**, **ontime_submit_count / should_submit_count**, avg_complete_rate / ontime_submit_rate(派生冗余,便于单日直读), active_student_count；UK(teacher_node_id,stat_date) |
| **`stat_node_daily`** | **节点日汇总**（机构/校区大屏，**按该节点子树聚合**） | node_id, stat_date, student_count, **finished_lesson_count / should_lesson_count**, **ontime_submit_count / should_submit_count**, avg_complete_rate / ontime_submit_rate(派生冗余), active_student_count；UK(node_id,stat_date) |

> **统计表铁律：存分子分母，不只存比率。** 比率不可跨天平均——`AVG(日比率)` ≠ 区间真实比率。各日汇总表冗余的 `xxx_rate` 列仅供"单日"直读，**禁止用于区间聚合**。
>
> **但区间取法分两类，不可一律 `Σ分子÷Σ分母`**（实现方最易出错处）：
>
> | 类型 | 列 | 语义 | 区间取法 |
> | --- | --- | --- | --- |
> | **累计状态型** | `finished_lesson_count` / `should_lesson_count` | **截至该日的累计快照**（已完播/应学课时数） | **取区间末日那一行**的 `分子 ÷ 分母`；**严禁跨天求和**——同一门课时会被重复计数 |
> | **当日流量型** | `ontime_submit_count` / `late_submit_count` / `should_submit_count` / `watch_seconds` | 当日新增量 | `Σ分子 ÷ Σ分母` |
>
> 举例：完播率第 1 天 10/100、第 2 天 15/100，正确的两日区间值是 **15/100=15%**（取末日），按 Σ 算成 25/200=12.5% 即为错。
> `is_active` / `active_student_count` 按天求和只得"活跃人日"；**区间去重活跃人数必须回事实表 `COUNT(DISTINCT student_id)`**。
> DDL 中这两类列的 COMMENT 必须显式写明"累计快照"或"当日新增"，不得含糊。
>
> **`stat_node_daily` 为子树聚合**：每行的值 = 该节点**整棵子树**内全部学员的汇总，因此读某节点大屏只需 1 行命中。代价是**父子节点的行不可相加**（父行已包含子行数据），跨节点汇总时只能取最近公共祖先的行，不能对多行求和。
| `stat_export_task` | 报表导出任务（异步） | export_type(1学员学习进度2学员作业成绩), biz_params(JSON), status(0排队1生成中2完成3失败), file_id, fail_reason, applicant_id |

**统计架构**：`stat_student_daily` 是唯一原始事实表，凭行内的 `teacher_node_id` / `node_id` 快照向两个维度上卷，不存在第二份原始数据。

## 5. 核心枚举（全局统一，文档中不得另造值）

| 枚举 | 值 |
| --- | --- |
| user_type | 0平台超管 1管理员 2教师 3学生 |
| **node_type 节点类型** | **1机构/管理单元 2管理员 3教师 4学生** |
| **student_status 学籍状态** | **0在读 1已退课(流失) 2毕业归档** |
| **change_type 节点异动类型** | **1建档 2分配导师 3转交管理员 4教师调岗 5毕业归档 6归档恢复 7退课 8节点移动(机构/管理员节点自身改父)** |
| **resource_type 受管资源类型** | **1课程 2题目 3视频** |
| **grant_source 授权/分发来源** | **1手动选择 2按节点批量 3按标签批量 4按名下全体 5按权限模板** |
| watch_status 完播状态 | 0未开始 1学习中 2已完成 |
| lesson_type 课时类型 | 1视频 2图文 |
| question_type 题型 | 1单选 2多选 3判断 4填空 5简答 6材料题(父题) |
| homework_status 作业状态 | 0草稿 1已发布 2已截止 3已撤回 |
| sheet_status 答卷状态 | 0未开始 1作答中 2已提交待批改 3已批改 4逾期未交 |
| video_status 媒资状态 | 0上传中 1转码中 2正常 3转码失败 9禁用 |
| is_correct 判定 | 0错误 1正确 2半对(多选漏选/填空部分对/主观题部分得分) NULL待批改 |

**完播判定规则**：`watched_duration >= duration × 90%` 即置为已完成（阈值 90% 为租户级可配置项 `complete_rate_threshold`）；判定时机为 XXL-Job 落盘时（唯一判定时机）。

**三态口径**：`正确率 + 漏选率 + 错误率 = 100%`（同一分母）。半对不计入错误率，独立为"漏选率"；错题本仍宽口径纳入 `is_correct ∈ {0,2}`。高频错题榜按"未掌握人数（错误+漏选）"排序。

**租户配置键白名单**（`sys_tenant_config.config_key` 的合法取值，穷举）：

| config_key | 类型 | 默认值 | 合法范围 | 说明 |
| --- | --- | --- | --- | --- |
| `complete_rate_threshold` | int | 90 | 60~100 | 完播判定阈值百分比 |
| `watermark_phone_mask` | int | 0 | 0/1 | 水印手机号是否脱敏；0 不脱敏（默认），1 中间四位打星 |

云厂商选择（腾讯/阿里 VOD）属**平台部署级参数**，不是租户配置键。

## 6. API 契约

### 6.1 通用
- Base Path：`/api/v1`；认证：`Authorization: Bearer {accessToken}`（Sa-Token JWT）
- 响应体：`{"code": 200, "msg": "操作成功", "data": ...}`
- 分页请求：`pageNum`(默认1), `pageSize`(默认10, 最大100)；分页响应 `data: {"total": 100, "list": [...]}`，允许附加可选 `summary`
- 所有 bigint ID 序列化为**字符串**
- 时间格式：`yyyy-MM-dd HH:mm:ss`（东八区）
- 逻辑删除统一用 `DELETE` 方法（后端执行 deleted_at=1）

### 6.2 模块路由前缀（权威）
| 前缀 | 模块 | 文档归属 |
| --- | --- | --- |
| `/api/v1/auth/**` | 登录、登出、刷新令牌、验证码、当前用户信息、修改密码 | 03-01 |
| `/api/v1/system/**` | 用户、角色、菜单、租户(平台超管)、租户配置、文件、日志 | 03-01 |
| `/api/v1/org/**` | **组织树、节点移动/分配/转交、教师、学生、标签、导入、异动轨迹、退课归档、资源下发** | 03-02 |
| `/api/v1/course/**` | 课程、章节、课时、图文资料 | 03-03 |
| `/api/v1/vod/**` | 视频上传凭证、媒资、转码回调、播放凭证、**心跳上报**、进度查询 | 03-03 |
| `/api/v1/question/**` | 题库分类、题目 CRUD、版本历史 | 03-04 |
| `/api/v1/homework/**` | 作业定义、发布分发、学生作答、自动判卷、批改流水线、错题本 | 03-04 |
| `/api/v1/stat/**` | **导师看板**、机构大屏、学员档案、导出任务 | 03-05 |

### 6.3 错误码段位
| 段位 | 含义 |
| --- | --- |
| 200 | 成功 |
| 401 / 403 | 未登录 / 无权限（含数据权限拒绝） |
| 400 | 参数校验失败 |
| 1xxxx | 系统与组织（含组织树结构校验：成环、非法父子类型、跨子树操作） |
| 2xxxx | 课程与视频 |
| 3xxxx | 题库与作业 |
| 4xxxx | 数据中心 |

**00-通用约定 §9 为错误码登记册**：新增错误码必须先在该表登记再在分册使用。

### 6.4 关键接口签名（心跳——最高频接口，签名固定）
```
POST /api/v1/vod/heartbeat
Body: {"lessonId":"...","videoId":"...","currentTime":123.4,"duration":600,"playRate":1.0}
Resp: {"code":200,"data":{"watchedDuration":130,"watchStatus":1,"maxPosition":135}}
服务端逻辑：间隔合理性校验(≥8s 视为有效，单次计入 min(实际间隔,15)s，>15s 封顶不报错；<8s 丢弃返回 20002) → 写 Redis
(key: vod:hb:{studentId}:{lessonId}) → XXL-Job 每 60s 批量落盘 vod_watch_progress，落盘时判定完播。
```

## 7. 文档产出物与分工

| 文件 | 内容 |
| --- | --- |
| `docs/01-PRD-产品需求文档.md` | 完整 PRD：角色与权限、组织树与人员管理、资源授权与权限模板（FR- 编号）、课程与视频、题库与作业、数据中心、页面清单、边界场景、非功能需求 |
| `docs/02-数据库设计.md` | 表结构说明、ER 图、核心设计要点、索引与分区、容量估算 |
| `docs/sql/edumatrix_ddl.sql` | 全部 41 表可执行 DDL（MySQL 8.0） |
| `docs/03-API接口文档/00-通用约定.md` | 认证、响应结构、分页、幂等、错误码登记册 |
| `docs/03-API接口文档/01-认证与系统.md` | auth + system |
| `docs/03-API接口文档/02-组织机构.md` | org：组织树、人员、标签、资源授权、权限模板 |
| `docs/03-API接口文档/03-课程与视频.md` | course + vod（含心跳/防刷） |
| `docs/03-API接口文档/04-题库与作业.md` | question + homework（含错题本） |
| `docs/03-API接口文档/05-数据中心.md` | stat |

## 8. 不可违反的铁律与表总数

### 8.1 表总数与构成

`sys_10 + org_10 + crs_4 + vod_4 + qb_3 + hw_6 + stat_4 = 41 张`。

### 8.2 三条不可违反的铁律

1. **树不成环**：任何节点移动都必须先做后代校验，且 `ancestors` 与移动在同一事务内重算。
2. **数据范围只由树决定**：不存在第二套数据权限逻辑；标签、角色、资源授权都不得扩大可见范围。
3. **资源只能向自己子树下发，且不得超出自己拥有的范围**：授权链**每一层都显式、无继承**，不可越权横向或向上授权；权限模板只是批量操作的快捷方式，应用时取交集，**绝不放大权限**。撤销必须级联到子树，不留悬挂授权。
