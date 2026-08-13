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

- **平台根节点**：`org_node` 中存在唯一一行 `id = 0` 的平台根（`node_type=0` 平台超管、`tenant_id=0`、`parent_id=-1`、`ancestors=''`）。平台超管的 `sys_user.node_id = 0`，因此"子树"规则对超管同样成立（其子树 = 全平台），**全系统无需为超管写特例分支**。
- **超管的直接子节点 = 一个机构 = 一个租户**：该节点是**机构最高管理员**（`node_type=1`），其 `tenant_id` 即节点自身 id；其下所有节点继承同一 `tenant_id`。`sys_tenant.root_node_id` 指向这个管理员节点。
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
- **所有 `*_by` 类人员字段一律指向 `sys_user.id`**，不指向 `org_teacher.id`——因为管理员与教师都可能是创建者，而管理员没有 `org_teacher` 档案行。**不设 `creator_id` 这类专用创建人列**：署名一律用公共字段 `create_by`，归属一律用 `owner_node_id`（见 §4 资源归属唯一化）。
- 核心业务数据（课程/题目/作业/答卷）**禁止物理删除**，一律写 `deleted_at = 当前毫秒时间戳`。
- **软删除用时间戳而非 0/1 标志**：唯一索引末尾统一追加 `deleted_at`。若用 `is_deleted` 这类 0/1 标志，同一业务键**最多只能容纳一条已删除行**——"打标 → 去标 → 再打标 → 再去标"到第二次去标就撞唯一键，而这类反复增删在 `org_student_tag`、`sys_user_role`、`hw_answer_detail` 上都是常规操作。时间戳方案下每次删除值不同，可容纳任意多条，且白得一个删除时间用于审计。MyBatis-Plus 用 `@TableLogic(value="0", delval="UNIX_TIMESTAMP(NOW(3))*1000")` 原生支持。
- 日志/心跳明细表可例外（允许归档清理；可不带 `update_by` / `remark`，登录与操作日志另可省略 `create_by` / `create_time`，改由 `login_time` / `oper_time` 承担业务时间）。


### 2.3 统一组织树

**系统中的每一个人都是同一棵树上的节点**，从平台超管一路到学生，一棵树到底。表：`org_node`。

**每个节点都是一个人**，不存在独立于人的"组织单元"节点。`node_type` 与 `user_type` 取值**完全一致**，一一对应：

| node_type | 节点类型 | 可承载的子节点 | 说明 |
| --- | --- | --- | --- |
| 0 | 平台超管 | 管理员 | 全树唯一一行（`id=0`），平台根 |
| 1 | 管理员 | 管理员、教师、学生 | 可创建下级管理员、教师、学生；**组织层级由管理员的嵌套表达**（"华东校区" = 张三管的那一片） |
| 2 | 教师 | **仅学生** | 导师节点；其直接子节点即"名下学员" |
| 3 | 学生 | **不可有子节点** | 叶子 |

> **组织单元即人**：不设"校区/年级/部门"这类空节点——需要表达组织层级时，建一个管理员节点即可（节点名可命名为"华东校区"，`ref_user_id` 指向该片区负责人）。代价是组织单元必须绑定一个具体账号；负责人更替时改 `ref_user_id` 与节点名，子树不动。

**结构约束（实现必须强制校验，违反即拒绝）**：

1. **教师节点下只能挂学生**；学生节点必须是叶子。教师尚无学员时其自身即为叶子。超管节点下只能挂管理员。
   `ref_user_id` **全部节点非空**（每个节点都是一个人），与 `sys_user.node_id` 互为反向引用。
2. **树不允许成环**。移动节点时，目标父节点不得是自身或自身的任何后代：
   `targetParentId != movingNodeId AND FIND_IN_SET(#{movingNodeId}, targetParent.ancestors) = 0`
3. `org_node.ancestors` 冗余祖级路径（逗号串，如 `0,100,101,205`），子树查询据此判定，**不走递归 CTE**。注意 `FIND_IN_SET` 是**语义定义而非执行写法**——它是列上的函数，无法走索引，实际实现见 §2.4 的三条路径。
4. **移动节点后必须递归重算整棵子树的 `ancestors`**（含被移动节点自身），且该操作与移动本身在同一事务内。
5. 树深度**不设上限**。

**停用语义（按节点类型区分，无可选参数）**：

| 停用对象 | 效果 | 理由 |
| --- | --- | --- |
| **管理员**（`node_type=1`） | **本人及其整棵子树全部禁止登录**（分支冻结） | 停用一个管理员就是停掉他管的这一片——分部关停、欠费停服、合规冻结都是这个意图 |
| **教师**（`node_type=2`） | **仅本人**；名下学员照常登录学习 | 教师离职/停职/请假时，学员的课程授权仍在，必须能继续学。级联会让整批学员突然登不进去，是业务事故 |
| **学生**（`node_type=3`） | 仅本人 | 无子树 |

