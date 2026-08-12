# EduMatrix — ToB 私域督学管理平台

面向教育机构 / 培训机构的强管控在线学习管理系统。Web 端（PC 管理端 + 学生 H5），API-First，为微信小程序 / App 预留标准接口。

## 文档导航

| 文档 | 说明 |
| --- | --- |
| [docs/00-原始需求.md](docs/00-原始需求.md) | 客户原始需求基线 |
| [docs/DESIGN-CONTRACT.md](docs/DESIGN-CONTRACT.md) | **设计契约**：表名/字段/枚举/路由/错误码的唯一权威，所有文档以此为准 |
| [docs/01-PRD-产品需求文档.md](docs/01-PRD-产品需求文档.md) | 产品需求文档（角色与权限、五大模块功能详述、验收标准、页面清单、50 条边界场景） |
| [docs/02-数据库设计.md](docs/02-数据库设计.md) | 数据库设计说明（ER 图、核心设计要点、逐表字段、索引、分区、容量估算） |
| [docs/sql/edumatrix_ddl.sql](docs/sql/edumatrix_ddl.sql) | 可执行 DDL（MySQL 8.0，**41 张表**，已实测执行通过） |
| [docs/03-API接口文档/](docs/03-API接口文档/) | API 接口文档（6 个分册，**244 个接口**，见 00-通用约定 内目录） |
| [references/README.md](references/README.md) | 参考开源仓库导读（RuoYi-Vue-Plus / roncoo-education / xzs / DPlayer） |

## 核心设计决策速览

- **统一组织树**：机构、管理员、教师、学生**全部是 `org_node` 上的节点**（`node_type` 1/2/3/4），一棵树到底。师生关系由树的父子结构表达——学生挂在教师节点下即该导师名下学员
- **数据权限只有一条规则**：**你能看到的数据 = 你所在节点的子树**（`id = #{myNodeId} OR FIND_IN_SET(#{myNodeId}, ancestors)`）。全部角色适用，无第二套逻辑
- **资源逐级下发**：课程 / 题目 / 视频经 `org_resource_grant` **每级显式授权、不向下继承**，只能授权自己拥有的、只能授给自己子树内的；撤销级联到子树；权限模板套用时取交集，绝不放大权限
- **机构 = 租户**：`tenant_id` 硬隔离，与子树权限是两道独立防线
- **视频防刷**：VOD 加密 HLS + 300s 播放凭证 + 禁快进（maxPosition 前可回看）+ 跑马灯水印 + 10s 心跳（≥8s 有效、单次封顶 15s；Redis 缓冲，60s 批量落盘并判定完播）
- **题库版本防错乱**：题目雪花物理 ID 恒定，编辑即生成不可变版本快照 `qb_question_version`；作业发布时固化版本；错题本绑定做错时刻版本
- **统计双快照上卷**：`stat_student_daily` 为唯一事实表，凭行内 `teacher_node_id` / `node_id` 快照分别上卷到导师维度与节点维度；历史归原导师、以自然日结算
- **软删除**：核心业务数据一律 `is_deleted` 逻辑删除，禁止物理删除

## 三条不可违反的铁律

1. **树不成环** —— 节点移动必须先做后代校验，`ancestors` 重算与移动同事务
2. **数据范围只由树决定** —— 标签、角色、资源授权都不得扩大可见范围
3. **资源只能向自己子树下发，且不得超出自己拥有的范围** —— 逐级显式、无继承、撤销级联、有效期逐级截断

## 技术栈基线

Java 17 · Spring Boot 3.x · MyBatis-Plus · Sa-Token · MySQL 8.0 · Redis 7 · Vue 3 + Element Plus · DPlayer（二开）· 腾讯云 VOD（阿里云兼容适配）