**实现必须走"登录时查祖先链"，不做级联写库**：

```sql
-- 登录校验分两段，缺一不可（一条 SQL 表达）：
--   ① 自身节点被停用           → 拒登（教师/学生的"仅本人"停用由本段生效）
--   ② 祖先链中有被停用的管理员 → 拒登（管理员的"分支冻结"由本段生效）
SELECT 1 FROM org_node
 WHERE status = 1
   AND (  id = #{myNodeId}                                    -- ① 自身
       OR (id IN (...祖先 id 列表...) AND node_type = 1) )     -- ② 祖先链中的管理员
 LIMIT 1;                                                     -- 命中即拒登（10017）
```

**①段不可省**。只写②段（仅查祖先链中的管理员）时，教师与学生**自身**的 `status=1` 永远不命中——`node_type=1` 这个条件把它们排除在外——于是"停用一个教师"完全不生效。此前分册是靠"停用时同步把 `sys_user.status` 也置 1"来兜住本人停用的，那条路已废弃，理由见下。

- 停用只写 1 行，恢复也只写 1 行，子树立即同步，**不存在"分不清谁是被级联停的、谁是本来就单独停的"**
- 级联写库方案要为一个 1.1 万人的分支写 2.2 万行，中途失败留下半停用状态且无法自愈
- 注意②段里的 `node_type = 1`：只有**管理员**节点的停用会冻结子树；教师节点的 `status=1` 只通过①段停掉他自己，不影响其学员

**`org_node.status` 是停用的唯一权威**；`sys_user.status` 仅用于与组织无关的账号级封禁（如安全风控），**两者不联动**。

> **不联动是硬约束，不是措辞偏好**。曾有分册规定"停用节点时同步置 `sys_user.status=1`"，而启用只改回 `org_node.status` 一行——于是**停用可逆、启用不可逆**：账号侧的 1 再也没人改回来，用户永久登不进去。而 `PUT /system/users/{id}/status` 已收敛为超管专用（§2.9 与 03-01 §2.6），机构管理员**没有任何接口能把它改回来**，只能提工单改库。
>
> 根因是让两个都能表达"停用"的字段互相写。收敛为单一权威后：停用与启用都只写 `org_node.status` 一行，天然对称可逆；本人停用由登录校验①段承担，不再需要借道 `sys_user`。

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
| **教师（最高频）** | 子树 ≡ 直接子节点，退化为 `WHERE parent_id = #{myNodeId} AND node_type = 3` | `idx_parent_type` |
| **管理员：取整棵子树** | 先用前缀 LIKE 解析出子树节点 ID 集合（`ancestors = P OR ancestors LIKE CONCAT(P,',%')`，`P = (ancestors = '' ? CAST(id AS CHAR) : CONCAT(ancestors,',',id))`——**空串分支不可省**：平台根 `ancestors=''`、`id=0`，若直接 CONCAT 得 `',0'`，而机构根节点 `ancestors='0'` 既不等于 `',0'` 也不 LIKE `',0,%'`，超管取全平台会静默返回空集），再对业务表 `WHERE node_id IN (...)`；结果集可缓存至 Redis，节点移动时失效 | `idx_ancestors(255)` |
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

10. **撤销授权与已分发作业解耦**：撤销课程授权**不影响已分发的作业**（作业是已下达的任务，不是资源）。学员仍可作答、教师仍可批改、成绩仍计入统计；仅失去课程内容的继续访问权。否则会出现作业中途消失、成绩缺失的严重业务事故。

11. **受管资源的授权目标类型限制**：`resource_type` 为 2（题目）或 3（视频）时**不得授权给学生节点**（`node_type=3`）。
    学生侧没有题目/视频的直接使用入口——作答走 `hw_homework_target` + 固化版本，播放走课程授权，错题本走 `question_version` 快照，三条路径都与题目/视频授权解耦。授给学生的行永远不会被任何鉴权路径读到，只会放大授权表规模并制造"悬挂授权"误报。

12. **资源被删除/停用时，授权行一律保留，不做级联撤销**（与规则 5 的边界，务必分清）：
    - 规则 5 的级联，触发条件是**撤销某个节点对某资源的授权**——沿目标节点子树级联，解决的是"父级已无权、子级仍持有"。
    - 本条针对的是**资源自身被逻辑删除或停用**（课程下架、视频禁用 `status=9`、题目停用、题目/课程逻辑删除）。此时 `org_resource_grant` 的行**原样保留**，可用性由**资源状态**在使用侧拒绝（课程 `20013`、题目与视频按可见性 404）。
    - 理由：资源状态是可逆的（下架可再上架、停用可再启用、软删可恢复），而级联撤销不可逆——一次误下架就会清空全机构成百上千条授权，恢复上架后所有人依然无权，只能逐级重授。保留授权行则资源恢复后授权自动重新生效。
    - 因此**任何"删除资源"接口都不得写"级联撤销其全部授权行"**；巡检时指向已删除/已停用资源的授权行**不计为悬挂授权**（见规则 6 的分类计数）。

**权限模板（解决逐级显式授权的操作成本）**：

逐级显式授权的代价是运维负担（新招一名教师要逐个勾选几百个资源），用模板抵消：

- `org_perm_template` 定义一组资源清单（如"高三数学包" = 20 课程 + 500 题目 + 80 视频），`org_perm_template_item` 存明细。
- **适用于创建任意下级节点**：新建下级管理员、教师、学生时均可套用模板一键授权，生成的授权行 `grant_source=5`（按模板）；也可对已存在的节点追加套用。
- **模板只管资源，不含功能权限**：模板内容仅为课程/题目/视频清单，不包含角色、菜单、按钮等操作权限——操作权限由角色决定（见第 3 节），与模板无关。
- **模板不绕过收缩规则**：应用模板时，实际授权 = 模板资源清单 **∩ 授权人当前拥有的资源**。模板中授权人已无权的部分自动跳过并在响应中列出，**绝不放大权限**。
- **批量授权的总量硬上限：单次写入的授权行数 ≤ 5000**（直接授权按 `资源数 × 目标节点数`，套用模板按 `交集后资源数 × 目标节点数`）。模板明细上限 2000 项 × 目标节点上限 500 个 = 100 万行，放进一个同步事务足以拖垮主库，而调用方只是点了一次按钮。超限整体拒绝、不落任何行，提示分批。
- **模板可见范围是铁律 2 的唯一显式例外**（仅作用于模板对象本身，不影响任何业务数据范围）：
  `可见模板 = 我自己建的 ∪ 我的祖先节点建的 ∪ 我子树内节点建的`（即整条根到叶路径 ∪ 子树）。
  纳入"祖先建的"是必要的——否则机构统一制定的模板下级根本看不到，模板就失去了抵消操作成本的意义。
  **该例外的安全性要靠两条一起保证，缺一不可**：

  1. **使用权维度——取交集**：套用时实际授权 = 模板 ∩ 授权人已有资源，**永远不可能授出授权人本就没有的资源**。同一个"高三数学包"，机构管理员用能授出 20 门课，只拿到 5 门的下级管理员用就只授出 5 门。
  2. **存在性维度——祖先模板的明细必须脱敏**：查看**祖先节点建的**模板明细时，**只返回调用者当前拥有的条目**，其余折叠为计数 `{total, visible, hidden}`；本人建的与子树内建的不受此限，明细全量返回。套用响应中的 `skippedResources` 同理——祖先模板下恒为空数组，只回 `skippedCount`。

  **第 2 条不是第 1 条的推论，必须单独实现**。取交集只管"授不出去"，管不了"看得见"：`org_perm_template_item` 就是一张 `(resource_type, resource_id, resource_name)` 清单，若祖先模板的明细全量返回，下级管理员打开机构统一建的"高三数学包"，**当场就能枚举出上级拥有但从未授予他的全部资源 ID 与名称**——这正是 FR-2 规则 2 要求"请求未授予资源的详情按不存在处理、不暴露存在性"所禁止的。**套不出来 ≠ 看不见。**

  脱敏只作用于**响应**，不改变套用行为：服务端仍按完整模板清单取交集，结果与不脱敏时逐字相同。

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

### 2.8 无会话上下文的写入（回调、定时任务、异步 Worker）

MyBatis-Plus 租户插件从**当前会话**取 `tenant_id` 并自动注入 `WHERE tenant_id = ?` 与 INSERT 的列值。这套机制对着 Web 请求设计，而系统里有三类写入**根本没有会话**：

| 入口 | 为什么没有会话 | 不处理的后果 |
| --- | --- | --- |
| 云厂商转码回调 `POST /api/v1/vod/callback/{provider}` | 云厂商服务端直连，无 `Authorization`，走签名校验 | 插件取不到 `tenant_id` → 回写 `vod_video.status` 的 UPDATE 命中 0 行，**转码永远停在"转码中"** |
| XXL-Job 定时任务（心跳落盘、日结算、作业截止扫描、授权巡检） | 调度器触发，无用户 | 要么写不进去，要么按调度线程残留的上下文**写进错误的租户** |
| 异步导入/导出 Worker | 从队列取任务，脱离原请求线程 | 同上 |

**统一规则（三条，实现必须逐条落实）**：

1. **上下文必须由数据显式携带，不得依赖线程残留**。回调从 `vod_video.vod_file_id` 反查行、取其 `tenant_id`；定时任务从任务参数或被处理行取；Worker 从 `org_import_task` / `sys_export_task` 行取。取到后用 `TenantHelper.setTenantId(...)` 显式设置，**处理完成后必须 `clear()`**——线程池会复用线程，不清理就是下一个任务串租户。
2. **跨租户的任务必须按租户分片执行**，每片开始前设上下文、结束后清理。禁止"一个事务里跨租户批量写"——那样任何一行的租户判定错误都会被整批放大。
3. **无法确定租户的写入一律拒绝并告警**，绝不"猜一个"或退化为忽略租户条件。回调若反查不到媒资行，返回 200 让云厂商停止重试（避免无限重试风暴），同时记 `sys_oper_log` 并触发告警——这是一次**静默的数据丢失**，必须有人看见。

> 回调接口另有两条与租户无关但同样必须的约束：**签名校验代替身份认证**（网关层仅对云厂商回调出口 IP 段放行），以及**幂等**（同一 `vod_file_id` 重复回调只生效一次，按 `uk_provider_file` 定位后判断状态是否已推进）。

### 2.9 平台级行的读取（`tenant_id = 0`）

**与 §2.8 是同一个问题的两半**：§2.8 治的是**写侧**——没有会话，插件取不到 `tenant_id`；本节治的是**读侧**——会话有 `tenant_id`，但要读的目标行是平台级的 `tenant_id = 0`。两者的共同根因是租户插件把"当前会话的租户"当成了唯一正确答案，而系统里存在**不属于任何租户、却要被所有租户读到**的数据。

**不处理的后果是系统开箱即不可用**：租户 A 的用户登录后调 `/auth/me` 加载权限，链路是 `sys_user_role → sys_role → sys_role_menu`。四个内置角色及其菜单绑定的 `tenant_id = 0`，插件注入 `AND tenant_id = A` 后**命中 0 行** → `roles = []`、`perms = []` → 前端所有按钮隐藏、后端所有 `@SaCheckPermission` 校验 403。**每一个非超管用户都是零权限**。

**承载平台级行的表（穷举，共 7 张 + `org_node` 的哨兵行）**：

| 表 | 平台级行是什么 | 是否同时存租户私有行 | 处置 |
| --- | --- | --- | --- |
| `sys_role` | 四个内置角色（super_admin / org_admin / teacher / student） | **是**（租户自建角色，如"教务主任"） | **放行 0** |
| `sys_role_menu` | 内置角色的菜单/按钮绑定 | **是**（自建角色的绑定） | **放行 0** |
| `sys_user` | 平台超管账号 | 是 | 严格过滤 |
| `sys_user_role` | 超管的角色绑定 | 是 | 严格过滤 |
| `sys_file` | 超管上传的文件 | 是 | 严格过滤 |
| `sys_login_log` | 超管登录日志 | 是 | 严格过滤 |
| `sys_oper_log` | 超管操作日志 | 是 | 严格过滤 |
| `org_node` | 平台根哨兵（`id = 0`，`node_type = 0`） | 是 | 严格过滤（见下） |

**定案：只对 `sys_role` 与 `sys_role_menu` 放行，注入条件改为 `AND (tenant_id = #{当前租户} OR tenant_id = 0)`。其余各表维持严格 `AND tenant_id = #{当前租户}`。**

**为什么不用 `ignoreTable` 忽略清单（方案 a）**：`ignoreTable` 是**整表**开关，一旦忽略，该表就**完全失去租户过滤**。而上表第三列显示——**这 7 张表没有一张是纯平台级的**，每一张都同时装着租户私有行：忽略 `sys_role` 意味着租户 A 能列出、甚至改删租户 B 自建的"教务主任"角色；忽略 `sys_oper_log` 意味着操作日志跨租户全裸。`ignoreTable` 只适用于**纯平台级、无租户列**的表——本系统里那就是 `sys_menu` 与 `sys_tenant`，它们本来就不带 `tenant_id`，压根不进插件。所以方案 (a) 在本系统无一处可用。

**为什么放行范围要收到这 2 张表**：`OR tenant_id = 0` 的代价是把该表的平台级行暴露给所有租户，所以只在"平台级行本就该被所有租户读到"时才成立。

- `sys_role` / `sys_role_menu` 成立：内置角色就是**全租户共用的定义**，租户看到它们是设计意图——03-01 §3.1 早已写明数据权限是"org_admin 仅本租户角色 **+ 平台预置角色**"，此前只是缺了让它成立的机制。
- 其余 5 张不成立：放行会把**超管本人的账号、手机号、登录轨迹与操作日志**暴露给每一个租户管理员。超管读自己这些行不靠放行，而靠**租户插件对超管整体放行**（超管会话跨租户，02-数据库设计 §3.2）——这是两条不同的通道，不要混用。

**`org_node` 的哨兵行为什么不放行，且不需要放行**：

1. **登录停用校验不受影响**。§2.3 的祖先链查询条件是 `node_type = 1 AND status = 1`，而哨兵行是 `node_type = 0`，**它永远不可能命中这个条件**。哨兵被过滤掉与被查出来，结果完全一样。
2. **面包屑本就不该显示它**。`nodePath` 的口径是"自**租户根**到自身"（03-05），平台根不属于任何租户，出现在租户的面包屑里反而是越界。
3. 因此实现上：**解析 `ancestors` 时必须跳过首位的哨兵 `0`**。`ancestors` 形如 `0,机构id,...`，首位 `0` 是路径哨兵而非可读节点。若按 `IN (拆出的全部 id)` 查名称，返回行数会比 id 数少 1——**这是正确行为，不是 bug**，不要"修"成放行哨兵。

**放宽的只是读，写侧必须反向收紧**：`sys_role` / `sys_role_menu` 的 `tenant_id = 0` 行是**全平台所有租户共用的同一行**，因此对 `org_admin` **全只读**——改预置角色的名称、状态或菜单绑定，会让**所有租户**跟着变（停用预置 `teacher` 角色即全平台教师失权）。这四行只有 `super_admin` 可改，且任何人不可删。放行解决的是"读不到自己的权限定义"，不是"可以改别人的"，两件事必须分别落实（03-01 §3 导语与 §3.4 / §3.5 / §3.6）。

**实现约束**：放行逻辑写在 `TenantLineHandler` 的表达式构造里（返回 `tenant_id = ? OR tenant_id = 0` 而非单等式），**不要靠业务代码逐处手写 `OR tenant_id = 0`**——漏一处就是一次零权限或一次越权，且这类漏写不会报错，只会表现为"某个页面按钮没了"。

**新增带 `tenant_id` 的表时，必须先回答"这张表会不会有 `tenant_id = 0` 的行"**，答案为是则必须在上表登记并定案，否则默认严格过滤。

## 3. 角色与操作权限

**操作权限（能执行什么）由固定角色决定，与树的位置无关**；树只决定数据范围。

| 角色 | role_key | user_type | 绑定节点类型 | 可执行的关键操作 |
| --- | --- | --- | --- | --- |
| 平台超管 | `super_admin` | 0 | 平台（树根） | 租户开通、平台配置 |
| 管理员 | `org_admin` | 1 | 管理员节点（机构根节点即机构最高管理员） | 建下级管理员、建教师、建学生、分配学员、资源下发、全模块管理 |
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
| **`org_node`** | **统一组织树（所有节点都是人：平台超管/管理员/教师/学生）** | parent_id(根为0), **ancestors**(逗号祖级串), node_name, **node_type**(0平台超管 1管理员 2教师 3学生，取值同 sys_user.user_type), **ref_user_id**(NOT NULL，每个节点都是一个人，指向 sys_user.id), sort, status(0正常1停用), child_count/student_count(冗余) |
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
| `qb_question` | 题目主表（物理 ID 恒定） | id(雪花,永不复用), **owner_node_id**(归属节点), category_id, question_type(1单选2多选3判断4填空5简答6材料题), parent_id(子题→父题id), difficulty(1-5), current_version, stem_preview, status(0草稿1启用2停用)（创建人取公共字段 `create_by`） |
| `qb_question_version` | 题目版本快照（不可变） | question_id, version, content(JSON), correct_answer(JSON), analysis, score_default（创建人/时间取公共字段 `create_by` / `create_time`）；UK(question_id,version) |

**版本规则**：编辑题目 = 写入新 `qb_question_version`（version+1）并更新 `current_version`；历史版本不可修改、不可删除。

### hw_（6）
| 表名 | 说明 | 关键字段 |
| --- | --- | --- |
| `hw_homework` | 作业/试卷定义（**注：作业不是"受管资源"**，不可经 `org_resource_grant` 下发；`owner_node_id` 仅作归属锚点与数据权限依据） | homework_name, homework_type(1作业2考试), **owner_node_id**, course_id(可空), total_score, question_count(冗余), deadline, publish_time, allow_late_submit, answer_visible_type(1提交后2截止后), status(0草稿1已发布2已截止3已撤回) |
| `hw_homework_question` | 作业-题目（**发布时固化版本**） | homework_id, question_id, question_version(锁定), score, sort；UK(homework_id,question_id) |
| **`hw_homework_target`** | **分发对象（全量精确到学生）** | homework_id, **student_id**（原 target_type/target_id 废弃）, **grant_source**(1手动选择2按节点批量3按标签批量4按名下全体), source_ref_id(可空)；UK(homework_id,student_id) |
| **`hw_answer_sheet`** | 学生答卷 | homework_id, student_id, **teacher_node_id**(作答时导师节点快照，取代 class_id), status(0未开始1作答中2已提交待批改3已批改4逾期未交), objective_score, subjective_score, total_score, submit_time, is_late, grade_teacher_id, grade_time；UK(homework_id,student_id) |
| `hw_answer_detail` | 逐题作答明细 | answer_sheet_id, question_id, question_version(快照), student_answer(JSON), is_correct(0错误 1正确 2半对，待批改 NULL；取值定义见第 5 节), score, auto_graded, comment, grade_time |
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
| **node_type 节点类型** | **0平台超管 1管理员 2教师 3学生**（与 `user_type` 取值完全一致，一一对应） |
| **student_status 学籍状态** | **0在读 1已退课(流失) 2毕业归档** |
| **change_type 节点异动类型** | **1建档 2分配导师 3转交管理员 4教师调岗 5毕业归档 6归档恢复 7退课 8节点移动(管理员节点自身改父)** |
| **resource_type 受管资源类型** | **1课程 2题目 3视频** |
| **grant_source 授权/分发来源** | **1手动选择 2按节点批量 3按标签批量 4按名下全体 5按权限模板** |
| watch_status 完播状态 | 0未开始 1学习中 2已完成 |
| lesson_type 课时类型 | 1视频 2图文 |
| question_type 题型 | 1单选 2多选 3判断 4填空 5简答 6材料题(父题) |
| homework_status 作业状态 | 0草稿 1已发布 2已截止 3已撤回 |
| sheet_status 答卷状态 | 0未开始 1作答中 2已提交待批改 3已批改 4逾期未交 |
| video_status 媒资状态 | 0上传中 1转码中 2正常 3转码失败 9禁用 |
| is_correct 判定 | 0错误 1正确 2半对(多选漏选/填空部分对/主观题部分得分) NULL待批改 |

**答案 JSON 结构（`qb_question_version.correct_answer` 与 `hw_answer_detail.student_answer` 共用同一形状，按题型穷举）**：

| question_type | JSON | 自动判分 | 说明 |
| --- | --- | --- | --- |
| 1 单选 | `{"answer": "B"}` | 是 | 选项号大写字母，字符串 |
| 2 多选 | `{"answer": ["A","C"]}` | 是 | 字符串数组；**比较前必须排序去重**，`["C","A"]` 与 `["A","C"]` 等价 |
| 3 判断 | `{"answer": true}` | 是 | **JSON 布尔字面量，不是字符串 `"true"`** |
| 4 填空 | `{"blanks": [{"index": 1, "text": "北京"}]}` | 是 | `index` 从 1 起；判分按 `index` 对齐，逐空比较 |
| 5 简答 | `{"text": "……"}` | 否 | 进人工批改 |
| 6 材料题 | 父题不存答案，逐子题按其自身题型取上述结构 | 随子题 | 父题分数 = 子题之和 |

> **判断题必须是布尔而非字符串**，这一条单独强调：两者在 JSON 里都"看得过去"，但 `true != "true"`，一旦两端不一致，**全部判断题一律判错**且客观题不开放教师改分（PRD F3-6 规则 3），错了没有救济路径——是最典型的"上线才发现、发现了也不好补"的缺陷。服务端反序列化时必须做类型校验，收到 `"true"` 直接返回 400，不做隐式转换。
>
> 自动判分只在 `question_type ∈ {1,2,3,4}` 上执行；比较前统一：多选排序去重、填空按 `index` 对齐并去首尾空白。

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
- 逻辑删除统一用 `DELETE` 方法（后端将 `deleted_at` 由 0 置为**当前毫秒时间戳**，非 0/1 标志，见 §2.2）

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
Body: {"lessonId":"...","videoId":"...","currentTime":123.4,"duration":600,"playRate":1.0,"seeked":false,"sessionId":"..."}
Resp: {"code":200,"data":{"watchedDuration":130,"watchStatus":1,"maxPosition":135}}
服务端逻辑：间隔合理性校验(≥8s 视为有效，单次计入 min(实际间隔,15)s，>15s 封顶不报错；<8s 丢弃返回 20002) → 写 Redis
(key: vod:hb:{studentId}:{lessonId}) → XXL-Job 每 60s 批量落盘 vod_watch_progress，落盘时判定完播。
```

**`seeked` 是必填布尔**：前端在**用户拖动进度条后**或**暂停恢复后**的第一次心跳置 `true`，其余一律 `false`。

它存在的唯一理由是解开一处规则冲突：系统**明确允许**把进度条拖回 `maxPosition` 之前自由复看（PRD F2-5 规则 1），复看同样计时长（F2-7 **处理流程 2**）；而防刷的推进一致性校验（03-03 §8.2.1 规则 6）要求 `Δ currentTime` 落在 `[0.5,1.5] × Δt × playRate`。学生从 500s 拖回 120s 后，下一次心跳的 `Δ currentTime = -380`，直接出区间被判异常丢弃——**一个被明确允许的行为会被持续判为作弊且不计时长**。暂停后原地续播同理（`Δt` 大而 `Δ currentTime` 近 0）。

服务端据此处理：`seeked = true` 的心跳**重置推进校验基准**，本次不做推进判定但**正常计时**；推进一致性只在**连续两次 `seeked = false`** 的心跳之间校验。

**`sessionId` 是必填字符串**：取自 8.1 播放凭证响应，标识**本次播放**（续签凭证不换会话）。服务端按 `{studentId}:{lessonId}` 维护当前活跃会话，实现**同课时会话择一**：同会话正常计时；不同会话且当前会话 60s 内活跃则返回 `20020`；超 60s 无心跳则新会话接管，并重置规则 6 的推进基准。

它存在的理由是多端并发下的**永久归零**：规则 2 按"上一次被采纳心跳"算间隔，两端交替时只有一端被采纳、时长本身满速率累计；但被采纳的那端一停播，另一端的 `Δ currentTime` 是相对前一端最后位置算的（两端位置无关，动辄相差数百秒），必然出区间被规则 6 拒绝——而**被拒的心跳不推进基准**，下一次仍以同一个陈旧位置为基准，就此锁死。学生只是多开了一个标签页，此后累计时长约等于 0，前端毫无提示。

> **被拒绝的心跳一律不推进任何基准**（时间基准、位置基准、`maxPosition`）。唯一例外是会话接管：接管时必须重置位置基准，否则新设备第一条心跳就被判位置跳变，接管等于没发生。

> **`seeked` 由客户端提供，因此必须防止它被用来永久关闭规则 6**：脚本只要每次都置 `true` 就能以 1:1 墙钟速度刷时长而无需下载任何分片。约束见 03-03 §8.2.1 规则 6——连续 `seeked = true` 不得超过 2 次、且单课时每小时不超过 20 次，超出按异常丢弃并计风控分。真实用户 seek 后会继续播放，不存在"每 10 秒 seek 一次"的模式。

## 7. 运行期约定（可观测性 / 合规 / 库变更）

前六节定的是"系统长什么样"，本节定的是"上线后怎么活着"。三块内容都属于**签约与运维的前置条件**，不是可以留到后期补的技术债。

### 7.1 可观测性

**链路追踪**：每个入站请求在网关生成 `traceId`（32 位 hex），经 MDC 贯穿日志、异步线程池、XXL-Job 与 MQ；**响应头 `X-Trace-Id` 原样回传**，出问题时用户截图即可定位。异步任务从触发方继承 traceId，继承不到则新生成并记录 `parentTraceId`。

**必须落地的监控指标**（Micrometer → Prometheus）：

| 指标 | 类型 | 告警线 | 为什么是它 |
| --- | --- | --- | --- |
| `heartbeat_flush_lag_seconds` | Gauge | > 180s | 心跳落盘滞后即学习时长丢失，且用户无感知 |
| `heartbeat_reject_total{rule}` | Counter | 单租户 5min 内 > 1000 | 按 8 条防刷规则分标签；突增说明有人在刷或前端发版出错 |
| `grant_dangling_count` | Gauge | **> 0** | 契约 §2.5 规则 6 的真悬挂授权，目标值恒为 0；`crossScopeCount` 单独打点，**不进告警** |
| `stat_settle_job_duration_seconds` | Histogram | P99 > 30min | 日结算跑不完则次日看板空白 |
| `vod_callback_orphan_total` | Counter | **> 0** | 回调反查不到媒资行（§2.8 规则 3），每一次都是静默的数据丢失 |
| `tree_move_depth` / `tree_move_subtree_size` | Histogram | 子树 > 5000 告警 | 大子树移动会长时间持有 `ancestors` 重算的写锁 |
| `api_permission_denied_total{code}` | Counter | 单账号 5min 内 > 100 | 403/404/10107 突增 = 越权探测 |

**日志分级**：越权拒绝（403 / 404 / 10107）一律 `WARN` 并带 `traceId + userId + 目标对象 ID`；数据权限过滤条件为空集时 `ERROR`——那意味着过滤逻辑写漏了，正在返回全量数据。

**慢查询**：`FIND_IN_SET` 出现在慢查询日志中即视为缺陷（§2.4 明令它不得进高频查询），配置慢查询阈值 200ms 并对该函数单独告警。

### 7.2 合规（K12 场景，签约前置项）

本系统面向 K12 培训机构，`org_student` 收集**未成年人姓名、手机号与监护人手机号**（`guardian_name` / `guardian_phone`），落在《个人信息保护法》第 31 条"不满十四周岁未成年人个人信息"的敏感个人信息范畴。以下是硬要求，不是加分项：

1. **监护人同意留痕**：学员建档（含 Excel 批量导入）时必须记录同意来源与时间。机构线下签署的知情同意书由机构留存，系统侧至少记录"机构已确认取得监护人同意"的操作人与时间戳，写入 `sys_oper_log`；导入场景记入 `org_import_task`。
2. **最小必要**：不收集与教学无关的信息（住址、身份证号、人脸、精确位置一律不采集）。跑马灯水印展示**姓名 + 手机号后 4 位**，不得展示完整手机号。
3. **数据删除请求**：监护人有权要求删除。系统提供的路径是**学籍归档 + 账号逻辑删除**，并在 30 日内对 `guardian_phone` / `phone` 做不可逆脱敏（保留掩码位供对账）。学习记录与答卷本身是机构的教学档案，按约定保留期留存，与个人身份标识解绑后不再构成个人信息。
4. **出境与第三方**：视频经腾讯云/阿里云 VOD 存储与分发，属委托处理，须在隐私政策中列明受托方；**不得开启任何境外节点**。
5. **审计留存**：`sys_login_log` / `sys_oper_log` 保留 ≥ 6 个月（《网络安全法》第 21 条），且这两张表不参与"删除请求"的清理。

> 这几条写在契约里而不是留给实施方，是因为**它们会决定表结构**（同意留痕字段、脱敏而非物理删除的删除路径），事后补要改数据模型。

### 7.3 数据库变更管理

**上线后 DDL 不再手工执行**，一律走 Flyway：

- 脚本命名 `V{yyyyMMddHHmm}__{描述}.sql`，只增不改；已发布的脚本**永不修改**，修正靠新增脚本。
- `docs/sql/edumatrix_ddl.sql` 是**初始基线**（Flyway `V202608120000__baseline.sql`），此后所有变更以增量脚本表达，该文件不再随手改动——否则新老环境会分叉。
- 每个脚本必须**幂等或可重复执行前置判断**（`ADD COLUMN IF NOT EXISTS` 之类 MySQL 不支持，改为脚本内先查 `information_schema` 再决定），并配套一份回滚说明（不要求可执行的 down 脚本，但要写清怎么退）。

**大表在线 DDL**：`hw_answer_detail` 稳态约 3600 万行、`vod_heartbeat_log` 亿级。MySQL 8.0 的 Online DDL 对加列是 INSTANT，但**加索引、改列类型仍会长时间占用元数据锁**。约定：

| 变更类型 | 方式 |
| --- | --- |
| 加列（无默认值/有常量默认值） | `ALGORITHM=INSTANT`，可直接执行 |
| 加索引 | `ALGORITHM=INPLACE, LOCK=NONE`，低峰执行并监控主从延迟 |
| 改列类型 / 加 NOT NULL / 重建表 | **必须走 gh-ost 或 pt-online-schema-change**，禁止直接 ALTER |
| `vod_heartbeat_log` 的任何结构变更 | 先在 `pmax` 之外的历史分区验证，禁止全表重建 |

**红线**：任何变更脚本在预发环境跑过一次真实数据量的耗时测算之前，不得进生产；耗时 > 30s 的变更必须走在线 DDL 工具。

## 8. 文档产出物与分工

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

## 9. 不可违反的铁律与表总数

### 8.1 表总数与构成

`sys_10 + org_10 + crs_4 + vod_4 + qb_3 + hw_6 + stat_4 = 41 张`。

### 8.2 三条不可违反的铁律

1. **树不成环**：任何节点移动都必须先做后代校验，且 `ancestors` 与移动在同一事务内重算。
2. **数据范围只由树决定**：不存在第二套数据权限逻辑；标签、角色、资源授权都不得扩大可见范围。
3. **资源只能向自己子树下发，且不得超出自己拥有的范围**：授权链**每一层都显式、无继承**，不可越权横向或向上授权；权限模板只是批量操作的快捷方式，应用时取交集，**绝不放大权限**。撤销必须级联到子树，不留悬挂授权。
